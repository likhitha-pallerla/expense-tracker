"use client";

import { useState } from "react";

import { AccountForm, DeleteAccountButton } from "@/components/account-form";
import { Button, Card, EmptyState } from "@/components/ui/form";
import { formatMoney } from "@/lib/format";
import { ACCOUNT_TYPE_LABELS, type Account } from "@/lib/types";

export function AccountsView({
  accounts,
  showArchived,
}: {
  accounts: Account[];
  showArchived: boolean;
}) {
  const [adding, setAdding] = useState(false);
  const [editingId, setEditingId] = useState<string | null>(null);

  return (
    <div className="space-y-4">
      {adding ? (
        <Card title="New account">
          <AccountForm onDone={() => setAdding(false)} />
        </Card>
      ) : (
        <Button onClick={() => setAdding(true)}>Add account</Button>
      )}

      {accounts.length === 0 ? (
        <EmptyState>
          No accounts yet. Add the bank or wallet you spend from to get started.
        </EmptyState>
      ) : (
        <ul className="space-y-3">
          {accounts.map((account) => (
            <li key={account.id}>
              <Card>
                {editingId === account.id ? (
                  <AccountForm
                    account={account}
                    onDone={() => setEditingId(null)}
                  />
                ) : (
                  <div className="flex flex-wrap items-center justify-between gap-4">
                    <div>
                      <p className="font-medium">
                        {account.name}
                        {account.last4 && (
                          <span className="text-neutral-400">
                            {" "}
                            ••{account.last4}
                          </span>
                        )}
                        {account.isArchived && (
                          <span className="ml-2 rounded bg-neutral-200 px-1.5 py-0.5 text-xs text-neutral-600 dark:bg-neutral-800 dark:text-neutral-400">
                            Archived
                          </span>
                        )}
                      </p>
                      <p className="text-sm text-neutral-500">
                        {ACCOUNT_TYPE_LABELS[account.type] ?? account.type}
                      </p>
                    </div>

                    <div className="flex items-center gap-3">
                      <span
                        className={`font-mono text-sm ${
                          account.balance < 0
                            ? "text-red-600 dark:text-red-400"
                            : ""
                        }`}
                      >
                        {formatMoney(account.balance, account.currency)}
                      </span>
                      <Button
                        variant="secondary"
                        onClick={() => setEditingId(account.id)}
                      >
                        Edit
                      </Button>
                      <DeleteAccountButton account={account} />
                    </div>
                  </div>
                )}
              </Card>
            </li>
          ))}
        </ul>
      )}

      <p className="text-sm text-neutral-500">
        {showArchived ? (
          <a href="/accounts" className="underline underline-offset-4">
            Hide archived accounts
          </a>
        ) : (
          <a
            href="/accounts?includeArchived=true"
            className="underline underline-offset-4"
          >
            Show archived accounts
          </a>
        )}
      </p>
    </div>
  );
}
