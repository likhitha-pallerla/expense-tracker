/**
 * Product analytics, on the assumption that every screen in this application
 * has somebody's money on it.
 *
 * PostHog's defaults are built for an e-commerce funnel and are actively
 * dangerous here. Two of them in particular:
 *
 * - **Autocapture** records the text of whatever you clicked. On the
 *   transactions page that is a merchant and an amount, so a single click
 *   would ship "Swiggy" and "1,240.00" to a third party. It is off.
 * - **Session recording** replays the screen. The screen is a bank statement.
 *   Also off.
 *
 * What is left is a short list of named events, written by hand, each carrying
 * only counts and enum-ish labels. If you are adding an event and find
 * yourself wanting to attach a merchant, an amount or a message body, the
 * answer is a count or a bucket instead.
 *
 * Inert unless NEXT_PUBLIC_POSTHOG_KEY is set, so a fork or a local checkout
 * sends nothing and needs no account. The library itself is loaded on demand
 * for the same reason: with no key configured it is never fetched, so an
 * installation that does not use analytics does not make every visitor
 * download an analytics SDK it will not run.
 */
import type { PostHog } from "posthog-js";

/**
 * The complete set of events this application sends.
 *
 * A closed union rather than free-form strings, because the failure mode of
 * analytics is a thousand near-duplicate event names nobody can chart, and
 * because it puts every event in one reviewable list.
 *
 * Kept to events that answer a question the project actually has. Each one is
 * a decision or an outcome, never a value.
 */
export type AnalyticsEvent =
  /** Did automatic ingestion work? The product's central hypothesis. */
  | "mailbox_sync_completed"
  /** Feedback on the duplicate matcher: merged, kept both, or dismissed. */
  | "duplicate_resolved"
  /** Feedback on the sender gate: trusted or discarded. */
  | "held_sender_resolved";

/**
 * Properties allowed on an event.
 *
 * Numbers and booleans are safe by construction. Strings are the risk, so the
 * rule is that a string must be a label from a known set -- "gmail",
 * "duplicate", "manual" -- and never a value the user typed or a bank sent.
 */
export type AnalyticsProperties = Record<string, number | boolean | string>;

let started = false;
let client: PostHog | null = null;

/**
 * Events recorded before the library finished loading.
 *
 * {@link init} resolves an import, so the first page view is raised before
 * there is anything to send it to. Dropping it would lose the entry point of
 * every session -- the one page view that says where people arrive.
 */
const pending: Array<() => void> = [];

function whenReady(action: () => void): void {
  if (client) {
    action();
  } else if (started) {
    pending.push(action);
  }
}

/**
 * Replaces a path's identifiers with placeholders.
 *
 * `/transactions/9f8e...` is a different page from `/transactions/1a2b...` only
 * in the sense that they are different rows; as analytics they are one screen,
 * and sending the ids would build a map of which records a person opens.
 * Numeric segments go too, since those are typically dates or amounts.
 */
export function scrubPath(pathname: string): string {
  return pathname
    .split("/")
    .map((segment) => {
      if (!segment) return segment;
      if (/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(segment)) {
        return ":id";
      }
      if (/^\d+$/.test(segment)) return ":n";
      return segment;
    })
    .join("/");
}

/** Whether analytics are configured at all. */
export function isEnabled(): boolean {
  return Boolean(process.env.NEXT_PUBLIC_POSTHOG_KEY);
}

/**
 * Starts PostHog, once.
 *
 * Every option here that turns something off is doing more work than the ones
 * that turn something on -- see the note at the top of the file.
 *
 * The import is dynamic so that the SDK is fetched only by installations that
 * have configured a key.
 */
export function init(): void {
  if (started || !isEnabled() || typeof window === "undefined") return;
  started = true;

  void import("posthog-js").then(({ default: posthog }) => {
    posthog.init(process.env.NEXT_PUBLIC_POSTHOG_KEY as string, {
      api_host: process.env.NEXT_PUBLIC_POSTHOG_HOST ?? "https://us.i.posthog.com",

      // The important ones.
      autocapture: false,
      disable_session_recording: true,
      capture_pageview: false, // sent by hand, with the path scrubbed
      capture_pageleave: false,

      // A URL here can carry a search term ("psychiatrist") or a date range.
      // Neither is worth having.
      mask_all_element_attributes: true,
      mask_all_text: true,

      // Do not let PostHog decide it wants an IP address or a precise location.
      ip: false,

      persistence: "localStorage+cookie",
    });

    client = posthog;
    for (const action of pending.splice(0)) action();
  });
}

/**
 * Ties events to a user by id and nothing else.
 *
 * PostHog will happily take an email and display it in its UI, which is
 * convenient and also means a support contractor with dashboard access can see
 * who banks where. The id is enough to count returning users.
 */
export function identify(userId: string): void {
  whenReady(() => client?.identify(userId));
}

/** Clears the identity on sign-out, so a shared browser does not merge people. */
export function reset(): void {
  whenReady(() => client?.reset());
}

/** Records a page view with identifiers stripped out of the path. */
export function pageview(pathname: string): void {
  const scrubbed = scrubPath(pathname);
  whenReady(() => client?.capture("$pageview", { $current_url: scrubbed }));
}

/**
 * Records a named event.
 *
 * Typed against the union above so a new event has to be added to that list,
 * where it will be read.
 */
export function track(event: AnalyticsEvent, properties?: AnalyticsProperties): void {
  whenReady(() => client?.capture(event, properties));
}
