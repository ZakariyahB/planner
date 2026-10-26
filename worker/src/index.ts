import { AuthError, verifyIdToken, type AuthConfig } from "./auth";

export interface Env {
  ALLOWED_EMAIL: string;
  GOOGLE_WEB_CLIENT_ID: string;
  MONTHLY_CAP_GBP: string;
  USAGE: KVNamespace;
  ANTHROPIC_API_KEY: string;
}

export interface Deps {
  auth?: Pick<AuthConfig, "fetchJwks" | "nowSeconds">;
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
    // Spend cap, model routing and forwarding to Anthropic land in M4.
    return json({ error: "not implemented" }, 501);
  }

  return new Response("not found", { status: 404 });
}

function json(body: unknown, status: number): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "content-type": "application/json" },
  });
}

export default {
  fetch: (req: Request, env: Env) => handle(req, env),
} satisfies ExportedHandler<Env>;
