"use client";

import { useState, useTransition } from "react";

import { commitImport, previewImport } from "@/lib/actions/imports";
import { formatDate, formatSigned } from "@/lib/format";
import {
  IMPORT_ROLES,
  type Account,
  type ImportMapping,
  type ImportPreview,
  type ImportResult,
  type ImportRole,
} from "@/lib/types";
import { Button, Card, EmptyState, Field, Select } from "@/components/ui/form";

/** 4 MB matches the API's limit; caught here so a big file fails instantly. */
const MAX_BYTES = 4_000_000;

type Stage =
  | { name: "upload" }
  | { name: "review"; csv: string; filename: string; preview: ImportPreview }
  | { name: "done"; result: ImportResult };

function Notice({ tone, children }: { tone: "error" | "info"; children: React.ReactNode }) {
  const styles =
    tone === "error"
      ? "bg-red-50 text-red-700 dark:bg-red-950 dark:text-red-300"
      : "bg-amber-50 text-amber-800 dark:bg-amber-950 dark:text-amber-300";
  return (
    <p role="status" aria-live="polite" className={`rounded-md px-3 py-2 text-sm ${styles}`}>
      {children}
    </p>
  );
}

function Stat({ label, value }: { label: string; value: string }) {
  return (
    <div className="rounded-md border border-neutral-200 px-3 py-2 dark:border-neutral-800">
      <p className="text-xs text-neutral-500">{label}</p>
      <p className="text-sm font-medium">{value}</p>
    </div>
  );
}

export function ImportView({ accounts }: { accounts: Account[] }) {
  const [stage, setStage] = useState<Stage>({ name: "upload" });
  const [accountId, setAccountId] = useState(accounts[0]?.id ?? "");
  const [skipped, setSkipped] = useState<Set<number>>(new Set());
  const [error, setError] = useState<string | null>(null);
  const [busy, startTransition] = useTransition();

  function runPreview(csv: string, filename: string, mapping: ImportMapping | null) {
    setError(null);
    startTransition(async () => {
      const state = await previewImport({
        csv,
        filename,
        accountId: accountId || null,
        mapping,
      });
      if (!state.ok || !state.preview) {
        setError(state.message ?? "Could not read that file.");
        return;
      }
      setStage({ name: "review", csv, filename, preview: state.preview });
    });
  }

  async function onFile(file: File) {
    if (file.size > MAX_BYTES) {
      setError("That file is larger than 4 MB. Export a shorter date range.");
      return;
    }
    setSkipped(new Set());
    runPreview(await file.text(), file.name, null);
  }

  if (accounts.length === 0) {
    return (
      <EmptyState>
        Add an account first — imported transactions have to belong to one.
      </EmptyState>
    );
  }

  if (stage.name === "done") {
    const { result } = stage;
    return (
      <Card title="Import finished">
        <div className="grid grid-cols-2 gap-2 sm:grid-cols-4">
          <Stat label="Added" value={String(result.imported)} />
          <Stat label="Merged as duplicates" value={String(result.merged)} />
          <Stat label="Waiting for review" value={String(result.queuedForReview)} />
          <Stat label="Skipped or unreadable" value={String(result.skipped + result.failed)} />
        </div>
        <p className="mt-4 text-sm text-neutral-500">
          {result.merged > 0 &&
            "Rows that matched something already in your ledger were folded into it rather than added twice. "}
          {result.queuedForReview > 0 &&
            "Anything uncertain is waiting on the Review page. "}
          Nothing was deleted.
        </p>
        <div className="mt-4 flex gap-2">
          <Button onClick={() => setStage({ name: "upload" })}>Import another file</Button>
        </div>
      </Card>
    );
  }

  if (stage.name === "upload") {
    return (
      <Card title="Upload a statement">
        <div className="space-y-4">
          <Field
            label="Account"
            name="accountId"
            hint="Which account this statement belongs to."
          >
            <Select
              name="accountId"
              value={accountId}
              onChange={(e) => setAccountId(e.target.value)}
            >
              {accounts.map((account) => (
                <option key={account.id} value={account.id}>
                  {account.name}
                </option>
              ))}
            </Select>
          </Field>

          <Field
            label="CSV file"
            name="file"
            hint="Export from your bank's net banking as CSV. Nothing is saved until you confirm the next screen."
          >
            <input
              id="file"
              name="file"
              type="file"
              accept=".csv,text/csv"
              disabled={busy}
              onChange={(e) => {
                const file = e.target.files?.[0];
                // Reset so re-picking the same file fires onChange again.
                e.target.value = "";
                if (file) void onFile(file);
              }}
              className="block w-full text-sm file:mr-3 file:rounded-md file:border-0 file:bg-neutral-900 file:px-4 file:py-2 file:text-sm file:font-medium file:text-white dark:file:bg-neutral-100 dark:file:text-neutral-900"
            />
          </Field>

          {busy && <Notice tone="info">Reading the file…</Notice>}
          {error && <Notice tone="error">{error}</Notice>}
        </div>
      </Card>
    );
  }

  const { csv, filename, preview } = stage;
  const selected = accounts.find((a) => a.id === accountId);
  const currency = selected?.currency ?? "INR";

  function setRole(role: ImportRole, column: number) {
    const next: ImportMapping = { ...preview.mapping };
    next[role] = column;

    // A column can only play one part; taking it from another role keeps the
    // preview honest instead of reading the same cell as two things.
    for (const { key } of IMPORT_ROLES) {
      if (key !== role && next[key] === column && column >= 0) {
        next[key] = -1;
      }
    }
    runPreview(csv, filename, next);
  }

  function toggleRow(rowNumber: number) {
    const next = new Set(skipped);
    if (!next.delete(rowNumber)) next.add(rowNumber);
    setSkipped(next);
  }

  function confirm() {
    setError(null);
    startTransition(async () => {
      const state = await commitImport({
        csv,
        filename,
        accountId,
        mapping: preview.mapping,
        skipRows: [...skipped],
      });
      if (!state.ok || !state.result) {
        setError(state.message ?? "Could not import that file.");
        return;
      }
      setStage({ name: "done", result: state.result });
    });
  }

  const willImport = preview.rows.filter(
    (row) => !row.error && !skipped.has(row.rowNumber),
  ).length;

  return (
    <div className="space-y-4">
      <Card title={`Columns in ${filename}`}>
        <p className="mb-3 text-sm text-neutral-500">
          These were detected from the header row. Correct anything that looks
          wrong — a mistake here would file real money against the wrong dates.
        </p>
        <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
          {IMPORT_ROLES.map(({ key, label, required }) => (
            <Field key={key} label={label} name={key}>
              <Select
                name={key}
                value={String(preview.mapping[key])}
                disabled={busy}
                onChange={(e) => setRole(key, Number(e.target.value))}
              >
                <option value="-1">{required ? "— required —" : "— none —"}</option>
                {preview.headers.map((header, index) => (
                  <option key={index} value={index}>
                    {header || `Column ${index + 1}`}
                  </option>
                ))}
              </Select>
            </Field>
          ))}
        </div>
        <p className="mt-3 text-xs text-neutral-500">
          Dates are being read as{" "}
          <strong>{preview.mapping.dayFirst ? "day/month" : "month/day"}</strong>.
        </p>
      </Card>

      {!preview.usable ? (
        <Notice tone="error">{preview.problem}</Notice>
      ) : (
        <Card title="What will be imported">
          <div className="grid grid-cols-2 gap-2 sm:grid-cols-4">
            <Stat label="Rows in file" value={String(preview.totalRows)} />
            <Stat label="Readable" value={String(preview.validRows)} />
            <Stat label="Look like duplicates" value={String(preview.duplicateRows)} />
            <Stat label="Net" value={formatSigned(preview.netAmount, currency)} />
          </div>

          {preview.duplicateRows > 0 && (
            <div className="mt-3">
              <Notice tone="info">
                {preview.duplicateRows} row
                {preview.duplicateRows === 1 ? "" : "s"} already look like
                something in your ledger. They will be merged or sent to Review
                rather than added twice — untick any you want left out entirely.
              </Notice>
            </div>
          )}

          <div className="mt-4 overflow-x-auto">
            <table className="w-full text-left text-sm">
              <thead className="border-b border-neutral-200 text-xs uppercase tracking-wide text-neutral-500 dark:border-neutral-800">
                <tr>
                  <th className="py-2 pr-2">Import</th>
                  <th className="py-2 pr-2">Date</th>
                  <th className="py-2 pr-2">Description</th>
                  <th className="py-2 pr-2 text-right">Amount</th>
                  <th className="py-2 pl-2">Status</th>
                </tr>
              </thead>
              <tbody>
                {preview.rows.map((row) => {
                  const skip = skipped.has(row.rowNumber);
                  return (
                    <tr
                      key={row.rowNumber}
                      className={`border-b border-neutral-100 dark:border-neutral-900 ${
                        row.error || skip ? "opacity-50" : ""
                      }`}
                    >
                      <td className="py-2 pr-2">
                        <input
                          type="checkbox"
                          checked={!skip && !row.error}
                          disabled={!!row.error}
                          aria-label={`Import row ${row.rowNumber}`}
                          onChange={() => toggleRow(row.rowNumber)}
                        />
                      </td>
                      <td className="py-2 pr-2 whitespace-nowrap">
                        {row.occurredAt ? formatDate(row.occurredAt) : "—"}
                      </td>
                      <td className="py-2 pr-2">{row.description ?? "—"}</td>
                      <td className="py-2 pr-2 text-right font-mono whitespace-nowrap">
                        {row.amount === null
                          ? "—"
                          : formatSigned(
                              row.direction === "debit" ? -row.amount : row.amount,
                              currency,
                            )}
                      </td>
                      <td className="py-2 pl-2 text-xs">
                        {row.error ? (
                          <span className="text-red-600">{row.error}</span>
                        ) : row.duplicateAction === "merge" ? (
                          <span className="text-amber-700 dark:text-amber-400">
                            Duplicate — will merge
                          </span>
                        ) : row.duplicateAction === "review" ? (
                          <span className="text-amber-700 dark:text-amber-400">
                            Possible duplicate ({Math.round((row.duplicateScore ?? 0) * 100)}%)
                          </span>
                        ) : (
                          <span className="text-neutral-400">New</span>
                        )}
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>

          {error && <div className="mt-3"><Notice tone="error">{error}</Notice></div>}

          <div className="mt-4 flex flex-wrap items-center gap-2">
            <Button onClick={confirm} disabled={busy || willImport === 0}>
              {busy
                ? "Importing…"
                : `Import ${willImport} transaction${willImport === 1 ? "" : "s"}`}
            </Button>
            <Button
              variant="secondary"
              disabled={busy}
              onClick={() => setStage({ name: "upload" })}
            >
              Cancel
            </Button>
            <span className="text-xs text-neutral-500">
              Into {selected?.name ?? "the selected account"}
              {selected ? ` · amounts in ${selected.currency}` : ""}
            </span>
          </div>
        </Card>
      )}
    </div>
  );
}
