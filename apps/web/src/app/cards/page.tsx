import { AppShell } from "@/components/app-shell";
import { CardsView } from "@/components/cards-view";
import { EmptyState } from "@/components/ui/form";
import { apiFetch } from "@/lib/api";
import { formatMoney } from "@/lib/format";
import type { Card, Profile } from "@/lib/types";

export const metadata = { title: "Cards" };

export default async function CardsPage() {
  let cards: Card[] = [];
  let profile: Profile | null = null;
  let error: string | null = null;

  try {
    [cards, profile] = await Promise.all([
      apiFetch<Card[]>("/api/cards"),
      apiFetch<Profile>("/api/me"),
    ]);
  } catch (err) {
    error = (err as Error).message;
  }

  const currency = profile?.baseCurrency ?? "INR";
  const owed = cards.reduce((sum, card) => sum + card.outstanding, 0);

  return (
    <AppShell
      title="Credit cards"
      description="What you owe, how much of each limit is in use, and when payment is due."
      action={
        cards.length > 0 ? (
          <div className="text-right">
            <p className="text-xs uppercase tracking-wide text-neutral-500">
              Total owed
            </p>
            <p className="font-mono text-xl">{formatMoney(owed, currency)}</p>
          </div>
        ) : undefined
      }
    >
      {error ? (
        <EmptyState>Could not load cards: {error}</EmptyState>
      ) : (
        <CardsView cards={cards} />
      )}
    </AppShell>
  );
}
