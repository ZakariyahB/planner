const JWKS_URL = "https://www.googleapis.com/oauth2/v3/certs";
const ISSUERS = ["https://accounts.google.com", "accounts.google.com"];

export interface AuthConfig {
  allowedEmail: string;
  clientId: string;
  /** Injectable for tests; defaults to Google's published signing keys. */
  fetchJwks?: () => Promise<JsonWebKey[]>;
  nowSeconds?: () => number;
}

export class AuthError extends Error {}

function b64urlToBytes(s: string): Uint8Array {
  const pad = "=".repeat((4 - (s.length % 4)) % 4);
  const bin = atob(s.replace(/-/g, "+").replace(/_/g, "/") + pad);
  return Uint8Array.from(bin, (c) => c.charCodeAt(0));
}

function parseJson(bytes: Uint8Array): Record<string, unknown> {
  try {
    return JSON.parse(new TextDecoder().decode(bytes));
  } catch {
    throw new AuthError("malformed token");
  }
}

async function defaultJwks(): Promise<JsonWebKey[]> {
  const res = await fetch(JWKS_URL, { cf: { cacheTtl: 3600, cacheEverything: true } });
  if (!res.ok) throw new AuthError("cannot fetch signing keys");
  return ((await res.json()) as { keys: JsonWebKey[] }).keys;
}

/** Verifies a Google ID token and returns its email. Throws AuthError on any failure. */
export async function verifyIdToken(token: string, cfg: AuthConfig): Promise<string> {
  const parts = token.split(".");
  if (parts.length !== 3) throw new AuthError("malformed token");
  const [h, p, s] = parts;
  const header = parseJson(b64urlToBytes(h));
  if (header.alg !== "RS256") throw new AuthError("unsupported alg");

  const keys = await (cfg.fetchJwks ?? defaultJwks)();
  const jwk = keys.find((k) => (k as { kid?: string }).kid === header.kid);
  if (!jwk) throw new AuthError("unknown signing key");

  const key = await crypto.subtle.importKey(
    "jwk",
    jwk,
    { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" },
    false,
    ["verify"],
  );
  const valid = await crypto.subtle.verify(
    "RSASSA-PKCS1-v1_5",
    key,
    b64urlToBytes(s),
    new TextEncoder().encode(`${h}.${p}`),
  );
  if (!valid) throw new AuthError("bad signature");

  const claims = parseJson(b64urlToBytes(p));
  const now = (cfg.nowSeconds ?? (() => Math.floor(Date.now() / 1000)))();
  if (typeof claims.exp !== "number" || claims.exp <= now) throw new AuthError("expired");
  if (!ISSUERS.includes(claims.iss as string)) throw new AuthError("bad issuer");
  if (claims.aud !== cfg.clientId) throw new AuthError("bad audience");
  if (claims.email_verified !== true) throw new AuthError("email not verified");
  const email = String(claims.email ?? "").toLowerCase();
  if (email !== cfg.allowedEmail.toLowerCase()) throw new AuthError("email not allowed");
  return email;
}
