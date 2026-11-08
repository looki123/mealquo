// Logowanie Google/Apple i zgoda rodzica na prawdziwej bazie, z podstawioną weryfikacją tokenu i wysyłką e-maili:
// sudo bash -c "set -a; . /etc/mealquo-api.env; node test/social.test.mjs"   (konta *@example.com są usuwane)
import assert from "node:assert/strict";
import mysql from "mysql2/promise";
import { v2 } from "../v2.mjs";

const env = process.env;
const pool = mysql.createPool({ host: env.DB_HOST || "localhost", user: env.DB_USER, password: env.DB_PASSWORD, database: env.DB_NAME, connectionLimit: 2 });
const sent = [];
const mailer = { enabled: true, async send(to, subject, html) { sent.push({ to, subject, html }); return true; } };
const claims = {};   // token -> payload albo Error
const verify = async (token, cfg) => { const c = claims[token]; if (!c || c instanceof Error) throw c || new Error("signature"); assert.ok(cfg.aud.length); return c; };
const stamp = Date.now();
const emails = [`g-${stamp}@example.com`, `relay-${stamp}@privaterelay.example.com`.replace("privaterelay.example.com", "example.com"), `kid-${stamp}@example.com`, `parent-${stamp}@example.com`];
const cleanup = () => pool.query("DELETE FROM users WHERE email LIKE '%@example.com'");

try {
  // bez konfiguracji: 501 i config z samymi false
  const off = v2(pool, { env: {}, verify, mailer: { enabled: false, send: async () => false } });
  let [s, d] = await off("/v2/auth/config", {}, {});
  assert.deepEqual(d, { google: false, apple: false, googleClientId: "", appleServicesId: "" });
  [s] = await off("/v2/auth/google", { idToken: "x" }, {}); assert.equal(s, 501);
  [s] = await off("/v2/auth/apple", { identityToken: "x" }, {}); assert.equal(s, 501);

  const route = v2(pool, { env: { GOOGLE_CLIENT_IDS: "web.apps, android.apps", APPLE_AUDIENCES: "app.mealquo", APPLE_SERVICES_ID: "app.mealquo.web", PUBLIC_URL: "https://mealquo.test/" }, verify, mailer });
  [s, d] = await route("/v2/auth/config", {}, {});
  assert.deepEqual(d, { google: true, apple: true, googleClientId: "web.apps", appleServicesId: "app.mealquo.web" });

  // Google: nowe konto, potem to samo konto po sub, zły token
  claims.g1 = { sub: "g-sub-1", email: emails[0], email_verified: true };
  [s, d] = await route("/v2/auth/google", { idToken: "g1", device: "test" }, {});
  assert.equal(s, 200); assert.equal(d.created, true); assert.equal(d.email, emails[0]); assert.ok(d.token);
  const tokenG = d.token;
  [s, d] = await route("/v2/auth/google", { idToken: "g1" }, {});
  assert.equal(s, 200); assert.equal(d.created, false, "drugi raz to samo konto");
  claims.bad = new Error("aud");
  [s, d] = await route("/v2/auth/google", { idToken: "bad" }, {}); assert.equal(s, 401); assert.equal(d.reason, "aud");
  claims.unv = { sub: "g-sub-2", email: "nowy@example.com", email_verified: false };
  [s] = await route("/v2/auth/google", { idToken: "unv" }, {}); assert.equal(s, 401, "niezweryfikowany e-mail");
  [s] = await route("/v2/auth/google", {}, {}); assert.equal(s, 400);
  // sesja działa jak po haśle
  [s] = await route("/v2/sync", { since: 0 }, { authorization: "Bearer " + tokenG }); assert.equal(s, 200);

  // konto założone hasłem, potem Google tym samym e-mailem: powiązanie, nie duplikat
  const pw = `pw-${stamp}@example.com`;
  [s, d] = await route("/v2/register", { email: pw, password: "tajne123" }, {}); assert.equal(s, 200);
  claims.g3 = { sub: "g-sub-3", email: pw, email_verified: true };
  [s, d] = await route("/v2/auth/google", { idToken: "g3" }, {}); assert.equal(s, 200); assert.equal(d.created, false);
  const [[linked]] = await pool.query("SELECT google_sub FROM users WHERE email = ?", [pw]);
  assert.equal(linked.google_sub, "g-sub-3");
  [s] = await route("/v2/login", { email: pw, password: "tajne123" }, {}); assert.equal(s, 200, "hasło dalej działa");

  // Apple: pierwszy token z ukrytym e-mailem, kolejny bez e-maila (po sub)
  claims.a1 = { sub: "001.apple", email: emails[1], email_verified: "true" };
  [s, d] = await route("/v2/auth/apple", { identityToken: "a1" }, {}); assert.equal(s, 200); assert.equal(d.created, true); assert.equal(d.email, emails[1]);
  claims.a2 = { sub: "001.apple" };
  [s, d] = await route("/v2/auth/apple", { identityToken: "a2" }, {}); assert.equal(s, 200); assert.equal(d.email, emails[1]);
  claims.a3 = { sub: "002.apple" };
  [s, d] = await route("/v2/auth/apple", { identityToken: "a3" }, {}); assert.equal(s, 400, "nowe konto Apple bez e-maila");
  console.log("logowanie Google/Apple OK");

  // zgoda rodzica
  [s, d] = await route("/v2/register", { email: emails[2], password: "tajne123" }, {}); const tK = d.token;
  const h = { authorization: "Bearer " + tK };
  [s, d] = await route("/v2/consent/status", {}, h); assert.deepEqual(d, { status: "none", mail: false, mailer: true });
  [s] = await route("/v2/consent/request", { parentEmail: "zly" }, h); assert.equal(s, 400);
  [s] = await route("/v2/consent/request", { parentEmail: emails[2] }, h); assert.equal(s, 400, "rodzic to nie ten sam adres");
  [s, d] = await route("/v2/consent/request", { parentEmail: emails[3], lang: "pl" }, h);
  assert.equal(s, 200); assert.equal(d.status, "pending"); assert.equal(d.mail, true);
  assert.equal(sent.length, 1); assert.equal(sent[0].to, emails[3]); assert.match(sent[0].subject, /zgod/i);
  const link = /https:\/\/mealquo\.test\/consent\?t=([A-Za-z0-9_-]+)/.exec(sent[0].html);
  assert.ok(link, "link w mailu");
  [s, d] = await route("/v2/consent/status", {}, h); assert.equal(d.status, "pending"); assert.equal(d.parentEmail, emails[3]);
  [s] = await route("/v2/consent/confirm", { token: "A".repeat(30) }, {}); assert.equal(s, 404);
  [s] = await route("/v2/consent/confirm", { token: "zly" }, {}); assert.equal(s, 400);
  [s, d] = await route("/v2/consent/confirm", { token: link[1] }, {}); assert.equal(s, 200);
  [s, d] = await route("/v2/consent/status", {}, h); assert.equal(d.status, "confirmed");
  [s] = await route("/v2/consent/confirm", { token: link[1] }, {}); assert.equal(s, 200, "ponowne kliknięcie nie szkodzi");
  // bez Brevo: zapis zostaje, mail=false
  [s, d] = await off("/v2/consent/request", { parentEmail: emails[3] }, h); assert.equal(s, 200); assert.equal(d.mail, false);
  [s, d] = await off("/v2/consent/status", {}, h); assert.equal(d.status, "pending"); assert.equal(d.mailer, false);
  // usunięcie konta kasuje zgodę (klucz obcy)
  [s] = await route("/v2/account/delete", { password: "tajne123" }, h); assert.equal(s, 200);
  const [[left]] = await pool.query("SELECT COUNT(*) AS n FROM parent_consents pc JOIN users u ON u.id = pc.user_id WHERE u.email = ?", [emails[2]]);
  assert.equal(left.n, 0);
  console.log("zgoda rodzica OK");
} finally {
  await cleanup();
  await pool.end();
}
