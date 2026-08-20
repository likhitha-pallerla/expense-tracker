/**
 * Error shape shared by server and client code.
 *
 * Kept apart from `api.ts` because that module imports `next/headers`, which
 * cannot be bundled for the browser; a client component only needs the type.
 */

/** RFC 9457 problem details, as produced by the API's exception handler. */
export type ProblemDetail = {
  title?: string;
  detail?: string;
  fields?: Record<string, string>;
};

export class ApiError extends Error {
  constructor(
    readonly status: number,
    message: string,
    /** Per-field messages, so a form can mark the offending input. */
    readonly fields: Record<string, string> = {},
  ) {
    super(message);
    this.name = "ApiError";
  }
}

/**
 * Turns an error response into an ApiError, preferring the server's own
 * message. A raw body is only used as a last resort, since it can carry
 * internal detail that should never reach a user.
 */
export async function toApiError(
  response: Response,
  path: string,
): Promise<ApiError> {
  const fallback = `Request to ${path} failed with ${response.status}`;
  const text = await response.text();

  if (!text) {
    return new ApiError(response.status, fallback);
  }

  try {
    const problem = JSON.parse(text) as ProblemDetail;
    return new ApiError(
      response.status,
      problem.detail ?? problem.title ?? fallback,
      problem.fields ?? {},
    );
  } catch {
    return new ApiError(response.status, fallback);
  }
}
