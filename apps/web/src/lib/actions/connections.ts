"use server";

import { redirect } from "next/navigation";
import { revalidatePath } from "next/cache";

import { apiDelete, apiPost } from "@/lib/api";
import { text, toFormState, type FormState } from "@/lib/actions/form-state";

/**
 * Starts the handshake and sends the browser to the provider.
 *
 * <p>Two hops rather than one: the API has to be called with the user's token,
 * which only the server has, and the provider has to be reached by the browser,
 * which only it can do. So the server asks for the URL and then redirects.
 */
export async function connectMailbox(
  _prev: FormState,
  form: FormData,
): Promise<FormState> {
  const provider = text(form, "provider");
  if (!provider) {
    return { ok: false, message: "Missing provider." };
  }

  let authorizeUrl: string;
  try {
    const response = await apiPost<{ authorizeUrl: string }>(
      `/api/connections/${provider}/start`,
      { returnPath: "/connections" },
    );
    authorizeUrl = response.authorizeUrl;
  } catch (error) {
    return toFormState(error);
  }

  // Outside the try: redirect() works by throwing, and catching it here would
  // turn a successful start into an error message.
  redirect(authorizeUrl);
}

export async function disconnectMailbox(
  _prev: FormState,
  form: FormData,
): Promise<FormState> {
  const id = text(form, "id");
  if (!id) {
    return { ok: false, message: "Missing connection." };
  }

  try {
    await apiDelete<void>(`/api/connections/${id}`);
  } catch (error) {
    return toFormState(error);
  }

  revalidatePath("/connections");
  revalidatePath("/", "layout");
  return { ok: true, message: "Disconnected." };
}
