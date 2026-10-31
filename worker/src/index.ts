import { AuthError, verifyIdToken, type AuthConfig } from "./auth";
import { forwardMessages, json } from "./proxy";

export interface Env {
  ALLOWED_EMAIL: string;
  GOOGLE_WEB_CLIENT_ID: string;
  MONTHLY_CAP_GBP: string;
  USAGE: KVNamespace;
  ANTHROPIC_API_KEY: string;
  /** Optional overrides for the model used for chat/replans and for scheduled runs. */
  MODEL_CHAT?: string;
  MODEL_SCHEDULED?: string;
  /** "1" turns off the server-side refusal fallback (on by default for the Sonnet and Opus 5.5 models). */
  DISABLE_FALLBACKS?: string;
}

export interface Deps {
  auth?: Pick<AuthConfig, "fetchJwks" | "nowSeconds">;
  /** Replaces the call to Anthropic; used by tests. */
  upstream?: (req: Request) => Promise<Response>;
  now?: () => Date;
}

export async function handle(req: Request, env: Env, deps: Deps = {}): Promise<Response> {
  const url = new URL(req.url);

  if (req.method === "GET" && url.pathname === "/health") {
    return new Response("ok");
  }

  if (req.method === "POST" && url.pathname === "/v1/messages") {
    const bearer = /^Bearer (.+)$/.exec(req.headers.get("Authorization") ?? "");
    if (!bearer) return json({ error: "missing token" }, 401);
    try {
      await verifyIdToken(bearer[1], {
        allowedEmail: env.ALLOWED_EMAIL,
        clientId: env.GOOGLE_WEB_CLIENT_ID,
        ...deps.auth,
      });
    } catch (e) {
      if (e instanceof AuthError) return json({ error: e.message }, 401);
      throw e;
    }
    return forwardMessages(req, env, deps);
  }

  return new Response("not found", { status: 404 });
}

export default {
  fetch: (req: Request, env: Env) => handle(req, env),
} satisfies ExportedHandler<Env>;
