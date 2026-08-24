import { useCallback, useEffect, useState } from 'react';
import { ActivityIndicator, ScrollView, StyleSheet, Text, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';

import { useAuth } from '../../src/lib/auth.tsx';
import { REASON_LABELS, type RejectionReason } from '../../src/lib/sms-filter.ts';
import {
  deviceId,
  forgetScans,
  lastScanAt,
  rememberScan,
  smsQueue,
  uploadBatch,
} from '../../src/sms/device.ts';
import { availability, readInbox, requestPermission, type SmsAvailability } from '../../src/sms/reader.ts';
import { describeSync, syncSms, type SmsSyncReport } from '../../src/sms/sync.ts';
import { Button, Card, Notice, Row } from '../../src/ui/components.tsx';
import { space, theme, type } from '../../src/ui/theme.ts';

/**
 * Message reading: whether it is on, what it did, and what it left alone.
 *
 * This screen exists to be read by somebody who is not sure they should have
 * installed this. An app that asks for `READ_SMS` is asking for a great deal,
 * and the honest answer to "what do you do with them?" is not a paragraph in a
 * privacy policy — it is a list of counts from the last time it ran, including
 * the number that never left the handset. So the rejection breakdown is given
 * the same prominence as the payments found, not tucked behind a disclosure.
 */
export default function MessagesScreen() {
  const { signOut } = useAuth();
  const [state, setState] = useState<SmsAvailability | null>(null);
  const [report, setReport] = useState<SmsSyncReport | null>(null);
  const [pending, setPending] = useState(0);
  const [busy, setBusy] = useState(false);
  const [lastRun, setLastRun] = useState<Date | null>(null);

  const refreshState = useCallback(async () => {
    setState(await availability());
    setPending((await smsQueue.pending()).length);
    setLastRun(await lastScanAt());
  }, []);

  useEffect(() => {
    void refreshState();
  }, [refreshState]);

  async function enable() {
    setBusy(true);
    try {
      await requestPermission();
      await refreshState();
    } finally {
      setBusy(false);
    }
  }

  async function scan() {
    setBusy(true);
    try {
      const result = await syncSms({
        queue: smsQueue,
        readInbox,
        upload: uploadBatch,
        lastScanAt,
        rememberScan,
      });
      setReport(result);
      await refreshState();
    } finally {
      setBusy(false);
    }
  }

  async function rescanEverything() {
    // Clears the watermark so the next scan starts from ninety days ago again.
    // Offered because the queue is only a cache -- the messages are all still
    // in the inbox, so a full re-read is always available and always safe.
    setBusy(true);
    try {
      await forgetScans();
      await refreshState();
    } finally {
      setBusy(false);
    }
  }

  if (!state) {
    return (
      <SafeAreaView style={styles.centre}>
        <ActivityIndicator />
      </SafeAreaView>
    );
  }

  return (
    <SafeAreaView style={styles.screen} edges={['top']}>
      <ScrollView contentContainerStyle={styles.content}>
        <Text style={type.title}>Messages</Text>

        <Card>
          <Text style={type.heading}>What leaves your phone</Text>
          <Text style={type.small}>
            Only messages that look like bank alerts: a sender ID like HDFCBK, an amount, and
            wording saying money moved.
          </Text>
          <Text style={type.small}>
            Messages from phone numbers are never uploaded, whatever they say. Neither are one-time
            passwords, failed payments or adverts. Nothing is read for any other purpose, and the
            check runs again on our side before anything is stored.
          </Text>
        </Card>

        {state.available ? (
          <>
            <Card>
              <Row label="Reading messages" value="On" tone={theme.positive} />
              <Row
                label="Last checked"
                value={lastRun ? lastRun.toLocaleString() : 'Not yet'}
              />
              {pending > 0 ? (
                <Row label="Waiting to send" value={pending.toLocaleString()} tone={theme.warningText} />
              ) : null}
            </Card>

            <Button label="Check for new alerts" onPress={scan} busy={busy} />

            {report ? (
              <Card>
                <Text style={type.heading}>Last check</Text>
                <Text style={type.body}>{describeSync(report)}</Text>

                {Object.keys(report.filtered).length > 0 ? (
                  <View style={styles.breakdown}>
                    <Text style={[type.small, styles.breakdownTitle]}>
                      Left on your phone, and why
                    </Text>
                    {Object.entries(report.filtered).map(([reason, count]) => (
                      <Row
                        key={reason}
                        label={REASON_LABELS[reason as RejectionReason] ?? reason}
                        value={String(count)}
                      />
                    ))}
                  </View>
                ) : null}

                {report.upload.error ? <Notice text={report.upload.error} /> : null}
              </Card>
            ) : null}

            <Button label="Read the last 90 days again" variant="secondary" onPress={rescanEverything} />
          </>
        ) : (
          <>
            <Card>
              <Row label="Reading messages" value="Off" tone={theme.muted} />
              <Text style={type.small}>{state.explanation}</Text>
            </Card>

            {state.reason === 'permission-denied' ? (
              <Button label="Allow message reading" onPress={enable} busy={busy} />
            ) : null}

            {state.reason === 'unsupported-platform' ? (
              <Notice
                tone="good"
                text="Everything else works: connect your email on the web and your alerts arrive that way, or add payments here by hand."
              />
            ) : null}
          </>
        )}

        <View style={styles.footer}>
          <Button label="Sign out" variant="secondary" onPress={() => void signOut()} />
          <DeviceLine />
        </View>
      </ScrollView>
    </SafeAreaView>
  );
}

/**
 * Shows the device id.
 *
 * Small print, but it is what appears in the connections list on the web, and
 * without it there is no way to tell which of two phones to disconnect.
 */
function DeviceLine() {
  const [id, setId] = useState<string | null>(null);
  useEffect(() => {
    void deviceId().then(setId);
  }, []);

  return <Text style={type.tiny}>{id ? `This device: ${id}` : ''}</Text>;
}

const styles = StyleSheet.create({
  screen: { flex: 1, backgroundColor: theme.surface },
  centre: { flex: 1, alignItems: 'center', justifyContent: 'center', backgroundColor: theme.surface },
  content: { padding: space.lg, gap: space.lg, paddingBottom: space.xxl },
  breakdown: { marginTop: space.sm, gap: space.xs },
  breakdownTitle: { fontWeight: '600', color: theme.text },
  footer: { marginTop: space.lg, gap: space.md, alignItems: 'center' },
});
