import { AppShell } from "@/components/app-shell";
import { HealthView } from "@/components/health-view";
import { EmptyState } from "@/components/ui/form";
import { apiFetch } from "@/lib/api";
import type { HealthReport } from "@/lib/types";

export const metadata = { title: "Health" };

export default async function HealthPage() {
  let report: HealthReport | null = null;
  let error: string | null = null;

  try {
    report = await apiFetch<HealthReport>("/api/financial-health");
  } catch (err) {
    error = (err as Error).message;
  }

  const partial = report !== null && report.score !== null && report.coverage < 100;

  return (
    <AppShell
      title="Financial health"
      description="One score, and every number behind it."
      action={
        partial ? (
          <div className="text-right">
            <p className="text-xs uppercase tracking-wide text-neutral-500">
              Coverage
            </p>
            <p className="font-mono text-xl">{report!.coverage}%</p>
          </div>
        ) : undefined
      }
    >
      {error || !report ? (
        <EmptyState>Could not work out your score: {error}</EmptyState>
      ) : (
        <HealthView report={report} />
      )}
    </AppShell>
  );
}
