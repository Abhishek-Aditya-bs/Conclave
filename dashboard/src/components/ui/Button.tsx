import type { ButtonHTMLAttributes } from "react";
import { cn } from "../../lib/utils";

type Variant = "primary" | "secondary" | "ghost";
type Size = "sm" | "md";

const variants: Record<Variant, string> = {
  primary:
    "bg-[color:var(--color-fg)] text-[color:var(--color-bg)] hover:bg-[color:var(--color-fg-muted)]",
  secondary:
    "border border-[color:var(--color-border-strong)] bg-[color:var(--color-bg-soft)] hover:bg-[color:var(--color-border)]",
  ghost:
    "text-[color:var(--color-fg-muted)] hover:text-[color:var(--color-fg)] hover:bg-[color:var(--color-bg-soft)]",
};

const sizes: Record<Size, string> = {
  sm: "h-7 px-2.5 text-xs",
  md: "h-9 px-3.5 text-sm",
};

interface ButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: Variant;
  size?: Size;
}

export function Button({
  variant = "secondary",
  size = "md",
  className,
  ...props
}: ButtonProps) {
  return (
    <button
      className={cn(
        "inline-flex items-center justify-center gap-1.5 rounded-md font-medium transition-colors disabled:cursor-not-allowed disabled:opacity-50 focus:outline-none focus:ring-2 focus:ring-[color:var(--color-fg)]/30",
        variants[variant],
        sizes[size],
        className
      )}
      {...props}
    />
  );
}
