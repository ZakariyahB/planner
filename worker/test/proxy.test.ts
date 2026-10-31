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

describe("the model the app names", () => {
  it("is used when it is one the cap can price", async () => {
    const s = setup();
    await call(s, chat, { "X-Planner-Model": "claude-opus-5-5" });
    expect(s.calls[0].body.model).toBe("claude-opus-5-5");
  });

  it("wins over the purpose's default", async () => {
    const s = setup();
    await call(s, chat, { "X-Planner-Purpose": "scheduled", "X-Planner-Model": "claude-sonnet-5-5" });
    expect(s.calls[0].body.model).toBe("claude-sonnet-5-5");
  });

  it("may be a configured override", async () => {
    const s = setup();
    s.env.MODEL_CHAT = "custom-chat";
    await call(s, chat, { "X-Planner-Model": "custom-chat" });
    expect(s.calls[0].body.model).toBe("custom-chat");
  });

  it("is refused when unknown, before anything is spent", async () => {
    const s = setup();
    const res = await call(s, chat, { "X-Planner-Model": "claude-something-expensive" });
    expect(res.status).toBe(400);
    expect(s.calls).toHaveLength(0);
  });
});

describe("server-side refusal fallback", () => {
  it("is requested for sonnet and opus 5.5", async () => {
    for (const model of ["claude-sonnet-5-5", "claude-opus-5-5"]) {
      const s = setup();
      await call(s, chat, { "X-Planner-Model": model });
      expect(s.calls[0].headers.get("anthropic-beta")).toBe("server-side-fallback-2026-07-01");
      expect(s.calls[0].body.fallbacks).toBe("default");
    }
  });

  it("is not requested for haiku, or when turned off", async () => {
    const haiku = setup();
    await call(haiku, chat, { "X-Planner-Purpose": "scheduled" });
    expect(haiku.calls[0].headers.get("anthropic-beta")).toBeNull();
    expect(haiku.calls[0].body.fallbacks).toBeUndefined();

    const off = setup();
    off.env.DISABLE_FALLBACKS = "1";
    await call(off, chat);
    expect(off.calls[0].headers.get("anthropic-beta")).toBeNull();
    expect(off.calls[0].body.fallbacks).toBeUndefined();
  });

  it("cannot be chosen by the client", async () => {
    const s = setup();
    s.env.DISABLE_FALLBACKS = "1";
    await call(s, { ...chat, fallbacks: [{ model: "claude-fable-5-1" }] });
    expect(s.calls[0].body.fallbacks).toBeUndefined();
  });

  it("prices the reply at the model that answered", async () => {
    const s = setup(
      () =>
        new Response(
          JSON.stringify({ model: "claude-sonnet-5", content: [], usage: { input_tokens: 1_000_000, output_tokens: 0 } }),
          { status: 200 },
        ),
    );
    const usage = JSON.parse((await call(s, chat, { "X-Planner-Model": "claude-opus-5-5" })).headers.get("X-Planner-Usage")!);
    expect(usage.model).toBe("claude-sonnet-5");
    expect(usage.costGbp).toBeCloseTo(2 * 0.8, 4);
  });
});

describe("request limits", () => {
  it("clamps max_tokens and turns streaming off", async () => {
    const s = setup();
    await call(s, { ...chat, max_tokens: 100_000, stream: true });
    expect(s.calls[0].body.max_tokens).toBe(8192);
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

describe("monthly spend cap", () => {
  const spendKey = "spend:2026-10";

  it("answers 402 and does not call Anthropic once the cap is reached", async () => {
    const s = setup();
    s.kv.store.set(spendKey, "15");
    const res = await call(s, chat);
    expect(res.status).toBe(402);
    expect(s.calls).toHaveLength(0);
  });

  it("still serves requests while under the cap", async () => {
    const s = setup();
    s.kv.store.set(spendKey, "14.99");
    expect((await call(s, chat)).status).toBe(200);
  });

  it("uses the cap from the environment", async () => {
    const s = setup();
    s.env.MONTHLY_CAP_GBP = "1";
    s.kv.store.set(spendKey, "1.5");
    expect((await call(s, chat)).status).toBe(402);
  });

  it("counts only this month's spend", async () => {
    const s = setup();
    s.kv.store.set("spend:2026-09", "99");
    expect((await call(s, chat)).status).toBe(200);
  });

  it("refuses everything if the cap is missing or invalid, rather than spending without limit", async () => {
    const s = setup();
    s.env.MONTHLY_CAP_GBP = "not a number";
    expect((await call(s, chat)).status).toBe(402);
  });
});

describe("usage reporting", () => {
  it("adds the call's cost to this month's total", async () => {
    const s = setup(() => anthropicOk({ input_tokens: 100_000, output_tokens: 10_000 }));
    await call(s, chat); // sonnet: (100k * $2 + 10k * $10) / 1M = $0.30 = about £0.24
    expect(Number(s.kv.store.get("spend:2026-10"))).toBeCloseTo(0.24, 4);
  });

  it("tells the app what the call used and cost, and the month so far", async () => {
    const s = setup(() => anthropicOk({ input_tokens: 100_000, output_tokens: 10_000 }));
    s.kv.store.set("spend:2026-10", "1");
    const usage = JSON.parse((await call(s, chat)).headers.get("X-Planner-Usage")!);
    expect(usage).toMatchObject({ model: "claude-sonnet-5-5", inputTokens: 100_000, outputTokens: 10_000 });
    expect(usage.costGbp).toBeCloseTo(0.24, 4);
    expect(usage.monthGbp).toBeCloseTo(1.24, 4);
  });

  it("prices scheduled runs at the cheaper model's rate", async () => {
    const s = setup(() => anthropicOk({ input_tokens: 100_000, output_tokens: 10_000 }));
    const usage = JSON.parse((await call(s, chat, { "X-Planner-Purpose": "scheduled" })).headers.get("X-Planner-Usage")!);
    expect(usage.model).toBe("claude-haiku-5-5");
    expect(usage.costGbp).toBeCloseTo(0.012, 4); // (100k * $0.10 + 10k * $0.50) / 1M * 0.8
  });

  it("counts cache reads and writes", async () => {
    const s = setup(() => anthropicOk({ input_tokens: 0, output_tokens: 0, cache_creation_input_tokens: 1_000_000, cache_read_input_tokens: 1_000_000 }));
    const usage = JSON.parse((await call(s, chat)).headers.get("X-Planner-Usage")!);
    expect(usage).toMatchObject({ cacheWriteTokens: 1_000_000, cacheReadTokens: 1_000_000 });
    expect(usage.costGbp).toBeCloseTo((2.5 + 0.2) * 0.8, 4);
  });

  it("records nothing for a failed call", async () => {
    const s = setup(() => new Response("{}", { status: 429 }));
    const res = await call(s, chat);
    expect(res.headers.get("X-Planner-Usage")).toBeNull();
    expect(s.kv.store.size).toBe(0);
  });

  it("copes with a response that has no usage", async () => {
    const s = setup(() => new Response(JSON.stringify({ id: "msg_2" }), { status: 200 }));
    const res = await call(s, chat);
    expect(res.status).toBe(200);
    expect(res.headers.get("X-Planner-Usage")).toBeNull();
  });

  it("stores only the running total, never what was said", async () => {
    const s = setup();
    await call(s, { ...chat, messages: [{ role: "user", content: "my secret plans" }] });
    expect([...s.kv.store.keys()]).toEqual(["spend:2026-10"]);
    expect(JSON.stringify([...s.kv.store.values()])).not.toContain("secret");
  });
});
