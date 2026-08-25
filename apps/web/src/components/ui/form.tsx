import type { ReactNode } from "react";

import type { FormState } from "@/lib/actions/form-state";

const inputClass =
  "w-full rounded-md border border-neutral-300 bg-white px-3 py-2 text-sm outline-none " +
  "transition focus:border-neutral-900 focus:ring-1 focus:ring-neutral-900 " +
  "dark:border-neutral-700 dark:bg-neutral-950 dark:focus:border-neutral-100 " +
  "dark:focus:ring-neutral-100";

const errorClass = "border-red-500 focus:border-red-500 focus:ring-red-500";

type FieldProps = {
  label: string;
  name: string;
  error?: string;
  hint?: string;
  children: ReactNode;
};

/** A labelled control with inline validation, wired up for screen readers. */
export function Field({ label, name, error, hint, children }: FieldProps) {
  return (
    <div className="space-y-1">
      <label htmlFor={name} className="block text-sm font-medium">
        {label}
      </label>
      {children}
      {hint && !error && (
        <p className="text-xs text-neutral-500">{hint}</p>
      )}
      {error && (
        <p id={`${name}-error`} className="text-xs text-red-600">
          {error}
        </p>
      )}
    </div>
  );
}

type InputProps = React.ComponentProps<"input"> & {
  error?: string;
};

export function Input({ error, className = "", ...props }: InputProps) {
  return (
    <input
      {...props}
      id={props.id ?? props.name}
      aria-invalid={error ? true : undefined}
      aria-describedby={error ? `${props.name}-error` : undefined}
      className={`${inputClass} ${error ? errorClass : ""} ${className}`}
    />
  );
}

type SelectProps = React.ComponentProps<"select"> & {
  error?: string;
};

export function Select({ error, className = "", ...props }: SelectProps) {
  return (
    <select
      {...props}
      id={props.id ?? props.name}
      aria-invalid={error ? true : undefined}
      aria-describedby={error ? `${props.name}-error` : undefined}
      className={`${inputClass} ${error ? errorClass : ""} ${className}`}
    />
  );
}

type TextareaProps = React.ComponentProps<"textarea"> & {
  error?: string;
};

export function Textarea({ error, className = "", ...props }: TextareaProps) {
  return (
    <textarea
      {...props}
      id={props.id ?? props.name}
      aria-invalid={error ? true : undefined}
      aria-describedby={error ? `${props.name}-error` : undefined}
      className={`${inputClass} ${error ? errorClass : ""} ${className}`}
    />
  );
}

/**
 * Form-level feedback.
 *
 * Announced politely so a screen reader hears the outcome without the focus
 * being yanked away from wherever the user is.
 */
export function FormMessage({ state }: { state: FormState }) {
  if (!state.message) return null;

  return (
    <p
      role="status"
      aria-live="polite"
      className={`rounded-md px-3 py-2 text-sm ${
        state.ok
          ? "bg-emerald-50 text-emerald-700 dark:bg-emerald-950 dark:text-emerald-300"
          : "bg-red-50 text-red-700 dark:bg-red-950 dark:text-red-300"
      }`}
    >
      {state.message}
    </p>
  );
}

/**
 * Props are taken from the element rather than its attribute list so that
 * `ref` is included. React 19 passes refs to function components as ordinary
 * props, and every control here forwards its props straight to the DOM node,
 * so a caller that needs to focus a control can simply ask for it.
 */
export function Button({
  variant = "primary",
  className = "",
  ...props
}: React.ComponentProps<"button"> & {
  variant?: "primary" | "secondary" | "danger";
}) {
  const styles = {
    primary:
      "bg-neutral-900 text-white hover:bg-neutral-700 dark:bg-neutral-100 dark:text-neutral-900 dark:hover:bg-neutral-300",
    secondary:
      "border border-neutral-300 hover:bg-neutral-100 dark:border-neutral-700 dark:hover:bg-neutral-900",
    danger:
      "border border-red-300 text-red-700 hover:bg-red-50 dark:border-red-900 dark:text-red-400 dark:hover:bg-red-950",
  }[variant];

  return (
    <button
      {...props}
      className={`rounded-md px-4 py-2 text-sm font-medium transition disabled:cursor-not-allowed disabled:opacity-60 ${styles} ${className}`}
    />
  );
}

export function Card({
  title,
  action,
  children,
}: {
  title?: string;
  action?: ReactNode;
  children: ReactNode;
}) {
  return (
    <section className="rounded-lg border border-neutral-200 bg-white p-5 dark:border-neutral-800 dark:bg-neutral-950">
      {(title || action) && (
        <header className="mb-4 flex items-center justify-between gap-4">
          {title && <h2 className="text-sm font-semibold">{title}</h2>}
          {action}
        </header>
      )}
      {children}
    </section>
  );
}

export function EmptyState({ children }: { children: ReactNode }) {
  return (
    <p className="rounded-md border border-dashed border-neutral-300 px-4 py-8 text-center text-sm text-neutral-500 dark:border-neutral-700">
      {children}
    </p>
  );
}
