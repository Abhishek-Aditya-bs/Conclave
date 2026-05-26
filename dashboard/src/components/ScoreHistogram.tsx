import {
  Bar,
  BarChart,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from "recharts";
import type { DecisionSummary } from "../lib/types";

interface Props {
  decisions: DecisionSummary[];
}

const BUCKETS = 10;

/** 10-bucket histogram of scores in the current page. Pure client-side. */
export function ScoreHistogram({ decisions }: Props) {
  const buckets = Array.from({ length: BUCKETS }, (_, i) => ({
    range: `${(i / BUCKETS).toFixed(1)}`,
    count: 0,
  }));
  for (const d of decisions) {
    const idx = Math.min(BUCKETS - 1, Math.floor(d.score * BUCKETS));
    buckets[idx].count += 1;
  }
  return (
    <div className="h-32 w-full">
      <ResponsiveContainer width="100%" height="100%">
        <BarChart data={buckets} margin={{ top: 4, right: 8, bottom: 0, left: -24 }}>
          <XAxis
            dataKey="range"
            stroke="var(--color-fg-subtle)"
            tick={{ fontSize: 10, fontFamily: "JetBrains Mono" }}
            axisLine={{ stroke: "var(--color-border)" }}
            tickLine={false}
          />
          <YAxis
            stroke="var(--color-fg-subtle)"
            tick={{ fontSize: 10, fontFamily: "JetBrains Mono" }}
            axisLine={false}
            tickLine={false}
            width={32}
          />
          <Tooltip
            cursor={{ fill: "var(--color-bg-soft)" }}
            contentStyle={{
              background: "var(--color-bg-card)",
              border: "1px solid var(--color-border-strong)",
              borderRadius: 6,
              fontSize: 11,
              fontFamily: "JetBrains Mono",
            }}
            labelFormatter={(v) => `score ≥ ${v}`}
          />
          <Bar dataKey="count" fill="var(--color-fg)" radius={[2, 2, 0, 0]} />
        </BarChart>
      </ResponsiveContainer>
    </div>
  );
}
