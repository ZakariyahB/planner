import { beforeAll, describe, expect, it } from "vitest";
import { handle, type Deps, type Env } from "../src/index";
import { asKv, FakeKV } from "./fakes";
import { authDeps, baseEnv, goodClaims, setupKeys, signToken } from "./token";

beforeAll(setupKeys);

interface Captured {
  url: string;
  headers: Headers;
  body: Record<string, unknown>;
}

function setup(upstreamResponse: () => Response = () => anthropicOk()) {
  const calls: Captured[] = [];
  const kv = new FakeKV();
  const env: Env = { ...baseEnv(), ANTHROPIC_API_KEY: "sk-test-key", USAGE: asKv(kv) };
  const deps: Deps = {
    auth: authDeps(),
    now: () => new Date("2026-10-26T10:00:00Z"),
    upstream: async (req: Request) => {
      calls.push({ url: req.url, headers: req.headers, body: await req.json() });
      return upstreamResponse();
    },
  };
  return { calls, kv, env, deps };
}

function anthropicOk(usage: Record<string, number> = { input_tokens: 100, output_tokens: 50 }) {
  return new Response(JSON.stringify({ id: "msg_1", type: "message", content: [{ type: "text", text: "hi" }], usage }), {
    status: 200,
    headers: { "content-type": "application/json" },
  });
}

async function call(s: ReturnType<typeof setup>, body: unknown, headers: Record<string, string> = {}) {
  const token = await signToken(goodClaims());
  return handle(
    new Request("https://worker/v1/messages", {
      method: "POST",
      headers: { Authorization: `Bearer ${token}`, "content-type": "application/json", ...headers },
      body: typeof body === "string" ? body : JSON.stringify(body),
    }),
    s.env,
    s.deps,
  );
}

const chat = { model: "ignored", max_tokens: 500, messages: [{ role: "user", content: "hello" }] };

describe("forwarding to Anthropic", () => {
  it("sends the request to the Messages API with the server's key and version", async () => {
    const s = setup();
    const res = await call(s, chat);
    expect(res.status).toBe(200);
    expect(s.calls).toHaveLength(1);
    expect(s.calls[0].url).toBe("https://api.anthropic.com/v1/messages");
    expect(s.calls[0].headers.get("x-api-key")).toBe("sk-test-key");
    expect(s.calls[0].headers.get("anthropic-version")).toBe("2023-06-01");
  });

  it("never forwards the user's Google token", async () => {
    const s = setup();
    await call(s, chat);
    expect(s.calls[0].headers.get("authorization")).toBeNull();
  });

  it("returns Anthropic's body unchanged", async () => {
    const s = setup();
    const body = await (await call(s, chat)).json();
    expect(body).toMatchObject({ id: "msg_1", content: [{ text: "hi" }] });
  });

  it("passes the rest of the request (messages, system, tools) through", async () => {
    const s = setup();
    await call(s, { ...chat, system: [{ type: "text", text: "rules" }], tools: [{ name: "get_day" }] });
    expect(s.calls[0].body).toMatchObject({ system: [{ text: "rules" }], tools: [{ name: "get_day" }], messages: chat.messages });
  });
});

describe("model routing", () => {
  it("uses sonnet for chat by default, whatever model the client asks for", async () => {
    const s = setup();
    await call(s, chat);
    expect(s.calls[0].body.model).toBe("claude-sonnet-5-5");
  });

  it("uses sonnet for replans and haiku for scheduled runs", async () => {
    const replan = setup();
    await call(replan, chat, { "X-Planner-Purpose": "replan" });
    expect(replan.calls[0].body.model).toBe("claude-sonnet-5-5");

    const scheduled = setup();
    await call(scheduled, chat, { "X-Planner-Purpose": "scheduled" });
    expect(scheduled.calls[0].body.model).toBe("claude-haiku-5-5");
  });

  it("rejects an unknown purpose", async () => {
    const s = setup();
    const res = await call(s, chat, { "X-Planner-Purpose": "free-for-all" });
    expect(res.status).toBe(400);
    expect(s.calls).toHaveLength(0);
  });

  it("lets the models be configured", async () => {
    const s = setup();
    s.env.MODEL_CHAT = "custom-chat";
    await call(s, chat);
    expect(s.calls[0].body.model).toBe("custom-chat");
  });
});

describe("request limits", () => {
  it("clamps max_tokens and turns streaming off", async () => {
    const s = setup();
    await call(s, { ...chat, max_tokens: 100_000, stream: true });
    expect(s.calls[0].body.max_tokens).toBe(2048);
    expect(s.calls[0].body.stream).toBe(false);
  });

  it("fills in max_tokens when the client leaves it out", async () => {
    const s = setup();
    await call(s, { messages: chat.messages });
    expect(s.calls[0].body.max_tokens).toBe(1024);
  });

  it("rejects a body that is not a JSON object with messages", async () => {
    const s = setup();
    expect((await call(s, "not json")).status).toBe(400);
    expect((await call(s, [1, 2])).status).toBe(400);
    expect((await call(s, { model: "x" })).status).toBe(400);
    expect(s.calls).toHaveLength(0);
  });

  it("rejects an oversized request", async () => {
    const s = setup();
    const huge = { ...chat, messages: [{ role: "user", content: "x".repeat(300_000) }] };
    expect((await call(s, huge)).status).toBe(413);
    expect(s.calls).toHaveLength(0);
  });
});

describe("upstream failures", () => {
  it("does not blame the user's sign-in when Anthropic rejects the server's key", async () => {
    const s = setup(() => new Response("{}", { status: 401 }));
    const res = await call(s, chat);
    expect(res.status).toBe(502);
    expect(await res.json()).toMatchObject({ error: expect.stringContaining("upstream") });
  });

  it("maps upstream server errors to 502", async () => {
    expect((await call(setup(() => new Response("{}", { status: 529 })), chat)).status).toBe(502);
    expect((await call(setup(() => new Response("{}", { status: 500 })), chat)).status).toBe(502);
  });

  it("passes rate limits and bad requests through", async () => {
    expect((await call(setup(() => new Response("{}", { status: 429 })), chat)).status).toBe(429);
    expect((await call(setup(() => new Response("{}", { status: 400 })), chat)).status).toBe(400);
  });

  it("reports a network failure as 502", async () => {
    const s = setup();
    s.deps.upstream = async () => {
      throw new Error("network down");
    };
    expect((await call(s, chat)).status).toBe(502);
  });
});
