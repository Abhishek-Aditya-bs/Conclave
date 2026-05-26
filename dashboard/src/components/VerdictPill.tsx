import { verdictClasses } from "../lib/utils";
import type { VerdictLabel } from "../lib/types";

export function VerdictPill({ label }: { label: VerdictLabel }) {
  return (
    <span
      className={`inline-flex items-center rounded px-1.5 py-0.5 font-mono text-[10px] font-semibold ring-1 ring-inset ${verdictClasses(label)}`}
    >
      {label}
    </span>
  );
}
