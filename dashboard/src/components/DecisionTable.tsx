import { formatDistanceToNow, parseISO } from "date-fns";
import { ArrowRight } from "lucide-react";
import { Link } from "react-router-dom";
import type { DecisionSummary } from "../lib/types";
import { truncId } from "../lib/utils";
import { ScoreBadge } from "./ScoreBadge";
import { VerdictPill } from "./VerdictPill";

interface Props {
  items: DecisionSummary[];
  isLoading: boolean;
}

export function DecisionTable({ items, isLoading }: Props) {
  if (isLoading && items.length === 0) {
    return (
      <div className="px-4 py-8 text-center text-xs text-[color:var(--color-fg-subtle)]">
        loading…
      </div>
    );
  }
  if (items.length === 0) {
    return (
      <div className="px-4 py-8 text-center text-xs text-[color:var(--color-fg-subtle)]">
        no decisions match these filters yet
      </div>
    );
  }
  return (
    <div className="overflow-x-auto">
      <table className="w-full border-collapse text-sm">
        <thead>
          <tr className="border-b border-[color:var(--color-border)] text-left text-[10px] uppercase tracking-wider text-[color:var(--color-fg-subtle)]">
            <th className="px-3 py-2 font-medium">Score</th>
            <th className="px-3 py-2 font-medium">Verdict</th>
            <th className="px-3 py-2 font-medium">Domain</th>
            <th className="px-3 py-2 font-medium">Entity</th>
            <th className="px-3 py-2 font-medium">Event</th>
            <th className="px-3 py-2 font-medium">Judge</th>
            <th className="px-3 py-2 text-right font-medium">Latency</th>
            <th className="px-3 py-2 font-medium">When</th>
            <th className="px-3 py-2"></th>
          </tr>
        </thead>
        <tbody>
          {items.map((d) => (
            <tr
              key={d.decision_id}
              className="border-b border-[color:var(--color-border)] transition-colors hover:bg-[color:var(--color-bg-soft)]"
            >
              <td className="px-3 py-2.5">
                <ScoreBadge score={d.score} />
              </td>
              <td className="px-3 py-2.5">
                <VerdictPill label={d.verdict_label} />
              </td>
              <td className="px-3 py-2.5 font-mono text-xs text-[color:var(--color-fg-muted)]">
                {d.domain}
              </td>
              <td className="px-3 py-2.5 font-mono text-xs text-[color:var(--color-fg-muted)]">
                {d.baseline_entity_id ?? "—"}
              </td>
              <td className="px-3 py-2.5 font-mono text-xs text-[color:var(--color-fg-subtle)]">
                {truncId(d.event_id)}
              </td>
              <td className="px-3 py-2.5 font-mono text-[11px] text-[color:var(--color-fg-muted)]">
                {d.judge_provider}/{d.judge_model.replace(/^claude-/, "")}
              </td>
              <td className="px-3 py-2.5 text-right font-mono text-xs text-[color:var(--color-fg-muted)]">
                {d.latency_ms}ms
              </td>
              <td className="px-3 py-2.5 text-xs text-[color:var(--color-fg-subtle)]">
                {formatDistanceToNow(parseISO(d.created_at), { addSuffix: true })}
              </td>
              <td className="px-3 py-2.5 text-right">
                <Link
                  to={`/decisions/${d.decision_id}`}
                  className="inline-flex items-center gap-1 text-xs text-[color:var(--color-fg-muted)] hover:text-[color:var(--color-fg)]"
                >
                  open
                  <ArrowRight className="h-3 w-3" />
                </Link>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
