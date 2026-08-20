import { LoginForm } from "@/components/login-form";

/**
 * `next` is read on the server so the form is present in the initial HTML.
 * Reading it with `useSearchParams` instead would force the Suspense boundary
 * to fall back to nothing until hydration.
 */
export default async function LoginPage({
  searchParams,
}: {
  searchParams: Promise<{ next?: string; error?: string }>;
}) {
  const params = await searchParams;

  // Only relative paths, otherwise `next` becomes an open redirect.
  const next =
    params.next?.startsWith("/") && !params.next.startsWith("//")
      ? params.next
      : "/dashboard";

  return (
    <main className="flex flex-1 items-center justify-center px-6 py-16">
      <div className="w-full max-w-sm space-y-4">
        {params.error && (
          <p
            role="alert"
            className="rounded-md border border-red-200 px-3 py-2 text-sm text-red-600 dark:border-red-900"
          >
            {params.error}
          </p>
        )}
        <LoginForm next={next} />
      </div>
    </main>
  );
}
