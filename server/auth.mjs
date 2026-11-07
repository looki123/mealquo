// Weryfikacja tokenów logowania Google (ID token) i Apple (identityToken): podpis RS256 kluczem z JWKS,
// wystawca, odbiorca (identyfikatory klientów z konfiguracji) i ważność. Bez zewnętrznych bibliotek.
// makeVerifier({ fetchJwks }) – w testach podaje się własne JWKS, w usłudze pobierane są z sieci i buforowane godzinę.
import crypto from "node:crypto";

export const GOOGLE = {
  jwks: "https://www.googleapis.com/oauth2/v3/certs",
  iss: ["https://accounts.google.com", "accounts.google.com"],
};
export const APPLE = {
  jwks: "https://appleid.apple.com/auth/keys",
  iss: ["https://appleid.apple.com"],
};

const b64url = (s) => Buffer.from(String(s).replace(/-/g, "+").replace(/_/g, "/") + "===".slice((String(s).length + 3) % 4), "base64");

async function defaultFetch(url) {
  const res = await fetch(url, { signal: AbortSignal.timeout(8000) });
  if (!res.ok) throw new Error("jwks " + res.status);
  return res.json();
}

export function makeVerifier({ fetchJwks = defaultFetch, ttlMs = 3600 * 1000 } = {}) {
  const cache = new Map();   // url -> { at, keys }
  async function keys(url, force) {
    const hit = cache.get(url);
    if (!force && hit && Date.now() - hit.at < ttlMs) return hit.keys;
    const data = await fetchJwks(url);
    const list = Array.isArray(data && data.keys) ? data.keys : [];
    cache.set(url, { at: Date.now(), keys: list });
    return list;
  }
  // zwraca payload albo rzuca Error z krótkim kodem: format, alg, key, signature, iss, aud, exp
  return async function verifyIdToken(token, { jwksUrl, iss, aud }) {
    const parts = String(token || "").split(".");
    if (parts.length !== 3) throw new Error("format");
    let header, payload;
    try {
      header = JSON.parse(b64url(parts[0]).toString("utf8"));
      payload = JSON.parse(b64url(parts[1]).toString("utf8"));
    } catch (_) {
      throw new Error("format");
    }
    if (header.alg !== "RS256" || !header.kid) throw new Error("alg");
    let jwk = (await keys(jwksUrl)).find((k) => k.kid === header.kid);
    if (!jwk) jwk = (await keys(jwksUrl, true)).find((k) => k.kid === header.kid);   // rotacja kluczy
    if (!jwk || jwk.kty !== "RSA") throw new Error("key");
    const pub = crypto.createPublicKey({ key: { kty: jwk.kty, n: jwk.n, e: jwk.e }, format: "jwk" });
    const ok = crypto.verify("RSA-SHA256", Buffer.from(parts[0] + "." + parts[1]), pub, b64url(parts[2]));
    if (!ok) throw new Error("signature");
    if (!iss.includes(payload.iss)) throw new Error("iss");
    const auds = Array.isArray(payload.aud) ? payload.aud : [payload.aud];
    if (!aud.length || !auds.some((a) => aud.includes(a))) throw new Error("aud");
    const now = Math.floor(Date.now() / 1000);
    if (!(+payload.exp > now)) throw new Error("exp");
    if (payload.nbf && +payload.nbf > now + 60) throw new Error("exp");
    if (payload.iat && +payload.iat > now + 300) throw new Error("exp");
    if (!payload.sub) throw new Error("format");
    return payload;
  };
}

// lista identyfikatorów z env: "a,b , c" -> ["a","b","c"]
export const idList = (raw) => String(raw || "").split(",").map((s) => s.trim()).filter(Boolean);
