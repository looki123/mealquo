// Test szyfrowania Web Push: odszyfrowuje jak przeglądarka (RFC 8291) i sprawdza podpis VAPID.
// node test/push.test.mjs
import { encrypt, vapid, b64u, unb64u, knownEndpoint } from "../src/push.js";
import assert from "node:assert/strict";

const enc = new TextEncoder();
async function hkdf(salt, ikm, info, bytes) {
  const key = await crypto.subtle.importKey("raw", ikm, "HKDF", false, ["deriveBits"]);
  return new Uint8Array(await crypto.subtle.deriveBits({ name: "HKDF", hash: "SHA-256", salt, info }, key, bytes * 8));
}

// "telefon": klucze subskrypcji
const ua = await crypto.subtle.generateKey({ name: "ECDH", namedCurve: "P-256" }, true, ["deriveBits"]);
const uaPublic = new Uint8Array(await crypto.subtle.exportKey("raw", ua.publicKey));
const auth = crypto.getRandomValues(new Uint8Array(16));
const message = JSON.stringify({ title: "Wieczorne podsumowanie", body: "1450 / 2000 kcal ąęłóśżź" });

const body = await encrypt(message, b64u(uaPublic), b64u(auth));
const salt = body.slice(0, 16);
const rs = new DataView(body.buffer, body.byteOffset).getUint32(16);
const idlen = body[20];
const asPublic = body.slice(21, 21 + idlen);
const cipher = body.slice(21 + idlen);
assert.equal(rs, 4096); assert.equal(idlen, 65);

const asKey = await crypto.subtle.importKey("raw", asPublic, { name: "ECDH", namedCurve: "P-256" }, false, []);
const shared = new Uint8Array(await crypto.subtle.deriveBits({ name: "ECDH", public: asKey }, ua.privateKey, 256));
const ikm = await hkdf(auth, shared, new Uint8Array([...enc.encode("WebPush: info\0"), ...uaPublic, ...asPublic]), 32);
const cek = await hkdf(salt, ikm, enc.encode("Content-Encoding: aes128gcm\0"), 16);
const nonce = await hkdf(salt, ikm, enc.encode("Content-Encoding: nonce\0"), 12);
const key = await crypto.subtle.importKey("raw", cek, "AES-GCM", false, ["decrypt"]);
const plain = new Uint8Array(await crypto.subtle.decrypt({ name: "AES-GCM", iv: nonce }, key, cipher));
assert.equal(plain[plain.length - 1], 2, "ostatni bajt to separator rekordu 0x02");
assert.equal(new TextDecoder().decode(plain.slice(0, -1)), message);
console.log("szyfrowanie OK");

// VAPID: podpis ES256 weryfikowalny kluczem publicznym, aud = origin usługi
const v = await crypto.subtle.generateKey({ name: "ECDSA", namedCurve: "P-256" }, true, ["sign", "verify"]);
const jwk = await crypto.subtle.exportKey("jwk", v.privateKey);
const pub = b64u(await crypto.subtle.exportKey("raw", v.publicKey));
const header = await vapid("https://web.push.apple.com/QGuQyavXutnMH...", jwk, pub, "https://mealquo.mealquo.workers.dev");
const [, token, k] = header.match(/^vapid t=([^,]+), k=(.+)$/);
assert.equal(k, pub);
const [h, c, s] = token.split(".");
const claims = JSON.parse(new TextDecoder().decode(unb64u(c)));
assert.equal(claims.aud, "https://web.push.apple.com");
assert.ok(claims.exp > Date.now() / 1000);
const ok = await crypto.subtle.verify({ name: "ECDSA", hash: "SHA-256" }, v.publicKey, unb64u(s), enc.encode(h + "." + c));
assert.ok(ok, "podpis VAPID");
console.log("VAPID OK");

assert.ok(knownEndpoint("https://web.push.apple.com/abc"));
assert.ok(knownEndpoint("https://fcm.googleapis.com/fcm/send/abc"));
assert.ok(!knownEndpoint("https://evil.example/abc"));
assert.ok(!knownEndpoint("http://web.push.apple.com/abc"));
console.log("adresy OK");
