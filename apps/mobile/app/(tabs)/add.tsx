import { useRouter } from 'expo-router';
import { useState } from 'react';
import {
  KeyboardAvoidingView,
  Platform,
  Pressable,
  ScrollView,
  StyleSheet,
  Text,
  TextInput,
  View,
} from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';

import { api, ApiError } from '../../src/lib/api.ts';
import type { Account, Category } from '../../src/lib/types.ts';
import { useApi } from '../../src/lib/use-api.ts';
import { Button, Notice } from '../../src/ui/components.tsx';
import { radius, space, theme, type } from '../../src/ui/theme.ts';

/**
 * Adding something the messages could not see.
 *
 * Cash, mostly — the one category of spending no bank will ever send an alert
 * about, and the reason an automatic tracker still needs a keyboard. The form
 * is short for that reason: amount, what it was, which pot, done. Anything
 * else can be corrected later on a bigger screen.
 */
export default function AddScreen() {
  const router = useRouter();
  const categories = useApi<Category[]>('/api/categories');
  const accounts = useApi<Account[]>('/api/accounts');

  const [amount, setAmount] = useState('');
  const [description, setDescription] = useState('');
  const [categoryId, setCategoryId] = useState<string | null>(null);
  const [accountId, setAccountId] = useState<string | null>(null);
  const [direction, setDirection] = useState<'debit' | 'credit'>('debit');
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState<{ text: string; tone: 'good' | 'warn' } | null>(null);

  const usable = (accounts.data ?? []).filter((a) => !a.isArchived);
  const chosenAccount = usable.find((a) => a.id === accountId) ?? usable[0];

  async function save() {
    const value = Number(amount.replace(/,/g, ''));
    if (!Number.isFinite(value) || value <= 0) {
      setMessage({ text: 'Enter an amount greater than zero.', tone: 'warn' });
      return;
    }

    setBusy(true);
    setMessage(null);
    try {
      await api.post('/api/transactions', {
        kind: 'expense',
        direction,
        amount: value.toFixed(2),
        currency: chosenAccount?.currency ?? 'INR',
        // Recorded as "now" rather than asking for a date. Someone typing this
        // in has almost always just paid; offering a date picker first would
        // add a step to the common case to serve the rare one.
        occurredAt: new Date().toISOString(),
        description: description.trim() || null,
        categoryId,
        accountId: chosenAccount?.id ?? null,
      });

      setAmount('');
      setDescription('');
      setMessage({ text: 'Saved.', tone: 'good' });
      // Sends the user to the list, where the new row is visible proof it
      // landed -- more convincing than a message on the form they just left.
      router.replace('/activity');
    } catch (error) {
      setMessage({
        text: error instanceof ApiError ? error.message : 'Could not save that.',
        tone: 'warn',
      });
    } finally {
      setBusy(false);
    }
  }

  return (
    <SafeAreaView style={styles.screen} edges={['top']}>
      <KeyboardAvoidingView
        behavior={Platform.OS === 'ios' ? 'padding' : undefined}
        style={styles.fill}
      >
        <ScrollView contentContainerStyle={styles.content} keyboardShouldPersistTaps="handled">
          <Text style={type.title}>Add</Text>

          <View style={styles.toggle}>
            {(['debit', 'credit'] as const).map((option) => (
              <Pressable
                key={option}
                onPress={() => setDirection(option)}
                style={[styles.toggleOption, direction === option && styles.toggleActive]}
              >
                <Text style={[styles.toggleLabel, direction === option && styles.toggleLabelActive]}>
                  {option === 'debit' ? 'Money out' : 'Money in'}
                </Text>
              </Pressable>
            ))}
          </View>

          <View style={styles.field}>
            <Text style={type.small}>Amount</Text>
            <TextInput
              value={amount}
              onChangeText={setAmount}
              keyboardType="decimal-pad"
              inputMode="decimal"
              placeholder="0"
              placeholderTextColor={theme.faint}
              style={styles.amountInput}
            />
          </View>

          <View style={styles.field}>
            <Text style={type.small}>What was it?</Text>
            <TextInput
              value={description}
              onChangeText={setDescription}
              placeholder="Lunch, auto fare, chai…"
              placeholderTextColor={theme.faint}
              style={styles.input}
            />
          </View>

          <View style={styles.field}>
            <Text style={type.small}>Category</Text>
            <View style={styles.options}>
              {(categories.data ?? []).slice(0, 14).map((category) => (
                <Pressable
                  key={category.id}
                  onPress={() => setCategoryId(categoryId === category.id ? null : category.id)}
                  style={[styles.option, categoryId === category.id && styles.optionActive]}
                >
                  <Text
                    style={[
                      styles.optionLabel,
                      categoryId === category.id && styles.optionLabelActive,
                    ]}
                  >
                    {category.name}
                  </Text>
                </Pressable>
              ))}
            </View>
          </View>

          {usable.length > 1 ? (
            <View style={styles.field}>
              <Text style={type.small}>Paid from</Text>
              <View style={styles.options}>
                {usable.map((account) => (
                  <Pressable
                    key={account.id}
                    onPress={() => setAccountId(account.id)}
                    style={[styles.option, chosenAccount?.id === account.id && styles.optionActive]}
                  >
                    <Text
                      style={[
                        styles.optionLabel,
                        chosenAccount?.id === account.id && styles.optionLabelActive,
                      ]}
                    >
                      {account.name}
                    </Text>
                  </Pressable>
                ))}
              </View>
            </View>
          ) : null}

          {message ? <Notice text={message.text} tone={message.tone} /> : null}

          <Button label="Save" onPress={save} busy={busy} />
        </ScrollView>
      </KeyboardAvoidingView>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  screen: { flex: 1, backgroundColor: theme.background },
  fill: { flex: 1 },
  content: { padding: space.lg, gap: space.lg, paddingBottom: space.xxl },
  field: { gap: space.sm },
  input: {
    borderWidth: 1,
    borderColor: theme.border,
    borderRadius: radius.md,
    paddingHorizontal: space.md,
    minHeight: 48,
    fontSize: 15,
    color: theme.text,
  },
  amountInput: {
    borderWidth: 1,
    borderColor: theme.border,
    borderRadius: radius.md,
    paddingHorizontal: space.md,
    minHeight: 64,
    fontSize: 32,
    fontWeight: '700',
    color: theme.text,
  },
  toggle: {
    flexDirection: 'row',
    backgroundColor: theme.neutralSoft,
    borderRadius: radius.md,
    padding: 3,
  },
  toggleOption: { flex: 1, alignItems: 'center', paddingVertical: space.md, borderRadius: radius.sm },
  toggleActive: { backgroundColor: theme.background },
  toggleLabel: { fontSize: 14, fontWeight: '600', color: theme.muted },
  toggleLabelActive: { color: theme.text },
  options: { flexDirection: 'row', flexWrap: 'wrap', gap: space.sm },
  option: {
    borderWidth: 1,
    borderColor: theme.border,
    borderRadius: radius.pill,
    paddingHorizontal: space.md,
    paddingVertical: space.sm,
  },
  optionActive: { backgroundColor: theme.accent, borderColor: theme.accent },
  optionLabel: { fontSize: 13, color: theme.text },
  optionLabelActive: { color: theme.onAccent, fontWeight: '600' },
});
