import { Stack, useRouter, useSegments } from 'expo-router';
import { StatusBar } from 'expo-status-bar';
import { useEffect } from 'react';
import { ActivityIndicator, StyleSheet, Text, View } from 'react-native';
import { SafeAreaProvider } from 'react-native-safe-area-context';

import { AuthProvider, useAuth } from '../src/lib/auth.tsx';
import { missingConfig } from '../src/lib/config.ts';
import { theme, type } from '../src/ui/theme.ts';

/**
 * The shell: configuration check, then session check, then the app.
 *
 * Both checks happen before anything else renders, because the failures they
 * catch otherwise surface much later as an unexplained network error on a
 * screen that looks like it should be working.
 */

function Gate() {
  const { session, loading } = useAuth();
  const segments = useSegments();
  const router = useRouter();

  useEffect(() => {
    if (loading) return;

    const onLoginScreen = segments[0] === 'login';
    if (!session && !onLoginScreen) {
      router.replace('/login');
    } else if (session && onLoginScreen) {
      router.replace('/');
    }
    // Navigating during render is rejected by React, so this waits for the
    // effect -- the same rule the web app follows.
  }, [session, loading, segments, router]);

  if (loading) {
    return (
      <View style={styles.centre}>
        <ActivityIndicator />
      </View>
    );
  }

  return (
    <Stack screenOptions={{ headerShown: false, contentStyle: { backgroundColor: theme.surface } }}>
      <Stack.Screen name="(tabs)" />
      <Stack.Screen name="login" />
    </Stack>
  );
}

export default function RootLayout() {
  const missing = missingConfig();

  if (missing.length > 0) {
    // Stated plainly rather than left to fail as "network request failed" three
    // taps later. This is the single most common way a fresh checkout does not
    // run, and the fix is one file.
    return (
      <SafeAreaProvider>
        <View style={styles.centre}>
          <Text style={type.heading}>Not configured yet</Text>
          <Text style={[type.small, styles.detail]}>
            Copy <Text style={styles.code}>.env.example</Text> to{' '}
            <Text style={styles.code}>.env</Text> in <Text style={styles.code}>apps/mobile</Text> and
            fill in {missing.join(', ')}.
          </Text>
        </View>
      </SafeAreaProvider>
    );
  }

  return (
    <SafeAreaProvider>
      <AuthProvider>
        <StatusBar style="dark" />
        <Gate />
      </AuthProvider>
    </SafeAreaProvider>
  );
}

const styles = StyleSheet.create({
  centre: {
    flex: 1,
    alignItems: 'center',
    justifyContent: 'center',
    padding: 24,
    gap: 12,
    backgroundColor: theme.background,
  },
  detail: { textAlign: 'center', lineHeight: 20 },
  code: { fontFamily: 'monospace', color: theme.text },
});
