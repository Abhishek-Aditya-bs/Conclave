import type { ContributingFactor } from "../lib/types";

interface Props {
  factors: ContributingFactor[];
}

/**
 * Vertically stacked weight bars. Bars extending right (positive weight)
 * nudge BLOCK; left (negative) nudge ALLOW. Same convention as
 * ContributingFactorRecord in the orchestrator domain model.
 */
export function ContributingFactors({ factors }: Props) {
  if (factors.length === 0) {
    return (
      <p className="text-xs text-[color:var(--color-fg-subtle)]">
        no factors recorded
      </p>
    );
  }
  return (
    <ul className="divide-y divide-[color:var(--color-border)]">
      {factors.map((f, i) => {
        const pct = Math.min(100, Math.abs(f.weight) * 100);
        const positive = f.weight >= 0;
        return (
          <li key={`${f.name}-${i}`} className="py-3">
            <div className="flex items-center justify-between gap-3">
              <span className="font-mono text-xs">{f.name}</span>
              <span className="font-mono text-xs text-[color:var(--color-fg-muted)]">
                {f.weight >= 0 ? "+" : ""}
                {f.weight.toFixed(3)}
              </span>
            </div>
            <div className="relative mt-1.5 h-1 w-full bg-[color:var(--color-bg-soft)]">
              <div
                className="absolute top-0 h-full"
                style={{
                  width: `${pct / 2}%`,
                  left: positive ? "50%" : `${50 - pct / 2}%`,
                  backgroundColor: positive
                    ? "var(--color-risk-high)"
                    : "var(--color-risk-low)",
                }}
              />
              <div
                aria-hidden
                className="absolute left-1/2 top-0 h-full w-px bg-[color:var(--color-border-strong)]"
              />
            </div>
            {f.evidence ? (
              <p className="mt-1.5 text-xs text-[color:var(--color-fg-muted)]">
                {f.evidence}
              </p>
            ) : null}
          </li>
        );
      })}
    </ul>
  );
}
