import type { InputHTMLAttributes, ReactNode, SelectHTMLAttributes } from "react";
import { cn } from "../../lib/utils";

const fieldBase =
  "h-8 rounded-md border border-border bg-secondary px-2 text-xs text-foreground placeholder:text-muted-foreground transition-colors focus:border-ring focus:outline-none focus-visible:ring-2 focus-visible:ring-ring/40";

export function Label({ children }: { children: ReactNode }) {
  return (
    <span className="mb-1 block text-[10px] font-medium uppercase tracking-wider text-muted-foreground">
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
