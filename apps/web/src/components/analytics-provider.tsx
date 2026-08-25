"use client";

import { usePathname } from "next/navigation";
import { useEffect } from "react";

import * as analytics from "@/lib/analytics";

/**
 * Starts analytics and records page views.
 *
 * Split from {@link analytics} so the module that decides *what may be sent*
 * stays free of React and can be tested on its own.
 *
 * Page views are sent from here rather than by PostHog's own listener because
 * the path has to be scrubbed of record ids first, and because the App Router
 * changes routes without a navigation event that PostHog would see.
 */
export function AnalyticsProvider({ userId }: { userId?: string }) {
  const pathname = usePathname();

  useEffect(() => {
    analytics.init();
  }, []);

  useEffect(() => {
    if (userId) {
      analytics.identify(userId);
    }
  }, [userId]);

  useEffect(() => {
    if (pathname) {
      analytics.pageview(pathname);
    }
  }, [pathname]);

  return null;
}
