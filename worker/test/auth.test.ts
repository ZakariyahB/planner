import { beforeAll, describe, expect, it } from "vitest";
import { handle } from "../src/index";
import { authDeps, b64urlOf, baseEnv, EMAIL, goodClaims, NOW, setupKeys, signToken } from "./token";

const env = baseEnv();
const deps = { auth: authDeps() };

const post = (token?: string) =>
  handle(
    new Request("https://w/v1/messages", {
      method: "POST",
      headers: token ? { Authorization: `Bearer ${token}` } : {},
      body: "{}",
    }),
    env,
    deps,
  );

beforeAll(setupKeys);

describe("GET /health", () => {
  it("returns ok", async () => {
    const res = await handle(new Request("https://w/health"), env);
    expect(res.status).toBe(200);
    expect(await res.text()).toBe("ok");
  });
});

describe("POST /v1/messages auth", () => {
  it("rejects a missing token", async () => {
    expect((await post()).status).toBe(401);
  });

  it("rejects garbage", async () => {
    expect((await post("not.a.jwt")).status).toBe(401);
  });

  it("rejects the wrong email", async () => {
    expect((await post(await signToken({ ...goodClaims(), email: "someone@else.com" }))).status).toBe(401);
  });

  it("rejects an unverified email", async () => {
    expect((await post(await signToken({ ...goodClaims(), email_verified: false }))).status).toBe(401);
  });

  it("rejects the wrong audience", async () => {
    expect((await post(await signToken({ ...goodClaims(), aud: "other" }))).status).toBe(401);
  });

  it("rejects an expired token", async () => {
    expect((await post(await signToken({ ...goodClaims(), exp: NOW - 1 }))).status).toBe(401);
  });

  it("rejects a token signed with an unknown key id", async () => {
    expect((await post(await signToken(goodClaims(), "other"))).status).toBe(401);
  });

  it("rejects a tampered payload", async () => {
    const [h, , s] = (await signToken(goodClaims())).split(".");
    const forged = b64urlOf(JSON.stringify({ ...goodClaims(), email: "someone@else.com" }));
    expect((await post(`${h}.${forged}.${s}`)).status).toBe(401);
  });

  it("lets a valid token for the allowed email through (case-insensitive) to the next check", async () => {
    const res = await post(await signToken({ ...goodClaims(), email: EMAIL.toUpperCase() }));
    expect(res.status).not.toBe(401);
  });
});
