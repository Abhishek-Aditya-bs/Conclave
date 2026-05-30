import type { ButtonHTMLAttributes } from "react";
import { cn } from "../../lib/utils";

type Variant = "primary" | "secondary" | "ghost";
type Size = "sm" | "md";

const variants: Record<Variant, string> = {
  primary:
    "bg-primary text-primary-foreground hover:bg-primary/90",
  secondary:
    "border border-border bg-card hover:bg-muted hover:text-foreground",
  ghost:
    "text-muted-foreground hover:bg-muted hover:text-foreground",
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
        "inline-flex items-center justify-center gap-1.5 rounded-lg font-medium transition-all select-none disabled:cursor-not-allowed disabled:opacity-50 focus:outline-none focus-visible:ring-2 focus-visible:ring-ring/50",
        variants[variant],
        sizes[size],
        className
      )}
      {...props}
    />
  );
}
