import type { Env } from "../src/index";

export const CLIENT_ID = "web-client.apps.googleusercontent.com";
export const EMAIL = "bello.zakariyah20@gmail.com";
export const NOW = 1_800_000_000;

let privateKey: CryptoKey;
let publicJwk: JsonWebKey & { kid: string };

const b64url = (data: ArrayBuffer | string) => {
  const bytes = typeof data === "string" ? new TextEncoder().encode(data) : new Uint8Array(data);
  return btoa(String.fromCharCode(...bytes)).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
};

/** Generates the signing key pair used by [signToken]; call once in beforeAll. */
export async function setupKeys(): Promise<void> {
  const pair = (await crypto.subtle.generateKey(
    { name: "RSASSA-PKCS1-v1_5", modulusLength: 2048, publicExponent: new Uint8Array([1, 0, 1]), hash: "SHA-256" },
    true,
    ["sign", "verify"],
  )) as CryptoKeyPair;
  privateKey = pair.privateKey;
  publicJwk = { ...((await crypto.subtle.exportKey("jwk", pair.publicKey)) as JsonWebKey), kid: "k1" };
}

export async function signToken(claims: Record<string, unknown>, kid = "k1"): Promise<string> {
  const h = b64url(JSON.stringify({ alg: "RS256", kid }));
  const p = b64url(JSON.stringify(claims));
  const sig = await crypto.subtle.sign("RSASSA-PKCS1-v1_5", privateKey, new TextEncoder().encode(`${h}.${p}`));
  return `${h}.${p}.${b64url(sig)}`;
}

export const b64urlOf = b64url;

export const goodClaims = () => ({
  iss: "https://accounts.google.com",
  aud: CLIENT_ID,
  email: EMAIL,
  email_verified: true,
  exp: NOW + 3600,
});

/** Auth dependencies that use the test key instead of Google's published keys. */
export const authDeps = () => ({ fetchJwks: async () => [publicJwk], nowSeconds: () => NOW });

export const baseEnv = (): Env =>
  ({ ALLOWED_EMAIL: EMAIL, GOOGLE_WEB_CLIENT_ID: CLIENT_ID, MONTHLY_CAP_GBP: "15" }) as Env;
