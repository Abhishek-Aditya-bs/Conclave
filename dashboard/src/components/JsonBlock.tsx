import { useMemo, useState } from "react";
import { Copy, Check } from "lucide-react";
import { prettyJson } from "../lib/utils";

interface Props {
  payload: string | object;
}

/** Read-only JSON viewer with a copy-to-clipboard affordance. */
export function JsonBlock({ payload }: Props) {
  const text = useMemo(() => prettyJson(payload), [payload]);
  const [copied, setCopied] = useState(false);

  const handleCopy = () => {
    void navigator.clipboard.writeText(text).then(() => {
      setCopied(true);
      setTimeout(() => setCopied(false), 1500);
    });
  };

  return (
    <div className="relative">
      <button
        type="button"
        onClick={handleCopy}
        className="absolute right-2 top-2 inline-flex items-center gap-1 rounded border border-[color:var(--color-border)] bg-[color:var(--color-bg-card)] px-1.5 py-1 text-[10px] text-[color:var(--color-fg-muted)] hover:text-[color:var(--color-fg)]"
        aria-label="Copy JSON"
      >
        {copied ? <Check className="h-3 w-3" /> : <Copy className="h-3 w-3" />}
        {copied ? "copied" : "copy"}
      </button>
      <pre className="max-h-96 overflow-auto rounded-md border border-[color:var(--color-border)] bg-[color:var(--color-bg-soft)] p-3 font-mono text-xs leading-relaxed text-[color:var(--color-fg-muted)]">
        {text}
      </pre>
    </div>
  );
}
