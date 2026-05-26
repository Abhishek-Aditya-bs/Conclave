interface Props {
  text: string;
}

/**
 * Tiny markdown subset renderer — enough for the judge's verdict, which uses:
 *   - **bold**
 *   - bullet lists (`- item` / `* item`)
 *   - paragraphs (blank line)
 *   - inline `code`
 *
 * No HTML escape concerns because the source is from our own judge node, not
 * user input. Even so, we never inject raw HTML — only typed React nodes.
 */
function inline(text: string): (string | React.ReactElement)[] {
  const out: (string | React.ReactElement)[] = [];
  let i = 0;
  let keyIdx = 0;
  while (i < text.length) {
    if (text.startsWith("**", i)) {
      const end = text.indexOf("**", i + 2);
      if (end !== -1) {
        out.push(<strong key={`b-${keyIdx++}`}>{text.slice(i + 2, end)}</strong>);
        i = end + 2;
        continue;
      }
    }
    if (text[i] === "`") {
      const end = text.indexOf("`", i + 1);
      if (end !== -1) {
        out.push(
          <code
            key={`c-${keyIdx++}`}
            className="rounded bg-[color:var(--color-bg-soft)] px-1 py-0.5 font-mono text-[11px]"
          >
            {text.slice(i + 1, end)}
          </code>
        );
        i = end + 1;
        continue;
      }
    }
    // Run of plain text until next special char.
    let j = i;
    while (j < text.length && text[j] !== "*" && text[j] !== "`") j++;
    if (j === i) {
      out.push(text[i]);
      i++;
    } else {
      out.push(text.slice(i, j));
      i = j;
    }
  }
  return out;
}

export function VerdictMarkdown({ text }: Props) {
  if (!text?.trim()) {
    return (
      <p className="text-xs text-[color:var(--color-fg-subtle)]">
        no verdict explanation
      </p>
    );
  }
  const blocks: React.ReactElement[] = [];
  const paragraphs = text.split(/\n{2,}/);
  paragraphs.forEach((para, pi) => {
    const lines = para.split("\n").map((l) => l.trim()).filter(Boolean);
    const isList = lines.every((l) => l.startsWith("- ") || l.startsWith("* "));
    if (isList && lines.length > 0) {
      blocks.push(
        <ul key={pi} className="list-disc space-y-1 pl-5 text-sm leading-relaxed">
          {lines.map((l, li) => (
            <li key={li}>{inline(l.replace(/^[-*]\s+/, ""))}</li>
          ))}
        </ul>
      );
    } else {
      blocks.push(
        <p key={pi} className="text-sm leading-relaxed">
          {inline(lines.join(" "))}
        </p>
      );
    }
  });
  return <div className="space-y-3">{blocks}</div>;
}
