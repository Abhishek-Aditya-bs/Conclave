#!/usr/bin/env python3
"""CONCLAVE benchmark capture harness.

Joins persisted decisions (M7 audit API) against the ground-truth labels that
the M9 generators publish on `events.{domain}.labels` (which the orchestrator
never sees), then computes detection + latency metrics for the paper.

Usage:
    python3 benchmark/capture.py --domain fraud --tag run1
    python3 benchmark/capture.py --domain security --tag run1

Writes benchmark/results/<domain>-<tag>.json and prints a summary.

No third-party deps beyond numpy (stdlib urllib for HTTP, docker CLI for Kafka).
"""
from __future__ import annotations

import argparse
import json
import subprocess
import sys
import urllib.request
from collections import Counter, defaultdict

import numpy as np

API = "http://localhost:8080/api/v1/decisions"
KAFKA_CONTAINER = "conclave-kafka"
KAFKA_BOOTSTRAP = "kafka:29092"


def fetch_decisions(domain: str) -> list[dict]:
    """Page through the audit API and return every decision for a domain."""
    out: list[dict] = []
    offset, limit = 0, 500
    while True:
        url = f"{API}?domain={domain}&limit={limit}&offset={offset}"
        with urllib.request.urlopen(url, timeout=30) as r:
            page = json.load(r)
        items = page.get("items", [])
        out.extend(items)
        total = page.get("total", len(out))
        offset += limit
        if offset >= total or not items:
            break
    return out


def fetch_labels(domain: str, timeout_ms: int = 12000) -> dict[str, dict]:
    """Drain the ground-truth label topic; return {event_id: label_record}."""
    topic = f"events.{domain}.labels"
    cmd = [
        "docker", "exec", KAFKA_CONTAINER,
        "kafka-console-consumer", "--bootstrap-server", KAFKA_BOOTSTRAP,
        "--topic", topic, "--from-beginning", "--timeout-ms", str(timeout_ms),
    ]
    proc = subprocess.run(cmd, capture_output=True, text=True)
    labels: dict[str, dict] = {}
    for line in proc.stdout.splitlines():
        line = line.strip()
        if not line:
            continue
        try:
            rec = json.loads(line)
        except json.JSONDecodeError:
            continue
        labels[rec["event_id"]] = rec
    return labels


def roc_auc(y_true: np.ndarray, scores: np.ndarray) -> float:
    """Mann-Whitney U formulation of ROC-AUC (tie-aware)."""
    pos = scores[y_true == 1]
    neg = scores[y_true == 0]
    if len(pos) == 0 or len(neg) == 0:
        return float("nan")
    order = np.argsort(scores, kind="mergesort")
    ranks = np.empty(len(scores), dtype=float)
    ranks[order] = np.arange(1, len(scores) + 1)
    # average ranks for ties
    s_sorted = scores[order]
    i = 0
    while i < len(s_sorted):
        j = i
        while j + 1 < len(s_sorted) and s_sorted[j + 1] == s_sorted[i]:
            j += 1
        if j > i:
            avg = (ranks[order[i]] + ranks[order[j]]) / 2.0
            for k in range(i, j + 1):
                ranks[order[k]] = avg
        i = j + 1
    sum_ranks_pos = ranks[y_true == 1].sum()
    n_pos, n_neg = len(pos), len(neg)
    auc = (sum_ranks_pos - n_pos * (n_pos + 1) / 2.0) / (n_pos * n_neg)
    return float(auc)


def precision_recall_at_fpr(y_true: np.ndarray, scores: np.ndarray, target_fpr: float):
    """Sweep thresholds; return the operating point whose FPR is closest to (and
    <=) target_fpr, with its precision/recall/threshold."""
    thresholds = np.unique(scores)[::-1]
    n_pos = int((y_true == 1).sum())
    n_neg = int((y_true == 0).sum())
    best = None
    for t in thresholds:
        pred = scores >= t
        tp = int(((pred == 1) & (y_true == 1)).sum())
        fp = int(((pred == 1) & (y_true == 0)).sum())
        fpr = fp / n_neg if n_neg else 0.0
        recall = tp / n_pos if n_pos else 0.0
        precision = tp / (tp + fp) if (tp + fp) else 0.0
        if fpr <= target_fpr:
            best = {"threshold": float(t), "fpr": fpr, "precision": precision,
                    "recall": recall, "tp": tp, "fp": fp}
        else:
            break
    return best


def pct(arr: np.ndarray, q: float) -> float:
    return float(np.percentile(arr, q)) if len(arr) else float("nan")


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--domain", required=True, choices=["fraud", "security"])
    ap.add_argument("--tag", default="run")
    args = ap.parse_args()

    decisions = fetch_decisions(args.domain)
    labels = fetch_labels(args.domain)
    if not decisions:
        print(f"!! no decisions for domain={args.domain}", file=sys.stderr)
        return 1

    # ---- join on event_id ----
    joined = []
    for d in decisions:
        lab = labels.get(d["event_id"])
        if lab is None:
            continue
        joined.append({**d, "truth_label": lab["label"]})

    n_dec = len(decisions)
    n_join = len(joined)

    verdict_mix = dict(Counter(d["verdict_label"] for d in decisions))
    provider = dict(Counter(d["judge_provider"] for d in decisions))
    model = dict(Counter(d["judge_model"] for d in decisions))
    truth_mix = dict(Counter(j["truth_label"] for j in joined))

    scores = np.array([j["score"] for j in joined], dtype=float)
    y_true = np.array([0 if j["truth_label"] == "CLEAN" else 1 for j in joined], dtype=int)
    verdicts = [j["verdict_label"] for j in joined]

    # ---- confusion matrices at two operating points ----
    def confusion(pred_positive_labels):
        pred = np.array([1 if v in pred_positive_labels else 0 for v in verdicts])
        tp = int(((pred == 1) & (y_true == 1)).sum())
        fp = int(((pred == 1) & (y_true == 0)).sum())
        fn = int(((pred == 0) & (y_true == 1)).sum())
        tn = int(((pred == 0) & (y_true == 0)).sum())
        precision = tp / (tp + fp) if (tp + fp) else 0.0
        recall = tp / (tp + fn) if (tp + fn) else 0.0
        f1 = 2 * precision * recall / (precision + recall) if (precision + recall) else 0.0
        fpr = fp / (fp + tn) if (fp + tn) else 0.0
        acc = (tp + tn) / (tp + tn + fp + fn) if (tp + tn + fp + fn) else 0.0
        return {"tp": tp, "fp": fp, "fn": fn, "tn": tn, "precision": precision,
                "recall": recall, "f1": f1, "fpr": fpr, "accuracy": acc}

    cm_block = confusion({"BLOCK"})
    cm_block_review = confusion({"BLOCK", "REVIEW"})

    auc = roc_auc(y_true, scores)
    pr_at_1 = precision_recall_at_fpr(y_true, scores, 0.01)
    pr_at_5 = precision_recall_at_fpr(y_true, scores, 0.05)

    # ---- per attack-type recall (BLOCK or REVIEW = flagged) ----
    per_type = {}
    flagged = {"BLOCK", "REVIEW"}
    by_type = defaultdict(lambda: [0, 0])  # label -> [flagged, total]
    for j in joined:
        if j["truth_label"] == "CLEAN":
            continue
        by_type[j["truth_label"]][1] += 1
        if j["verdict_label"] in flagged:
            by_type[j["truth_label"]][0] += 1
    for lab, (f, t) in by_type.items():
        per_type[lab] = {"flagged": f, "total": t, "recall": f / t if t else 0.0}

    # ---- score separation ----
    sep = {
        "clean_mean": float(scores[y_true == 0].mean()) if (y_true == 0).any() else None,
        "clean_p95": pct(scores[y_true == 0], 95) if (y_true == 0).any() else None,
        "attack_mean": float(scores[y_true == 1].mean()) if (y_true == 1).any() else None,
        "attack_p05": pct(scores[y_true == 1], 5) if (y_true == 1).any() else None,
    }

    # ---- latency (end-to-end deliberation, ms) ----
    lat = np.array([d["latency_ms"] for d in decisions], dtype=float)
    latency = {
        "n": len(lat), "mean": float(lat.mean()), "p50": pct(lat, 50),
        "p90": pct(lat, 90), "p95": pct(lat, 95), "p99": pct(lat, 99),
        "max": float(lat.max()), "min": float(lat.min()),
    }
    latency_by_verdict = {}
    for v in ("ALLOW", "REVIEW", "BLOCK"):
        vl = np.array([d["latency_ms"] for d in decisions if d["verdict_label"] == v], dtype=float)
        if len(vl):
            latency_by_verdict[v] = {"n": len(vl), "p50": pct(vl, 50), "p95": pct(vl, 95), "p99": pct(vl, 99)}

    # score histogram (10 buckets) for pgfplots
    hist_counts, hist_edges = np.histogram(scores, bins=10, range=(0.0, 1.0))
    score_hist = {"counts": hist_counts.tolist(), "edges": hist_edges.tolist()}
    # split histogram clean vs attack
    hc_clean, _ = np.histogram(scores[y_true == 0], bins=10, range=(0.0, 1.0))
    hc_attack, _ = np.histogram(scores[y_true == 1], bins=10, range=(0.0, 1.0))
    score_hist_split = {"clean": hc_clean.tolist(), "attack": hc_attack.tolist(), "edges": hist_edges.tolist()}

    result = {
        "domain": args.domain,
        "tag": args.tag,
        "n_decisions": n_dec,
        "n_joined_with_labels": n_join,
        "judge_provider": provider,
        "judge_model": model,
        "verdict_mix": verdict_mix,
        "truth_mix": truth_mix,
        "detection": {
            "roc_auc": auc,
            "confusion_block": cm_block,
            "confusion_block_or_review": cm_block_review,
            "precision_recall_at_fpr_1pct": pr_at_1,
            "precision_recall_at_fpr_5pct": pr_at_5,
            "per_attack_type_recall": per_type,
            "score_separation": sep,
        },
        "latency_ms": latency,
        "latency_by_verdict": latency_by_verdict,
        "score_hist": score_hist,
        "score_hist_split": score_hist_split,
    }

    out_path = f"benchmark/results/{args.domain}-{args.tag}.json"
    with open(out_path, "w") as f:
        json.dump(result, f, indent=2)

    # ---- console summary ----
    print(f"\n===== {args.domain.upper()} / {args.tag} =====")
    print(f"decisions={n_dec}  joined-with-labels={n_join}  model={list(model)}")
    print(f"verdict mix: {verdict_mix}")
    print(f"truth   mix: {truth_mix}")
    print(f"ROC-AUC = {auc:.4f}")
    print(f"BLOCK op point        : P={cm_block['precision']:.3f} R={cm_block['recall']:.3f} "
          f"F1={cm_block['f1']:.3f} FPR={cm_block['fpr']:.3f}  (TP={cm_block['tp']} FP={cm_block['fp']} FN={cm_block['fn']} TN={cm_block['tn']})")
    print(f"BLOCK+REVIEW op point : P={cm_block_review['precision']:.3f} R={cm_block_review['recall']:.3f} "
          f"F1={cm_block_review['f1']:.3f} FPR={cm_block_review['fpr']:.3f}")
    if pr_at_1:
        print(f"precision@FPR<=1%: P={pr_at_1['precision']:.3f} R={pr_at_1['recall']:.3f} thr={pr_at_1['threshold']:.3f}")
    print(f"per-type recall: { {k: round(v['recall'],3) for k,v in per_type.items()} }")
    print(f"score sep: clean_mean={sep['clean_mean']}, attack_mean={sep['attack_mean']}")
    print(f"latency ms: p50={latency['p50']:.0f} p95={latency['p95']:.0f} p99={latency['p99']:.0f} max={latency['max']:.0f}")
    print(f"wrote {out_path}\n")
    return 0


if __name__ == "__main__":
    sys.exit(main())
