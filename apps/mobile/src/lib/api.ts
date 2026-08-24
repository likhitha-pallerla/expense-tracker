import { API_URL } from './config.ts';
import { accessToken } from './supabase.ts';

/**
 * Talking to the API from a handset.
 *
 * The one idea worth naming here is the split between failures worth retrying
 * and failures that will never succeed. On a phone this is not a nicety: a
 * request fails constantly — a tunnel, a lift, a carrier handover — and if the
 * upload queue could not tell those apart it would either give up on messages
 * that would have gone through a minute later, or retry a malformed batch until
 * the battery died. Every caller gets that distinction through {@link ApiError}.
 */

export class ApiError extends Error {
  readonly status: number;

  constructor(status: number, message: string) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
  }

  /**
   * Whether trying again could plausibly work.
   *
   * Status 0 stands for "the request never reached a server" — no connection,
   * DNS failure, timeout — which is the most common case on mobile and always
   * worth retrying. Everything from 500 up is the server having a bad moment.
   * A 4xx is the request itself being wrong, and repeating it changes nothing.
   *
   * 401 is the exception inside that exception: the token has expired and the
   * Supabase client will silently refresh it, so the same call a moment later
   * may well succeed.
   */
  get retryable(): boolean {
    return this.status === 0 || this.status === 401 || this.status === 408 || this.status >= 500;
  }
}

/** Longer than a browser would wait: a phone on a weak signal is not broken. */
const TIMEOUT_MS = 30_000;

async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  const token = await accessToken();
  if (!token) {
    throw new ApiError(401, 'Signed out.');
  }

  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), TIMEOUT_MS);

  let response: Response;
  try {
    response = await fetch(`${API_URL}${path}`, {
      ...init,
      signal: controller.signal,
      headers: {
        'Content-Type': 'application/json',
        Authorization: `Bearer ${token}`,
        ...(init.headers ?? {}),
      },
    });
  } catch (cause) {
    // Includes the abort above. Reported as status 0 so the queue treats it as
    // worth retrying, which it is.
    const message = cause instanceof Error && cause.name === 'AbortError'
      ? 'The request timed out.'
      : 'Could not reach the server.';
    throw new ApiError(0, message);
  } finally {
    clearTimeout(timer);
  }

  if (!response.ok) {
    throw new ApiError(response.status, await errorMessage(response));
  }

  if (response.status === 204) {
    return undefined as T;
  }
  return (await response.json()) as T;
}

/**
 * Digs the useful sentence out of a failure.
 *
 * The API's error handler returns a JSON body with a `message`. A proxy, a
 * cold-start page or a captive portal will not, and calling `.json()` on their
 * HTML throws — turning a clear "502 Bad Gateway" into an unrelated parse
 * error several frames away from the cause.
 */
async function errorMessage(response: Response): Promise<string> {
  try {
    const body = (await response.json()) as { message?: string; error?: string };
    return body.message ?? body.error ?? `Request failed (${response.status}).`;
  } catch {
    return `Request failed (${response.status}).`;
  }
}

export const api = {
  get: <T>(path: string) => request<T>(path),
  post: <T>(path: string, body?: unknown) =>
    request<T>(path, { method: 'POST', body: body === undefined ? undefined : JSON.stringify(body) }),
  delete: <T>(path: string) => request<T>(path, { method: 'DELETE' }),
};
