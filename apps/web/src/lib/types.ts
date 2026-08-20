/** Mirrors the API's response records. Kept in one place so a schema change
 * surfaces as a type error everywhere it matters. */

export type Account = {
  id: string;
  name: string;
  type: AccountType;
  currency: string;
  last4: string | null;
  openingBalance: number;
  balance: number;
  isArchived: boolean;
  sortOrder: number;
  createdAt: string;
};

export const ACCOUNT_TYPES = [
  "bank",
  "cash",
  "upi",
  "wallet",
  "credit_card",
  "other",
] as const;

export type AccountType = (typeof ACCOUNT_TYPES)[number];

export const ACCOUNT_TYPE_LABELS: Record<AccountType, string> = {
  bank: "Bank",
  cash: "Cash",
  upi: "UPI",
  wallet: "Wallet",
  credit_card: "Credit card",
  other: "Other",
};

export type TransactionKind = "expense" | "income" | "transfer";
export type TransactionDirection = "debit" | "credit";

export type Transaction = {
  id: string;
  kind: TransactionKind;
  direction: TransactionDirection;
  amount: number;
  signedAmount: number;
  currency: string;
  occurredAt: string;
  description: string | null;
  notes: string | null;
  tags: string[];

  accountId: string | null;
  accountName: string | null;
  categoryId: string | null;
  categoryName: string | null;
  merchantId: string | null;
  merchantName: string | null;

  transferId: string | null;
  counterpartAccountId: string | null;
  counterpartAccountName: string | null;

  externalRef: string | null;
  mergedIntoId: string | null;
  source: "manual" | "auto" | "import";
  isExcluded: boolean;
  isRecurring: boolean;
  createdAt: string;
};

export type TransactionPage = {
  items: Transaction[];
  total: number;
  netAmount: number;
  limit: number;
  offset: number;
};

/** One side of a suspected duplicate, as shown in the review queue. */
export type DuplicateSide = {
  id: string;
  amount: number;
  currency: string;
  occurredAt: string;
  description: string | null;
  merchantName: string | null;
  accountName: string | null;
};

export type DuplicatePair = {
  id: string;
  score: number;
  /** Raw JSON from the engine, explaining which rules matched. */
  signals: string;
  a: DuplicateSide;
  b: DuplicateSide;
};

/**
 * Which CSV column holds what. Zero-based; -1 means "not present".
 * Mirrors the API's ImportMapping record exactly — it is round-tripped.
 */
export type ImportMapping = {
  dateColumn: number;
  descriptionColumn: number;
  amountColumn: number;
  debitColumn: number;
  creditColumn: number;
  referenceColumn: number;
  typeColumn: number;
  dayFirst: boolean;
};

/** The roles a column can be assigned, in the order they are shown. */
export const IMPORT_ROLES = [
  { key: "dateColumn", label: "Date", required: true },
  { key: "descriptionColumn", label: "Description", required: false },
  { key: "amountColumn", label: "Amount (signed)", required: false },
  { key: "debitColumn", label: "Money out", required: false },
  { key: "creditColumn", label: "Money in", required: false },
  { key: "typeColumn", label: "Dr/Cr indicator", required: false },
  { key: "referenceColumn", label: "Reference", required: false },
] as const;

export type ImportRole = (typeof IMPORT_ROLES)[number]["key"];

export type ImportPreviewRow = {
  rowNumber: number;
  occurredAt: string | null;
  description: string | null;
  amount: number | null;
  direction: TransactionDirection | null;
  reference: string | null;
  error: string | null;
  /** "merge", "review", or null when this row looks new. */
  duplicateAction: string | null;
  duplicateScore: number | null;
  duplicateOf: string | null;
};

export type ImportPreview = {
  mapping: ImportMapping;
  headers: string[];
  rows: ImportPreviewRow[];
  usable: boolean;
  problem: string | null;
  totalRows: number;
  validRows: number;
  duplicateRows: number;
  netAmount: number;
};

export type ImportResult = {
  batchId: string;
  imported: number;
  merged: number;
  queuedForReview: number;
  skipped: number;
  failed: number;
};

export type Category = {  id: string;
  parent_id: string | null;
  name: string;
  icon: string | null;
  is_system: boolean;
  sort_order: number;
};

export type Profile = {
  userId: string;
  email: string | null;
  displayName: string | null;
  baseCurrency: string;
  timezone: string;
  locale: string;
  onboardedAt: string | null;
  newlyProvisioned: boolean;
};
