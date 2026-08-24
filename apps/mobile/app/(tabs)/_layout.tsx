import { Tabs } from 'expo-router';
import { Text } from 'react-native';

import { theme } from '../../src/ui/theme.ts';

/**
 * Four tabs, which is the most a thumb reaches comfortably.
 *
 * The web application has eleven pages in its sidebar. Carrying all of them
 * across would be a worse app, not a more capable one: on a phone the questions
 * are "what did I spend this month", "what were the last few", "let me add the
 * one I paid in cash", and "is it still reading my messages". Budgets, goals,
 * imports and duplicate review are unhurried, comparative work that belongs on
 * a larger screen.
 *
 * Labels are text rather than icons because there is no icon font in this build
 * and an unrecognisable glyph is worse than a word.
 */
export default function TabsLayout() {
  return (
    <Tabs
      screenOptions={{
        headerShown: false,
        tabBarActiveTintColor: theme.accent,
        tabBarInactiveTintColor: theme.faint,
        tabBarStyle: { borderTopColor: theme.border, backgroundColor: theme.background },
        tabBarLabelStyle: { fontSize: 12, fontWeight: '600' },
      }}
    >
      <Tabs.Screen
        name="index"
        options={{ title: 'This month', tabBarIcon: () => <Text> </Text> }}
      />
      <Tabs.Screen name="activity" options={{ title: 'Activity', tabBarIcon: () => <Text> </Text> }} />
      <Tabs.Screen name="add" options={{ title: 'Add', tabBarIcon: () => <Text> </Text> }} />
      <Tabs.Screen name="messages" options={{ title: 'Messages', tabBarIcon: () => <Text> </Text> }} />
    </Tabs>
  );
}
