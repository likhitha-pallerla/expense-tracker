import { type ReactNode } from 'react';
import {
  ActivityIndicator,
  Pressable,
  StyleSheet,
  Text,
  View,
  type StyleProp,
  type ViewStyle,
} from 'react-native';

import { radius, space, theme, type } from './theme.ts';

/** The building blocks every screen uses, so no screen invents its own. */

export function Card({ children, style }: { children: ReactNode; style?: StyleProp<ViewStyle> }) {
  return <View style={[styles.card, style]}>{children}</View>;
}

export function Button({
  label,
  onPress,
  variant = 'primary',
  busy = false,
  disabled = false,
}: {
  label: string;
  onPress: () => void;
  variant?: 'primary' | 'secondary' | 'danger';
  busy?: boolean;
  disabled?: boolean;
}) {
  const inactive = disabled || busy;
  return (
    <Pressable
      accessibilityRole="button"
      accessibilityState={{ disabled: inactive, busy }}
      onPress={onPress}
      disabled={inactive}
      style={({ pressed }) => [
        styles.button,
        variant === 'primary' && styles.primary,
        variant === 'secondary' && styles.secondary,
        variant === 'danger' && styles.danger,
        pressed && styles.pressed,
        inactive && styles.inactive,
      ]}
    >
      {busy ? (
        <ActivityIndicator color={variant === 'primary' ? theme.onAccent : theme.text} />
      ) : (
        <Text style={[styles.buttonLabel, variant !== 'primary' && styles.buttonLabelDark]}>
          {label}
        </Text>
      )}
    </Pressable>
  );
}

export function Chip({ label, tone = 'neutral' }: { label: string; tone?: 'good' | 'warn' | 'neutral' }) {
  const background =
    tone === 'good' ? theme.positiveSoft : tone === 'warn' ? theme.warningSoft : theme.neutralSoft;
  const colour =
    tone === 'good' ? theme.positiveText : tone === 'warn' ? theme.warningText : theme.muted;

  return (
    <View style={[styles.chip, { backgroundColor: background }]}>
      <Text style={[styles.chipLabel, { color: colour }]}>{label}</Text>
    </View>
  );
}

/**
 * What a screen shows when it has nothing to show.
 *
 * A separate component because an empty list is the first thing a new user
 * sees, and "no transactions" on its own reads like a fault. Every use supplies
 * a next step.
 */
export function Empty({ title, detail }: { title: string; detail?: string }) {
  return (
    <View style={styles.empty}>
      <Text style={type.heading}>{title}</Text>
      {detail ? <Text style={[type.small, styles.emptyDetail]}>{detail}</Text> : null}
    </View>
  );
}

export function Notice({ text, tone = 'warn' }: { text: string; tone?: 'good' | 'warn' }) {
  return (
    <View
      style={[
        styles.notice,
        { backgroundColor: tone === 'good' ? theme.positiveSoft : theme.warningSoft },
      ]}
    >
      <Text style={{ color: tone === 'good' ? theme.positiveText : theme.warningText, fontSize: 13 }}>
        {text}
      </Text>
    </View>
  );
}

export function Row({ label, value, tone }: { label: string; value: string; tone?: string }) {
  return (
    <View style={styles.row}>
      <Text style={type.small}>{label}</Text>
      <Text style={[type.body, { fontWeight: '600' }, tone ? { color: tone } : null]}>{value}</Text>
    </View>
  );
}

const styles = StyleSheet.create({
  card: {
    backgroundColor: theme.background,
    borderColor: theme.border,
    borderWidth: 1,
    borderRadius: radius.lg,
    padding: space.lg,
    gap: space.sm,
  },
  button: {
    minHeight: 48,
    borderRadius: radius.md,
    alignItems: 'center',
    justifyContent: 'center',
    paddingHorizontal: space.lg,
  },
  primary: { backgroundColor: theme.accent },
  secondary: { backgroundColor: theme.background, borderWidth: 1, borderColor: theme.border },
  danger: { backgroundColor: theme.background, borderWidth: 1, borderColor: theme.negative },
  pressed: { opacity: 0.75 },
  inactive: { opacity: 0.45 },
  buttonLabel: { color: theme.onAccent, fontSize: 15, fontWeight: '600' },
  buttonLabelDark: { color: theme.text },
  chip: { borderRadius: radius.pill, paddingHorizontal: space.md, paddingVertical: 3 },
  chipLabel: { fontSize: 12, fontWeight: '600' },
  empty: { alignItems: 'center', paddingVertical: space.xxl, gap: space.sm },
  emptyDetail: { textAlign: 'center', paddingHorizontal: space.xl },
  notice: { borderRadius: radius.md, padding: space.md },
  row: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center' },
});
