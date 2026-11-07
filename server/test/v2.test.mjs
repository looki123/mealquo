// Test API v2 na działającej usłudze: sudo bash -c "set -a; . /etc/mealquo-api.env; node test/v2.test.mjs"
// Zakłada i usuwa konta testowe (*@example.com).

import crypto from "node:crypto";
import assert from "node:assert/strict";
const T = process.env.BACKEND_TOKEN;
const sha = (t) => crypto.createHash("sha256").update(t).digest("hex");
async function call(path, body, token) {
  const res = await fetch("http://127.0.0.1:8790" + path, { method: "POST",
    headers: { "Content-Type": "application/json", "X-Mealquo-Backend": T, ...(token ? { Authorization: "Bearer " + token } : {}) },
    body: JSON.stringify(body || {}) });
  return [res.status, await res.json()];
}
const email = "v2test-" + Date.now() + "@example.com";
let [s, d] = await call("/v2/register", { email, password: "tajne123", device: "test" });
assert.equal(s, 200); assert.ok(d.token); const tA = d.token;
[s] = await call("/v2/register", { email, password: "inne-haslo" }); assert.equal(s, 401, "zajęty e-mail innym hasłem");
[s] = await call("/v2/login", { email, password: "zle" }); assert.equal(s, 401);
[s, d] = await call("/v2/login", { email, password: "tajne123", device: "B" }); assert.equal(s, 200); const tB = d.token;
[s] = await call("/v2/sync", {}, "zly-token"); assert.equal(s, 401);
// telefon A zapisuje posiłek
[s, d] = await call("/v2/sync", { since: 0, changes: [{ kind: "meal", id: "1", data: { title: "Żurek", kcal: 300 }, updated_at: 1000 }] }, tA);
assert.equal(s, 200); assert.equal(d.applied, 1); const cA = d.cursor;
// telefon B dostaje go
[s, d] = await call("/v2/sync", { since: 0, changes: [] }, tB);
assert.deepEqual(d.changes.map((c) => c.data.title), ["Żurek"]); const cB = d.cursor;
// konflikt: B zmienia nowszą wersją, A wysyła starszą – wygrywa nowsza
await call("/v2/sync", { since: cB, changes: [{ kind: "meal", id: "1", data: { title: "Żurek z jajkiem", kcal: 380 }, updated_at: 3000 }] }, tB);
[s, d] = await call("/v2/sync", { since: cA, changes: [{ kind: "meal", id: "1", data: { title: "Żurek stary", kcal: 1 }, updated_at: 2000 }] }, tA);
assert.equal(d.changes.find((c) => c.id === "1").data.title, "Żurek z jajkiem");
// usunięcie
[s, d] = await call("/v2/sync", { since: 0, changes: [{ kind: "meal", id: "1", deleted: true, updated_at: 4000 }] }, tA);
assert.equal(d.changes.find((c) => c.id === "1").deleted, true);
[s] = await call("/v2/sync", { since: 0, changes: [{ kind: "zly", id: "x", data: 1 }] }, tA); assert.equal(s, 200);
[s, d] = await call("/v2/export", {}, tA); assert.equal(d.email, email); assert.equal(d.entries.length, 0);
// zmiana hasła
[s] = await call("/v2/password", { old: "zle", new: "nowe1234" }, tA); assert.equal(s, 401);
[s] = await call("/v2/password", { old: "tajne123", new: "nowe1234" }, tA); assert.equal(s, 200);
[s] = await call("/v2/login", { email, password: "nowe1234" }); assert.equal(s, 200);
// wylogowanie
[s] = await call("/v2/logout", {}, tB); assert.equal(s, 200);
[s] = await call("/v2/sync", {}, tB); assert.equal(s, 401);
// usunięcie konta
[s] = await call("/v2/account/delete", { password: "zle" }, tA); assert.equal(s, 401);
[s] = await call("/v2/account/delete", { password: "nowe1234" }, tA); assert.equal(s, 200);
[s] = await call("/v2/login", { email, password: "nowe1234" }); assert.equal(s, 401);
console.log("v2 konta i synchronizacja OK");

// konto z v1: dziennik w journals, hasło liczone jak w starym kliencie
const old = "v1test-" + Date.now() + "@example.com";
const hash = sha("mealquo:" + "stare123");
await call("/journal", { action: "push", id: sha(old), auth: sha("auth:" + hash), journal: JSON.stringify({ rev: 5, meals: [{ id: 11, title: "Bigos" }, { id: 12, title: "Usunięty" }], deleted: [12], workouts: [{ id: 21, name: "Bieg" }], settings: { evening: true }, user: { email: old, name: "Ola" } }) });
[s] = await call("/v2/login", { email: old, password: "zle123" }); assert.equal(s, 401);
[s, d] = await call("/v2/login", { email: old, password: "stare123" }); assert.equal(s, 200); assert.equal(d.migrated, 4);
[s, d] = await call("/v2/sync", { since: 0 }, d.token);
assert.deepEqual(d.changes.map((c) => c.kind + ":" + c.id).sort(), ["doc:settings", "doc:user", "meal:11", "workout:21"]);
[s, d] = await call("/v2/login", { email: old, password: "stare123" }); assert.equal(s, 200); assert.equal(d.migrated, 0, "drugi raz już zwykłe logowanie");
[s] = await call("/v2/account/delete", { password: "stare123" }, d.token); assert.equal(s, 200);
console.log("przeniesienie kont v1 OK");

// blokada po 10 nieudanych próbach
const lock = "lock-" + Date.now() + "@example.com";
await call("/v2/register", { email: lock, password: "tajne123" });
for (let i = 0; i < 10; i++) await call("/v2/login", { email: lock, password: "zle" + i });
[s] = await call("/v2/login", { email: lock, password: "tajne123" }); assert.equal(s, 429);
console.log("blokada zgadywania OK");

// telefon sprzed v2 zna tylko skrót hasła (hashPass), nie hasło
const dev = "legacy-" + Date.now() + "@example.com";
const devHash = sha("mealquo:" + "telefon1");
[s] = await call("/v2/login", { email: dev, legacy: "nie-skrot" }); assert.equal(s, 400);
[s, d] = await call("/v2/login", { email: dev, legacy: devHash }); assert.equal(s, 200); assert.equal(d.migrated, -1, "nowe konto ze skrótu (Android)");
[s] = await call("/v2/login", { email: dev, legacy: sha("mealquo:" + "inne") }); assert.equal(s, 401);
[s, d] = await call("/v2/login", { email: dev, password: "telefon1" }); assert.equal(s, 200, "potem hasłem – serwer zapisuje scrypt");
[s] = await call("/v2/login", { email: dev, legacy: devHash }); assert.equal(s, 200, "skrót dalej działa na starych telefonach");
[s] = await call("/v2/password", { old: "telefon1", new: "zmienione1" }, d.token); assert.equal(s, 200);
[s] = await call("/v2/login", { email: dev, legacy: devHash }); assert.equal(s, 401, "po zmianie hasła stary skrót nie działa");
[s] = await call("/v2/account/delete", { password: "zmienione1" }, d.token); assert.equal(s, 200);
// konto v1 przechodzi także skrótem (telefon zalogowany przed v2)
const old2 = "v1hash-" + Date.now() + "@example.com";
const h2 = sha("mealquo:" + "abc123");
await call("/journal", { action: "push", id: sha(old2), auth: sha("auth:" + h2), journal: JSON.stringify({ rev: 9, meals: [{ id: 1, title: "Pierogi" }] }) });
[s, d] = await call("/v2/login", { email: old2, legacy: h2 }); assert.equal(s, 200); assert.equal(d.migrated, 1);
[s] = await call("/v2/account/delete", {}, d.token); assert.equal(s, 200);
console.log("logowanie skrótem (telefony sprzed v2) OK");

// zdjęcia posiłków
const ph = "photo-" + Date.now() + "@example.com";
[s, d] = await call("/v2/register", { email: ph, password: "tajne123" }); const tP = d.token;
const jpeg = Buffer.concat([Buffer.from([0xff, 0xd8, 0xff, 0xe0]), Buffer.alloc(2000, 7)]).toString("base64");
[s] = await call("/v2/photo/put", { id: "m123", data: jpeg }, tP); assert.equal(s, 200);
[s, d] = await call("/v2/photo/get", { id: "m123" }, tP); assert.equal(s, 200); assert.equal(d.data, jpeg);
[s] = await call("/v2/photo/put", { id: "m124", data: Buffer.alloc(2000, 1).toString("base64") }, tP); assert.equal(s, 400, "tylko JPEG");
[s] = await call("/v2/photo/put", { id: "../x", data: jpeg }, tP); assert.equal(s, 400, "bez ścieżek");
[s] = await call("/v2/photo/put", { id: "m125", data: Buffer.concat([Buffer.from([0xff, 0xd8]), Buffer.alloc(2 * 1024 * 1024)]).toString("base64") }, tP); assert.equal(s, 413);
[s] = await call("/v2/photo/get", { id: "nie-ma" }, tP); assert.equal(s, 404);
[s] = await call("/v2/photo/get", { id: "m123" }); assert.equal(s, 401, "bez sesji");
[s] = await call("/v2/account/delete", { password: "tajne123" }, tP); assert.equal(s, 200);
console.log("zdjęcia OK");
