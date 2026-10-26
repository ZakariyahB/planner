import type { Deps, Env } from "./index";

const UPSTREAM_URL = "https://api.anthropic.com/v1/messages";
const DEFAULT_CHAT_MODEL = "claude-sonnet-5-5";
const DEFAULT_SCHEDULED_MODEL = "claude-haiku-5-5";
const MAX_TOKENS_LIMIT = 2048;
const DEFAULT_MAX_TOKENS = 1024;
const MAX_BODY_CHARS = 200_000;

export type Purpose = "chat" | "replan" | "scheduled";

/** Chat and replans get the stronger model; scheduled runs (23:30 draft, morning brief) the cheaper one. */
export function modelFor(purpose: Purpose, env: Env): string {
  return purpose === "scheduled" ? (env.MODEL_SCHEDULED ?? DEFAULT_SCHEDULED_MODEL) : (env.MODEL_CHAT ?? DEFAULT_CHAT_MODEL);
}

function purposeOf(header: string | null): Purpose | null {
  const value = (header ?? "chat").toLowerCase();
  return value === "chat" || value === "replan" || value === "scheduled" ? value : null;
}

export function json(body: unknown, status: number): Response {
  return new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json" } });
}

/** The client chooses only the purpose, never the model, and cannot raise the token limit. */
export async function forwardMessages(req: Request, env: Env, deps: Deps): Promise<Response> {
  const purpose = purposeOf(req.headers.get("X-Planner-Purpose"));
  if (!purpose) return json({ error: "unknown purpose" }, 400);

  const text = await req.text();
  if (text.length > MAX_BODY_CHARS) return json({ error: "request too large" }, 413);

  let parsed: unknown;
  try {
    parsed = JSON.parse(text);
  } catch {
    return json({ error: "body must be JSON" }, 400);
  }
  if (typeof parsed !== "object" || parsed === null || Array.isArray(parsed)) {
    return json({ error: "body must be a JSON object" }, 400);
  }
  const payload = parsed as Record<string, unknown>;
  if (!Array.isArray(payload.messages) || payload.messages.length === 0) {
    return json({ error: "messages are required" }, 400);
  }

  const model = modelFor(purpose, env);
  const requested = typeof payload.max_tokens === "number" ? payload.max_tokens : DEFAULT_MAX_TOKENS;
  const upstreamReq = new Request(UPSTREAM_URL, {
    method: "POST",
    headers: {
      "content-type": "application/json",
      "x-api-key": env.ANTHROPIC_API_KEY,
      "anthropic-version": "2023-06-01",
    },
    body: JSON.stringify({ ...payload, model, stream: false, max_tokens: Math.min(requested, MAX_TOKENS_LIMIT) }),
  });

  let upstream: Response;
  try {
    upstream = await (deps.upstream ?? fetch)(upstreamReq);
  } catch {
    return json({ error: "upstream unreachable" }, 502);
  }
  if (upstream.status === 401 || upstream.status === 403 || upstream.status >= 500) {
    return json({ error: `upstream error (${upstream.status})` }, 502);
  }
  return new Response(await upstream.text(), {
    status: upstream.status,
    headers: { "content-type": upstream.headers.get("content-type") ?? "application/json" },
  });
}
