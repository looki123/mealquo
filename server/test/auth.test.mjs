// node test/auth.test.mjs – weryfikacja tokenów Google/Apple na własnych kluczach i wstrzykniętym JWKS
import crypto from "node:crypto";
import assert from "node:assert/strict";
import { makeVerifier, idList, GOOGLE, APPLE } from "../auth.mjs";

const { publicKey, privateKey } = crypto.generateKeyPairSync("rsa", { modulusLength: 2048 });
const other = crypto.generateKeyPairSync("rsa", { modulusLength: 2048 });
const jwk = publicKey.export({ format: "jwk" });
const b64 = (o) => Buffer.from(typeof o === "string" ? o : JSON.stringify(o)).toString("base64url");
function sign(payload, { kid = "k1", alg = "RS256", key = privateKey } = {}) {
  const head = b64({ alg, kid, typ: "JWT" }) + "." + b64(payload);
  const sig = crypto.sign("RSA-SHA256", Buffer.from(head), key).toString("base64url");
  return head + "." + sig;
}
const now = Math.floor(Date.now() / 1000);
let fetches = 0;
const verify = makeVerifier({ fetchJwks: async (url) => { fetches++; return { keys: [{ ...jwk, kid: "k1", use: "sig", alg: "RS256" }] }; } });
const google = { jwksUrl: GOOGLE.jwks, iss: GOOGLE.iss, aud: idList("web-id.apps.googleusercontent.com, android-id.apps.googleusercontent.com") };
const apple = { jwksUrl: APPLE.jwks, iss: APPLE.iss, aud: idList("app.mealquo,app.mealquo.web") };

const good = { iss: "https://accounts.google.com", aud: "web-id.apps.googleusercontent.com", sub: "123", email: "ala@gmail.com", email_verified: true, iat: now - 10, exp: now + 3600 };
const p = await verify(sign(good), google);
assert.equal(p.email, "ala@gmail.com");
assert.equal(fetches, 1);
await verify(sign(good), google);
assert.equal(fetches, 1, "JWKS z bufora");

const rejects = async (token, cfg, reason) => {
  await assert.rejects(verify(token, cfg), (e) => { assert.equal(e.message, reason); return true; }, reason);
};
await rejects(sign({ ...good, aud: "ktos-inny" }), google, "aud");
await rejects(sign({ ...good, iss: "https://evil.example" }), google, "iss");
await rejects(sign({ ...good, exp: now - 5 }), google, "exp");
await rejects(sign(good, { key: other.privateKey }), google, "signature");
await rejects(sign(good, { alg: "HS256" }), google, "alg");
await rejects(sign(good, { kid: "nieznany" }), google, "key");
assert.equal(fetches, 2, "nieznany kid wymusza odświeżenie JWKS");
await rejects("nie.token", google, "format");
await rejects(sign({ ...good, sub: "" }), google, "format");
// aud jako tablica i drugi identyfikator klienta (Android)
await verify(sign({ ...good, aud: ["android-id.apps.googleusercontent.com"] }), google);
// Apple: ukryty e-mail, email_verified jako napis
const ap = await verify(sign({ iss: "https://appleid.apple.com", aud: "app.mealquo", sub: "001.abc", email: "x7q@privaterelay.appleid.com", email_verified: "true", iat: now, exp: now + 600 }), apple);
assert.equal(ap.email, "x7q@privaterelay.appleid.com");
await rejects(sign({ iss: "https://appleid.apple.com", aud: "app.mealquo", sub: "1", exp: now + 600 }), google, "iss");
// pusta konfiguracja = nic nie przechodzi
await rejects(sign(good), { ...google, aud: [] }, "aud");
console.log("auth: ok");
