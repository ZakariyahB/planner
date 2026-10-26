import { beforeAll, describe, expect, it } from "vitest";
import { handle, type Env } from "../src/index";

const CLIENT_ID = "web-client.apps.googleusercontent.com";
const EMAIL = "bello.zakariyah20@gmail.com";
const NOW = 1_800_000_000;

const env = {
  ALLOWED_EMAIL: EMAIL,
  GOOGLE_WEB_CLIENT_ID: CLIENT_ID,
  MONTHLY_CAP_GBP: "15",
} as Env;

let privateKey: CryptoKey;
let publicJwk: JsonWebKey & { kid: string };

const b64url = (data: ArrayBuffer | string) => {
  const bytes = typeof data === "string" ? new TextEncoder().encode(data) : new Uint8Array(data);
  return btoa(String.fromCharCode(...bytes)).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
};

async function sign(claims: Record<string, unknown>, kid = "k1"): Promise<string> {
  const h = b64url(JSON.stringify({ alg: "RS256", kid }));
  const p = b64url(JSON.stringify(claims));
  const sig = await crypto.subtle.sign("RSASSA-PKCS1-v1_5", privateKey, new TextEncoder().encode(`${h}.${p}`));
  return `${h}.${p}.${b64url(sig)}`;
}

const goodClaims = () => ({
  iss: "https://accounts.google.com",
  aud: CLIENT_ID,
  email: EMAIL,
  email_verified: true,
  exp: NOW + 3600,
});

const deps = { auth: { fetchJwks: async () => [publicJwk], nowSeconds: () => NOW } };

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

beforeAll(async () => {
  const pair = (await crypto.subtle.generateKey(
    { name: "RSASSA-PKCS1-v1_5", modulusLength: 2048, publicExponent: new Uint8Array([1, 0, 1]), hash: "SHA-256" },
    true,
    ["sign", "verify"],
  )) as CryptoKeyPair;
  privateKey = pair.privateKey;
  publicJwk = { ...((await crypto.subtle.exportKey("jwk", pair.publicKey)) as JsonWebKey), kid: "k1" };
});

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
    expect((await post(await sign({ ...goodClaims(), email: "someone@else.com" }))).status).toBe(401);
  });

  it("rejects an unverified email", async () => {
    expect((await post(await sign({ ...goodClaims(), email_verified: false }))).status).toBe(401);
  });

  it("rejects the wrong audience", async () => {
    expect((await post(await sign({ ...goodClaims(), aud: "other" }))).status).toBe(401);
  });

  it("rejects an expired token", async () => {
    expect((await post(await sign({ ...goodClaims(), exp: NOW - 1 }))).status).toBe(401);
  });

  it("rejects a token signed with an unknown key id", async () => {
    expect((await post(await sign(goodClaims(), "other"))).status).toBe(401);
  });

  it("rejects a tampered payload", async () => {
    const [h, , s] = (await sign(goodClaims())).split(".");
    const forged = b64url(JSON.stringify({ ...goodClaims(), email: "someone@else.com" }));
    expect((await post(`${h}.${forged}.${s}`)).status).toBe(401);
  });

  it("accepts a valid token for the allowed email (case-insensitive)", async () => {
    const res = await post(await sign({ ...goodClaims(), email: EMAIL.toUpperCase() }));
    expect(res.status).toBe(501); // authenticated; forwarding arrives in M4
  });
});
