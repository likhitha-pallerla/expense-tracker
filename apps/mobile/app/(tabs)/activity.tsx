import { ActivityIndicator, RefreshControl, SectionList, StyleSheet, Text, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';

import type { Transaction, TransactionPage } from '../../src/lib/types.ts';
import { useApi } from '../../src/lib/use-api.ts';
import { Empty, Notice } from '../../src/ui/components.tsx';
import { money, space, theme, type } from '../../src/ui/theme.ts';

/**
 * The last few weeks of spending, newest first.
 *
 * A `SectionList` grouped by day rather than a flat list. Bank alerts arrive in
 * bursts and a continuous column of amounts is very hard to read; the day
 * headers are what make it scannable, and the daily subtotal answers the
 * question people actually ask of this screen — "what did I get through
 * yesterday?"
 */
export default function ActivityScreen() {
  const page = useApi<TransactionPage>('/api/transactions?limit=100');

  if (page.loading) {
    return (
      <SafeAreaView style={styles.centre}>
        <ActivityIndicator />
      </SafeAreaView>
    );
  }

  const sections = groupByDay(page.data?.items ?? []);

  return (
    <SafeAreaView style={styles.screen} edges={['top']}>
      <View style={styles.header}>
        <Text style={type.title}>Activity</Text>
        {page.data ? (
          <Text style={type.small}>
            {page.data.total.toLocaleString()} recorded
            {page.data.total > page.data.items.length
              ? ` · showing the most recent ${page.data.items.length}`
              : ''}
          </Text>
        ) : null}
      </View>

      {page.error ? (
        <View style={styles.padded}>
          <Notice text={page.error} />
        </View>
      ) : null}

      <SectionList
        sections={sections}
        keyExtractor={(item) => item.id}
        contentContainerStyle={styles.list}
        refreshControl={<RefreshControl refreshing={page.refreshing} onRefresh={page.refresh} />}
        ListEmptyComponent={
          <Empty
            title="Nothing here yet"
            detail="Payments appear as soon as your bank alerts are read."
          />
        }
        renderSectionHeader={({ section }) => (
          <View style={styles.dayHeader}>
            <Text style={styles.dayLabel}>{section.title}</Text>
            <Text style={styles.dayTotal}>{money(section.spent, section.currency)}</Text>
          </View>
        )}
        renderItem={({ item }) => (
          <View style={styles.row}>
            <View style={styles.rowText}>
              <Text style={type.body} numberOfLines={1}>
                {item.merchantName ?? item.description ?? 'Payment'}
              </Text>
              <Text style={type.tiny} numberOfLines={1}>
                {[item.categoryName ?? 'Uncategorised', item.accountName].filter(Boolean).join(' · ')}
              </Text>
            </View>
            <Text
              style={[
                type.body,
                styles.amount,
                { color: item.direction === 'credit' ? theme.positive : theme.text },
              ]}
            >
              {item.direction === 'credit' ? '+' : ''}
              {money(item.amount, item.currency)}
            </Text>
          </View>
        )}
      />
    </SafeAreaView>
  );
}

type Day = { title: string; currency: string; spent: number; data: Transaction[] };

/**
 * Groups into days, keeping the order the API returned.
 *
 * The subtotal counts money out only. Netting a salary against a day's lunches
 * would produce a cheerful positive number on the one day of the month when
 * spending is least interesting.
 */
function groupByDay(items: Transaction[]): Day[] {
  const days: Day[] = [];
  let current: Day | null = null;

  for (const item of items) {
    const title = dayLabel(item.occurredAt);
    if (!current || current.title !== title) {
      current = { title, currency: item.currency, spent: 0, data: [] };
      days.push(current);
    }
    if (item.direction === 'debit') {
      current.spent += item.amount;
    }
    current.data.push(item);
  }

  return days;
}

function dayLabel(iso: string): string {
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return 'Unknown date';

  const today = new Date();
  const midnight = (d: Date) => new Date(d.getFullYear(), d.getMonth(), d.getDate()).getTime();
  const daysApart = Math.round((midnight(today) - midnight(date)) / 86_400_000);

  if (daysApart === 0) return 'Today';
  if (daysApart === 1) return 'Yesterday';
  if (daysApart < 7 && daysApart > 0) {
    return date.toLocaleDateString(undefined, { weekday: 'long' });
  }
  return date.toLocaleDateString(undefined, { day: 'numeric', month: 'long' });
}

const styles = StyleSheet.create({
  screen: { flex: 1, backgroundColor: theme.background },
  centre: { flex: 1, alignItems: 'center', justifyContent: 'center', backgroundColor: theme.surface },
  header: { padding: space.lg, gap: space.xs },
  padded: { paddingHorizontal: space.lg, paddingBottom: space.sm },
  list: { paddingBottom: space.xxl },
  dayHeader: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    backgroundColor: theme.surface,
    paddingHorizontal: space.lg,
    paddingVertical: space.sm,
  },
  dayLabel: { fontSize: 13, fontWeight: '600', color: theme.muted },
  dayTotal: { fontSize: 13, fontWeight: '600', color: theme.muted },
  row: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: space.md,
    paddingHorizontal: space.lg,
    paddingVertical: space.md,
    borderBottomWidth: StyleSheet.hairlineWidth,
    borderBottomColor: theme.border,
  },
  rowText: { flex: 1, gap: 2 },
  amount: { fontWeight: '600' },
});
