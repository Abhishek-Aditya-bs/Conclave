import { scoreColor } from "../lib/utils";

interface Props {
  score: number;
  /** When true, render a wider pill with the numeric value. */
  showValue?: boolean;
}

/**
 * Single visual encoding of risk score: dot + (optional) numeric. Color comes
 * from the risk gradient defined in index.css; nothing else in the dashboard
 * uses accent color so the eye snaps to the dot.
 */
export function ScoreBadge({ score, showValue = true }: Props) {
  const color = scoreColor(score);
  return (
    <span className="inline-flex items-center gap-1.5 font-mono text-xs">
      <span
        aria-hidden
        className="inline-block h-2 w-2 rounded-full"
        style={{ backgroundColor: color }}
      />
      {showValue ? score.toFixed(3) : null}
    </span>
  );
}
