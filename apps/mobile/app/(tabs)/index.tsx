import { useEffect } from 'react';
import { ActivityIndicator, RefreshControl, ScrollView, StyleSheet, Text, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';

import { api } from '../../src/lib/api.ts';
import type { Insights } from '../../src/lib/types.ts';
import { useApi } from '../../src/lib/use-api.ts';
import { Card, Chip, Empty, Notice } from '../../src/ui/components.tsx';
import { money, radius, space, theme, type } from '../../src/ui/theme.ts';

/**
 * What you have spent this month.
 *
 * Every figure here is computed by the API, including the comparison against
 * last month and the projection. That is not laziness — it is the reason the
 * phone and the web agree. Two clients each doing their own month arithmetic
 * would disagree the first time one of them handled a partial month or a
 * timezone differently, and the user would have no way to tell which was lying.
 */
export default function ThisMonthScreen() {
  const insights = useApi<Insights>('/api/insights');

  useEffect(() => {
    // Provisions the profile, default categories and a Cash account on a first
    // run. Harmless afterwards, and failing it silently is fine: every screen
    // that needs those will call it again.
    void api.get('/api/me').catch(() => {});
  }, []);

  if (insights.loading) {
    return (
      <SafeAreaView style={styles.centre}>
        <ActivityIndicator />
      </SafeAreaView>
    );
  }

  const data = insights.data;

  return (
    <SafeAreaView style={styles.screen} edges={['top']}>
      <ScrollView
        contentContainerStyle={styles.content}
        refreshControl={
          <RefreshControl refreshing={insights.refreshing} onRefresh={insights.refresh} />
        }
      >
        <View>
          <Text style={type.title}>{data?.label ?? 'This month'}</Text>
          {data?.partial ? (
            <Text style={type.small}>
              {data.daysElapsed} of {data.daysInMonth} days so far
            </Text>
          ) : null}
        </View>

        {insights.error ? <Notice text={insights.error} /> : null}

        {!data ? null : data.totals.isEmpty ? (
          <Empty
            title="Nothing recorded yet"
            detail="Connect your email on the web, turn on message reading in the Messages tab, or add something you paid for by hand."
          />
        ) : (
          <>
            <Card>
              <Text style={type.small}>Spent</Text>
              <Text style={type.figure}>{money(data.totals.expense, data.currency)}</Text>

              <View style={styles.chips}>
                {data.expenseChange !== null ? (
                  <Chip
                    tone={data.expenseChange > 0 ? 'warn' : 'good'}
                    label={`${data.expenseChange > 0 ? 'Up' : 'Down'} ${Math.abs(
                      Math.round(data.expenseChange),
                    )}% on last month`}
                  />
                ) : (
                  // Grey, not green. No comparison exists, and a green chip
                  // saying nothing would read as good news.
                  <Chip label="No month to compare with yet" />
                )}
              </View>

              {data.projectedExpense !== null ? (
                <Text style={type.small}>
                  On track for about {money(data.projectedExpense, data.currency)} by month end.
                </Text>
              ) : null}
            </Card>

            <View style={styles.pair}>
              <Card style={styles.half}>
                <Text style={type.small}>Came in</Text>
                <Text style={[type.heading, { color: theme.positive }]}>
                  {money(data.totals.income, data.currency)}
                </Text>
              </Card>
              <Card style={styles.half}>
                <Text style={type.small}>Left over</Text>
                <Text
                  style={[type.heading, { color: data.totals.net < 0 ? theme.negative : theme.text }]}
                >
                  {money(data.totals.net, data.currency)}
                </Text>
              </Card>
            </View>

            {data.mixedCurrencies ? (
              <Notice text="This month mixes currencies, so the totals are a rough guide." />
            ) : null}

            <Card>
              <Text style={type.heading}>Where it went</Text>
              {data.categories.length === 0 ? (
                <Text style={type.small}>Nothing categorised yet.</Text>
              ) : (
                data.categories.slice(0, 6).map((slice) => (
                  <View key={slice.categoryId ?? 'uncategorised'} style={styles.slice}>
                    <View style={styles.sliceHead}>
                      <Text style={type.body}>{slice.name}</Text>
                      <Text style={[type.body, { fontWeight: '600' }]}>
                        {money(slice.amount, data.currency)}
                      </Text>
                    </View>
                    <View style={styles.track}>
                      <View
                        style={[
                          styles.fill,
                          {
                            width: `${Math.max(2, Math.min(100, slice.share * 100))}%`,
                            backgroundColor: slice.isUncategorised ? theme.neutral : theme.accent,
                          },
                        ]}
                      />
                    </View>
                  </View>
                ))
              )}
            </Card>
          </>
        )}
      </ScrollView>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  screen: { flex: 1, backgroundColor: theme.surface },
  centre: { flex: 1, alignItems: 'center', justifyContent: 'center', backgroundColor: theme.surface },
  content: { padding: space.lg, gap: space.lg, paddingBottom: space.xxl },
  chips: { flexDirection: 'row', flexWrap: 'wrap', gap: space.sm },
  pair: { flexDirection: 'row', gap: space.md },
  half: { flex: 1 },
  slice: { gap: space.xs, marginTop: space.sm },
  sliceHead: { flexDirection: 'row', justifyContent: 'space-between' },
  track: { height: 6, borderRadius: radius.pill, backgroundColor: theme.neutralSoft },
  fill: { height: 6, borderRadius: radius.pill },
});
