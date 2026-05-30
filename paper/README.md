# CONCLAVE — research paper

*CONCLAVE: A Domain-Agnostic Multi-Agent Architecture for Real-Time Explainable
Risk Detection* — arXiv preprint, ACM `sigconf` (7 pages).

## Build

```bash
cd paper
latexmk -pdf conclave.tex      # → conclave.pdf
```

Needs a TeX Live 2025 install with `acmart`, `tikz`, `pgfplots`, `booktabs`,
`multirow`, `listings`. `latexmk` runs the pdflatex/bibtex passes automatically.

## Data provenance — every number is reproducible

The figures and tables are **not hand-typed**. They are generated from a clean,
end-to-end benchmark run of the live stack:

1. `benchmark/capture.py --domain {fraud,security} --tag serving`
   pages the M7 audit API for all persisted decisions, drains the ground-truth
   label topic (`events.{domain}.labels`) from Kafka, joins on `event_id`, and
   writes `benchmark/results/{domain}-serving.json` (confusion matrices,
   ROC-AUC, per-attack-type recall, score separation, latency percentiles).
2. `benchmark/make_paper_data.py` turns those JSON files into
   `paper/data/numbers.tex` (the `\newcommand` macros every figure quotes) plus
   the `*_hist.dat` / `latency.dat` files the `pgfplots` charts read.

So to refresh the paper after a new benchmark run:

```bash
python3 benchmark/capture.py --domain fraud    --tag serving
python3 benchmark/capture.py --domain security --tag serving
python3 benchmark/make_paper_data.py
cd paper && latexmk -pdf conclave.tex
```

## The run behind the current numbers

- **Judge:** `google/gemini-3.1-flash-lite` via OpenRouter (serving mode). The
  architecture is provider-agnostic; the spec's canonical judge is Claude
  Haiku 4.5.
- **Load (per domain):** `--customers 8 --days 7 --events-per-day 3 --clean 40
  --rings 2 --ato 2 --extra 2` → 332 fraud / 254 security decisions, 0 DLQ.
- **Headline:** fraud ROC-AUC 0.999 (zero false-positive blocks, 100% of
  attacks flagged at review+); security ROC-AUC 0.819 (lateral movement caught,
  exfiltration/ATO missed — the evidence-coverage finding).

## Files

| File | Purpose |
|---|---|
| `conclave.tex` | the paper source (inline TikZ diagrams + pgfplots) |
| `references.bib` | bibliography |
| `data/numbers.tex` | auto-generated metric macros (do not edit by hand) |
| `data/*_hist.dat`, `data/latency.dat` | auto-generated plot data |
| `conclave.pdf` | the built paper (7 pages) |

Build artifacts (`*.aux`, `*.bbl`, `*.log`, …) are git-ignored; `latexmk` regenerates
them. For an arXiv upload, run `latexmk -pdf` once and include the generated `conclave.bbl`.
