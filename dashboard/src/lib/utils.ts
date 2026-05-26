import { clsx, type ClassValue } from "clsx";
import { twMerge } from "tailwind-merge";

export function cn(...inputs: ClassValue[]) {
  return twMerge(clsx(inputs));
}

/** Score → CSS color from the risk gradient. */
export function scoreColor(score: number): string {
  if (score >= 0.66) return "var(--color-risk-high)";
  if (score >= 0.33) return "var(--color-risk-med)";
  return "var(--color-risk-low)";
}

/** Verdict label → text + background classes. */
export function verdictClasses(label: string): string {
  switch (label) {
    case "BLOCK":
      return "bg-red-500/10 text-red-400 ring-red-500/20";
    case "REVIEW":
      return "bg-yellow-500/10 text-yellow-400 ring-yellow-500/20";
    case "ALLOW":
    default:
      return "bg-emerald-500/10 text-emerald-400 ring-emerald-500/20";
  }
}

/** Pretty-print enriched_event_json (which is itself a JSON string). */
export function prettyJson(input: string | object): string {
  try {
    const obj = typeof input === "string" ? JSON.parse(input) : input;
    return JSON.stringify(obj, null, 2);
  } catch {
    return typeof input === "string" ? input : JSON.stringify(input);
  }
}

/** Truncate an entity id for table display. */
export function truncId(id: string, head = 8, tail = 4): string {
  if (id.length <= head + tail + 1) return id;
  return `${id.slice(0, head)}…${id.slice(-tail)}`;
}
