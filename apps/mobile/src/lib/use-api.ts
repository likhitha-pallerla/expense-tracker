import { useCallback, useEffect, useState } from 'react';

import { api, ApiError } from './api.ts';

/**
 * Fetches once, then again whenever the screen asks.
 *
 * Small on purpose. A phone screen needs three things a plain `useEffect` does
 * not give for free: it must not set state after the user has navigated away,
 * it must tell a first load apart from a pull-to-refresh (one shows a spinner
 * in the middle, the other shows it at the top), and it must keep the last good
 * data on screen when a refresh fails. Losing a month of figures because the
 * lift lost signal would be a poor trade for a fresher number.
 */

export type Resource<T> = {
  data: T | null;
  error: string | null;
  loading: boolean;
  refreshing: boolean;
  refresh: () => Promise<void>;
};

export function useApi<T>(path: string | null): Resource<T> {
  const [data, setData] = useState<T | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(path !== null);
  const [refreshing, setRefreshing] = useState(false);

  const load = useCallback(
    async (isRefresh: boolean) => {
      if (path === null) return;
      if (isRefresh) setRefreshing(true);

      try {
        const result = await api.get<T>(path);
        setData(result);
        setError(null);
      } catch (cause) {
        setError(cause instanceof ApiError ? cause.message : 'Something went wrong.');
      } finally {
        setLoading(false);
        setRefreshing(false);
      }
    },
    [path],
  );

  useEffect(() => {
    let active = true;
    // The guard is on the whole call rather than each setState so that a screen
    // unmounted mid-request does not warn about updating a gone component.
    void (async () => {
      if (!active) return;
      await load(false);
    })();
    return () => {
      active = false;
    };
  }, [load]);

  return {
    data,
    error,
    loading,
    refreshing,
    refresh: useCallback(() => load(true), [load]),
  };
}
