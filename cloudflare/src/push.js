// Web Push (RFC 8291 aes128gcm + VAPID RFC 8292) na WebCrypto, bez bibliotek.
// Działa w Workerze i w Node (test: cloudflare/test/push.test.mjs).

const enc = new TextEncoder();

export function b64u(bytes) {
  let s = "";
  for (const b of new Uint8Array(bytes)) s += String.fromCharCode(b);
  return btoa(s).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

export function unb64u(text) {
  const s = String(text).replace(/-/g, "+").replace(/_/g, "/");
  const bin = atob(s + "===".slice((s.length + 3) % 4));
  return Uint8Array.from(bin, (c) => c.charCodeAt(0));
}

function concat(...parts) {
  const out = new Uint8Array(parts.reduce((n, p) => n + p.length, 0));
  let at = 0;
  for (const p of parts) { out.set(p, at); at += p.length; }
  return out;
}

async function hkdf(salt, ikm, info, bytes) {
  const key = await crypto.subtle.importKey("raw", ikm, "HKDF", false, ["deriveBits"]);
  return new Uint8Array(await crypto.subtle.deriveBits({ name: "HKDF", hash: "SHA-256", salt, info }, key, bytes * 8));
}

// Szyfruje treść dla subskrypcji (keys.p256dh, keys.auth) – zwraca gotowe body żądania.
export async function encrypt(payload, p256dh, auth) {
  const uaPublic = unb64u(p256dh);
  const authSecret = unb64u(auth);
  const local = await crypto.subtle.generateKey({ name: "ECDH", namedCurve: "P-256" }, true, ["deriveBits"]);
  const asPublic = new Uint8Array(await crypto.subtle.exportKey("raw", local.publicKey));
  const uaKey = await crypto.subtle.importKey("raw", uaPublic, { name: "ECDH", namedCurve: "P-256" }, false, []);
  const shared = new Uint8Array(await crypto.subtle.deriveBits({ name: "ECDH", public: uaKey }, local.privateKey, 256));
  const ikm = await hkdf(authSecret, shared, concat(enc.encode("WebPush: info\0"), uaPublic, asPublic), 32);
  const salt = crypto.getRandomValues(new Uint8Array(16));
  const cek = await hkdf(salt, ikm, enc.encode("Content-Encoding: aes128gcm\0"), 16);
  const nonce = await hkdf(salt, ikm, enc.encode("Content-Encoding: nonce\0"), 12);
  const key = await crypto.subtle.importKey("raw", cek, "AES-GCM", false, ["encrypt"]);
  // jeden rekord: treść + bajt 0x02 (ostatni rekord)
  const plain = concat(enc.encode(payload), new Uint8Array([2]));
  const cipher = new Uint8Array(await crypto.subtle.encrypt({ name: "AES-GCM", iv: nonce }, key, plain));
  const header = new Uint8Array(21);
  header.set(salt, 0);
  new DataView(header.buffer).setUint32(16, 4096);
  header[20] = asPublic.length;
  return concat(header, asPublic, cipher);
}

// Nagłówek Authorization VAPID dla danego adresu push. privateJwk: klucz ES256 (JWK), publicB64: klucz publiczny (raw, base64url).
export async function vapid(endpoint, privateJwk, publicB64, subject) {
  const aud = new URL(endpoint).origin;
  const head = b64u(enc.encode(JSON.stringify({ typ: "JWT", alg: "ES256" })));
  const body = b64u(enc.encode(JSON.stringify({ aud, exp: Math.floor(Date.now() / 1000) + 12 * 3600, sub: subject })));
  const key = await crypto.subtle.importKey("jwk", privateJwk, { name: "ECDSA", namedCurve: "P-256" }, false, ["sign"]);
  const sig = await crypto.subtle.sign({ name: "ECDSA", hash: "SHA-256" }, key, enc.encode(head + "." + body));
  return "vapid t=" + head + "." + body + "." + b64u(sig) + ", k=" + publicB64;
}

// Wysyła powiadomienie; zwraca kod HTTP usługi push (404/410 = subskrypcja wygasła).
export async function send(subscription, payload, env) {
  const body = await encrypt(payload, subscription.keys.p256dh, subscription.keys.auth);
  const res = await fetch(subscription.endpoint, {
    method: "POST",
    headers: {
      Authorization: await vapid(subscription.endpoint, JSON.parse(env.VAPID_PRIVATE), env.VAPID_PUBLIC, env.VAPID_SUBJECT),
      "Content-Encoding": "aes128gcm",
      "Content-Type": "application/octet-stream",
      TTL: "3600",
      Urgency: "normal",
    },
    body,
  });
  return res.status;
}

// tylko znane usługi push (Apple, Google, Mozilla, Microsoft) – serwer nie wyśle nic pod dowolny adres
export function knownEndpoint(url) {
  try {
    const u = new URL(url);
    if (u.protocol !== "https:") return false;
    return /(^|\.)push\.apple\.com$|^fcm\.googleapis\.com$|(^|\.)push\.services\.mozilla\.com$|(^|\.)notify\.windows\.com$/.test(u.hostname);
  } catch (_) {
    return false;
  }
}
