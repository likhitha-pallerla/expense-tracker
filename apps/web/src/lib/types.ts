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

export type Category = {
  id: string;
  parent_id: string | null;
  name: string;
  icon: string | null;
  is_system: boolean;
  sort_order: number;
};

export const BUDGET_PERIODS = [
  { value: "weekly", label: "Weekly" },
  { value: "monthly", label: "Monthly" },
  { value: "yearly", label: "Yearly" },
] as const;

export type BudgetPeriod = (typeof BUDGET_PERIODS)[number]["value"];

export type BudgetStatus = "on_track" | "warning" | "over" | "upcoming" | "ended";

export type CardStatus =
  | "clear"
  | "tracking"
  | "due"
  | "minimum_met"
  | "paid"
  | "overdue";

export type Card = {
  accountId: string;
  name: string;
  last4: string | null;
  currency: string;
  isArchived: boolean;

  creditLimit: number | null;
  outstanding: number;
  available: number | null;
  utilisation: number | null;

  billingDay: number | null;
  dueDay: number | null;
  statementDate: string | null;
  dueDate: string | null;
  nextStatement: string | null;
  daysUntilDue: number | null;

  statementBalance: number | null;
  minimumDue: number | null;
  lastStatementAt: string | null;

  currentSpend: number | null;
  paidSinceStatement: number | null;
  remainingDue: number | null;
  minimumRemaining: number | null;
  status: CardStatus;
};

export const CADENCES = [
  { value: "weekly", label: "Weekly" },
  { value: "fortnightly", label: "Fortnightly" },
  { value: "monthly", label: "Monthly" },
  { value: "quarterly", label: "Quarterly" },
  { value: "half_yearly", label: "Every six months" },
  { value: "yearly", label: "Yearly" },
] as const;

export type Cadence = (typeof CADENCES)[number]["value"];

/** What the user has decided about a series. */
export type RecurringState = "suggested" | "confirmed" | "dismissed";

/** What the money is doing, which is a different question. */
export type RecurringStatus =
  | "active"
  | "due_today"
  | "due_soon"
  | "overdue"
  | "ended"
  | "paused"
  | "dismissed";

export type Recurring = {
  /** Null while it is only a suggestion — nothing has been saved to address. */
  id: string | null;
  matchKey: string;
  name: string;
  state: RecurringState;
  status: RecurringStatus;
  direction: "debit" | "credit";
  categoryId: string | null;
  categoryName: string | null;
  accountId: string | null;
  accountName: string | null;
  currency: string;
  cadence: Cadence;
  cadenceDays: number;

  typicalAmount: number;
  latestAmount: number;
  amountVaries: boolean;
  priceChanged: boolean;

  occurrences: number;
  firstCharge: string | null;
  lastCharge: string | null;
  nextExpected: string | null;
  daysUntilNext: number | null;

  monthlyCost: number;
  yearlyCost: number;
  isSubscription: boolean;
  isActive: boolean;
  confidence: number;
  reasons: string[];
  notes: string | null;
};

export type Budget = {
  id: string;
  name: string | null;
  categoryId: string | null;
  categoryName: string | null;
  amount: number;
  currency: string;
  period: BudgetPeriod;
  startsOn: string;
  endsOn: string | null;
  rollover: boolean;
  alertThresholds: number[];
  isActive: boolean;

  periodStart: string;
  periodEnd: string;
  daysRemaining: number;
  daysTotal: number;

  spent: number;
  carriedOver: number;
  limit: number;
  remaining: number;
  percentUsed: number;
  projected: number;
  status: BudgetStatus;
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

export type HealthBand = "strong" | "good" | "fair" | "weak" | "poor" | "unknown";

export type HealthGrade =
  | "strong"
  | "good"
  | "fair"
  | "needs_work"
  | "at_risk"
  | "unrated";

/**
 * One driver of the health score. `score` is null when there was nothing to
 * measure it from — which is not the same as scoring zero, and is rendered
 * differently.
 */
export type HealthSignal = {
  key: string;
  label: string;
  score: number | null;
  weight: number;
  value: number | null;
  unit: "percent" | "months" | "count";
  band: HealthBand;
  finding: string;
  action: string;
};

export type HealthReport = {
  score: number | null;
  grade: HealthGrade;
  headline: string;
  coverage: number;
  monthsObserved: number;
  windowStart: string;
  windowEnd: string;
  currency: string;
  signals: HealthSignal[];
  priorities: string[];
  wins: string[];
  missing: string[];
};

export type MailConnection = {
  id: string;
  provider: "gmail" | "outlook";
  label: string | null;
  address: string | null;
  status: "active" | "needs_reauth" | "paused" | "error" | "revoked";
  statusDetail: string;
  connectedAt: string | null;
  lastSyncedAt: string | null;
  lastError: string | null;
  needsReauth: boolean;
};

/**
 * A provider we support, whether this deployment can actually offer it, and
 * what the user has connected through it.
 */
export type MailProviderOption = {
  provider: "gmail" | "outlook";
  label: string;
  configured: boolean;
  connections: MailConnection[];
};

export type SyncRun = {
  id: string;
  connectionId: string;
  provider: "gmail" | "outlook";
  startedAt: string;
  finishedAt: string | null;
  status: "running" | "ok" | "failed";
  fetched: number;
  stored: number;
  skipped: number;
  hasMore: boolean;
  error: string | null;
  /** Built by the API, because the counts only mean something together. */
  summary: string;
};

/** What one pass over the waiting alerts did. */
export type ParseResult = {
  read: number;
  imported: number;
  merged: number;
  ignored: number;
  failed: number;
  /** Built by the API; the counts read as nonsense apart. */
  summary: string;
};

export type ParseQueue = {
  pending: number;
  failed: number;
  parsed: number;
  hasWork: boolean;
};

/**
 * An alert we stored but could not turn into a transaction.
 *
 * The snippet travels with the reason because "could not find the amount" only
 * makes sense next to the message it came from.
 */
export type UnreadMessage = {
  id: string;
  subject: string | null;
  sender: string | null;
  receivedAt: string | null;
  ruleName: string | null;
  reason: string | null;
  snippet: string | null;
};

export type NotificationType =
  | "budget_threshold"
  | "card_due"
  | "duplicates_pending"
  | "price_changed"
  | "recurring_overdue";

export type NotificationSeverity = "urgent" | "warning" | "info";

/**
 * Derived on every read, so there is no id — the key is the identity. Only
 * `read` and `dismissed` come from anything the user has stored.
 */
export type Notification = {
  key: string;
  type: NotificationType;
  severity: NotificationSeverity;
  title: string;
  body: string;
  href: string;
  occurredOn: string | null;
  read: boolean;
  dismissed: boolean;
  readAt: string | null;
  dismissedAt: string | null;
};

/**
 * A month's spending in one shape.
 *
 * Deliberately one response rather than several: totals and the category
 * breakdown built from separate calls could disagree, and the first thing
 * anyone checks on a dashboard is whether the parts add up.
 */
export type Insights = {
  month: string;
  label: string;
  currency: string;
  /** True while the month is still running, so figures are partial. */
  partial: boolean;
  daysElapsed: number;
  daysInMonth: number;
  /** How many days of the previous month the comparison covers. */
  previousDaysCounted: number;
  totals: Totals;
  previous: Totals;
  incomeChange: number | null;
  expenseChange: number | null;
  /** Where the month looks likely to end, or null when it is too early to say. */
  projectedExpense: number | null;
  uncategorisedAmount: number;
  categories: CategorySlice[];
  movers: CategorySlice[];
  merchants: MerchantSlice[];
  trend: TrendPoint[];
  /** The month holds more than one currency, so the totals are approximate. */
  mixedCurrencies: boolean;
  /** Whether this user has ever recorded anything, not just in this month. */
  hasHistory: boolean;
  earliestMonth: string | null;
  /** The month it is now where the user is — the browser's clock may differ. */
  currentMonth: string;
};

export type Totals = {
  income: number;
  expense: number;
  net: number;
  count: number;
  isEmpty: boolean;
};

export type CategorySlice = {
  categoryId: string | null;
  name: string;
  colour: string | null;
  amount: number;
  previousAmount: number;
  delta: number;
  /** Null when the previous amount was zero — no honest percentage exists. */
  percentChange: number | null;
  share: number;
  count: number;
  isUncategorised: boolean;
};

export type MerchantSlice = {
  merchantId: string | null;
  name: string;
  amount: number;
  count: number;
};

export type TrendPoint = {
  month: string;
  label: string;
  income: number;
  expense: number;
  net: number;
};
/** What the next few weeks look like, built only on known recurring series. */
export type Forecast = {
  today: string;
  end: string;
  days: number;
  currency: string;
  balanceToday: number;
  expectedIn: number;
  expectedOut: number;
  projectedBalance: number;
  /** What can be spent today without the balance ever dipping below zero. */
  safeToSpend: number;
  low: LowPoint;
  line: ForecastDay[];
  upcoming: ExpectedCharge[];
  /** Series we think are recurring; listed but kept out of every total. */
  suspected: ExpectedCharge[];
  /** Average daily spending no series accounts for. Reported, not projected. */
  unpredicted: number;
  basedOn: number;
  mixedCurrencies: boolean;
  hasAccounts: boolean;
};

/** The worst day in the window — the reason a forecast is worth having. */
export type LowPoint = {
  date: string;
  daysAway: number;
  balance: number;
  shortfall: number;
  goesNegative: boolean;
  isAhead: boolean;
};

export type ForecastDay = {
  date: string;
  balance: number;
  moneyIn: number;
  moneyOut: number;
  events: number;
  hasEvents: boolean;
};

export type ExpectedCharge = {
  seriesId: string;
  name: string;
  expectedOn: string;
  daysAway: number;
  amount: number;
  direction: string;
  currency: string;
  categoryId: string | null;
  categoryName: string | null;
  cadence: string;
  confirmed: boolean;
  /** Expected before today and not yet arrived — rolled forward, not dropped. */
  overdue: boolean;
  amountVaries: boolean;
  isIncome: boolean;
};