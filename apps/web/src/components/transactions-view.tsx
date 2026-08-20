"use client";

import { useState } from "react";

import {
  DeleteTransactionButton,
  TransactionForm,
  TransferForm,
} from "@/components/transaction-form";
import { Button, Card, EmptyState } from "@/components/ui/form";
import { formatDate, formatSigned } from "@/lib/format";
import type {
  Account,
  Category,
  Transaction,
  TransactionPage,
} from "@/lib/types";

const SOURCE_LABELS: Record<Transaction["source"], string> = {
  manual: "Manual",
  auto: "From an alert",
  import: "Imported",
};

export function TransactionsView({
  page,
  accounts,
  categories,
}: {
  page: TransactionPage;
  accounts: Account[];
  categories: Category[];
}) {
  const [panel, setPanel] = useState<"none" | "transaction" | "transfer">("none");
  const [editingId, setEditingId] = useState<string | null>(null);

  return (
    <div className="space-y-4">
      {panel === "transaction" && (
        <Card title="New transaction">
          <TransactionForm
            accounts={accounts}
            categories={categories}
            onDone={() => setPanel("none")}
          />
        </Card>
      )}

      {panel === "transfer" && (
        <Card title="New transfer">
          <TransferForm accounts={accounts} onDone={() => setPanel("none")} />
        </Card>
      )}

      {panel === "none" && (
        <div className="flex gap-2">
          <Button onClick={() => setPanel("transaction")}>Add transaction</Button>
          <Button variant="secondary" onClick={() => setPanel("transfer")}>
            Record transfer
          </Button>
        </div>
      )}

      {page.items.length === 0 ? (
        <EmptyState>
          Nothing here yet. Add a transaction, or adjust the filters above.
        </EmptyState>
      ) : (
        <ul className="space-y-2">
          {page.items.map((transaction) => (
            <li key={transaction.id}>
              <Card>
                {editingId === transaction.id ? (
                  <TransactionForm
                    accounts={accounts}
                    categories={categories}
                    transaction={transaction}
                    onDone={() => setEditingId(null)}
                  />
                ) : (
                  <Row
                    transaction={transaction}
                    onEdit={() => setEditingId(transaction.id)}
                  />
                )}
              </Card>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}

function Row({
  transaction,
  onEdit,
}: {
  transaction: Transaction;
  onEdit: () => void;
}) {
  const isTransfer = transaction.kind === "transfer";
  const title =
    transaction.merchantName ??
    transaction.description ??
    (isTransfer ? "Transfer" : "Untitled");

  return (
    <div className="flex flex-wrap items-center justify-between gap-3">
      <div className="min-w-0">
        <p className="truncate font-medium">
          {title}
          {transaction.isExcluded && (
            <span className="ml-2 rounded bg-neutral-200 px-1.5 py-0.5 text-xs text-neutral-600 dark:bg-neutral-800 dark:text-neutral-400">
              Excluded
            </span>
          )}
        </p>
        <p className="mt-0.5 truncate text-sm text-neutral-500">
          {formatDate(transaction.occurredAt)}
          {transaction.accountName && ` · ${transaction.accountName}`}
          {isTransfer && transaction.counterpartAccountName && (
            <>
              {" "}
              {transaction.direction === "debit" ? "→" : "←"}{" "}
              {transaction.counterpartAccountName}
            </>
          )}
          {!isTransfer && transaction.categoryName && ` · ${transaction.categoryName}`}
          {transaction.source !== "manual" &&
            ` · ${SOURCE_LABELS[transaction.source]}`}
        </p>
        {transaction.tags.length > 0 && (
          <p className="mt-1 flex flex-wrap gap-1">
            {transaction.tags.map((tag) => (
              <span
                key={tag}
                className="rounded bg-neutral-100 px-1.5 py-0.5 text-xs text-neutral-600 dark:bg-neutral-800 dark:text-neutral-400"
              >
                {tag}
              </span>
            ))}
          </p>
        )}
      </div>

      <div className="flex items-center gap-3">
        <span
          className={`font-mono text-sm ${
            isTransfer
              ? "text-neutral-500"
              : transaction.signedAmount < 0
                ? "text-red-600 dark:text-red-400"
                : "text-emerald-600 dark:text-emerald-400"
          }`}
        >
          {formatSigned(transaction.signedAmount, transaction.currency)}
        </span>
        {!isTransfer && (
          <Button variant="secondary" onClick={onEdit}>
            Edit
          </Button>
        )}
        <DeleteTransactionButton transaction={transaction} />
      </div>
    </div>
  );
}
