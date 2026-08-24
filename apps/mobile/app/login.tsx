import { useState } from 'react';
import { KeyboardAvoidingView, Platform, ScrollView, StyleSheet, Text, TextInput, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';

import { supabase } from '../src/lib/supabase.ts';
import { Button, Notice } from '../src/ui/components.tsx';
import { radius, space, theme, type } from '../src/ui/theme.ts';

/**
 * Signing in.
 *
 * E-mail and password only, deliberately. The web app offers Google sign-in
 * because linking a Gmail mailbox needs that consent anyway; the phone reads
 * text messages instead, so adding a native OAuth flow here would be a second
 * way in that earns nothing. The same account works in both places.
 */
export default function LoginScreen() {
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [mode, setMode] = useState<'signin' | 'signup'>('signin');
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState<{ text: string; tone: 'good' | 'warn' } | null>(null);

  async function submit() {
    if (!email.trim() || !password) {
      setMessage({ text: 'Enter your email and password.', tone: 'warn' });
      return;
    }

    setBusy(true);
    setMessage(null);
    try {
      const credentials = { email: email.trim(), password };
      const { error } =
        mode === 'signin'
          ? await supabase.auth.signInWithPassword(credentials)
          : await supabase.auth.signUp(credentials);

      if (error) {
        setMessage({ text: error.message, tone: 'warn' });
      } else if (mode === 'signup') {
        // Whether a session comes back depends on the confirm-email setting in
        // Supabase, so the wording covers both rather than promising one.
        setMessage({ text: 'Account created. Check your email if it asks you to confirm.', tone: 'good' });
      }
      // On a successful sign-in the auth listener redirects; nothing to do here.
    } finally {
      setBusy(false);
    }
  }

  return (
    <SafeAreaView style={styles.screen}>
      <KeyboardAvoidingView
        behavior={Platform.OS === 'ios' ? 'padding' : undefined}
        style={styles.fill}
      >
        <ScrollView contentContainerStyle={styles.content} keyboardShouldPersistTaps="handled">
          <View style={styles.header}>
            <Text style={type.title}>Expense tracker</Text>
            <Text style={type.small}>
              Your spending, gathered from your bank alerts — with the duplicates removed.
            </Text>
          </View>

          <View style={styles.form}>
            <Text style={type.small}>Email</Text>
            <TextInput
              value={email}
              onChangeText={setEmail}
              autoCapitalize="none"
              autoComplete="email"
              keyboardType="email-address"
              inputMode="email"
              style={styles.input}
              placeholder="you@example.com"
              placeholderTextColor={theme.faint}
            />

            <Text style={type.small}>Password</Text>
            <TextInput
              value={password}
              onChangeText={setPassword}
              secureTextEntry
              autoComplete={mode === 'signin' ? 'current-password' : 'new-password'}
              style={styles.input}
              placeholder="••••••••"
              placeholderTextColor={theme.faint}
            />

            {message ? <Notice text={message.text} tone={message.tone} /> : null}

            <Button
              label={mode === 'signin' ? 'Sign in' : 'Create account'}
              onPress={submit}
              busy={busy}
            />
            <Button
              label={mode === 'signin' ? 'I need an account' : 'I already have an account'}
              variant="secondary"
              onPress={() => {
                setMode(mode === 'signin' ? 'signup' : 'signin');
                setMessage(null);
              }}
            />
          </View>
        </ScrollView>
      </KeyboardAvoidingView>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  screen: { flex: 1, backgroundColor: theme.background },
  fill: { flex: 1 },
  content: { flexGrow: 1, justifyContent: 'center', padding: space.xl, gap: space.xxl },
  header: { gap: space.sm },
  form: { gap: space.sm },
  input: {
    borderWidth: 1,
    borderColor: theme.border,
    borderRadius: radius.md,
    paddingHorizontal: space.md,
    minHeight: 48,
    fontSize: 15,
    color: theme.text,
    marginBottom: space.sm,
  },
});
