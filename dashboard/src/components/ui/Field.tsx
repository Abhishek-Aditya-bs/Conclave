import type { InputHTMLAttributes, ReactNode, SelectHTMLAttributes } from "react";
import { cn } from "../../lib/utils";

const fieldBase =
  "h-8 rounded-md border border-[color:var(--color-border)] bg-[color:var(--color-bg-soft)] px-2 text-xs text-[color:var(--color-fg)] placeholder:text-[color:var(--color-fg-subtle)] focus:border-[color:var(--color-fg-subtle)] focus:outline-none";

export function Label({ children }: { children: ReactNode }) {
  return (
    <span className="mb-1 block text-[10px] font-medium uppercase tracking-wider text-[color:var(--color-fg-subtle)]">
      {children}
    </span>
  );
}

export function TextInput({ className, ...props }: InputHTMLAttributes<HTMLInputElement>) {
  return <input className={cn(fieldBase, "w-full", className)} {...props} />;
}

export function Select({
  className,
  children,
  ...props
}: SelectHTMLAttributes<HTMLSelectElement>) {
  return (
    <select className={cn(fieldBase, "w-full pr-6", className)} {...props}>
      {children}
    </select>
  );
}
