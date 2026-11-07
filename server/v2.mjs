// Mealquo API v2: konta na serwerze (scrypt, tokeny sesji) i synchronizacja wpisów (posiłki, treningi, ustawienia).
// Woła je tylko Worker Cloudflare (/api/v2/* -> /v2/*), który dokłada X-Mealquo-Backend i przekazuje Authorization.
import crypto from "node:crypto";
import fs from "node:fs/promises";
import path from "node:path";
import { makeVerifier, idList, GOOGLE, APPLE } from "./auth.mjs";
import { makeMailer, consentMail } from "./mail.mjs";

const KINDS = new Set(["meal", "workout", "doc"]);
const DOCS = ["likes", "pairs", "points", "round", "seenIdeas", "pending", "settings", "taste", "plans", "user"];
const MAX_ENTRY = 200 * 1024;
const MAX_CHANGES = 500;
const FAIL_LIMIT = 10;          // nieudanych logowań na e-mail
const FAIL_WINDOW_MIN = 15;     // w ciągu tylu minut
const PHOTO_DIR = process.env.PHOTO_DIR || "/var/lib/mealquo/photos";
const MAX_PHOTO = 1536 * 1024;          // jedno zdjęcie (aplikacja wysyła ~1280 px)
const PHOTO_QUOTA = 1024 * 1024 * 1024; // na konto

const sha256 = (text) => crypto.createHash("sha256").update(text).digest("hex");

function scryptHash(password) {
  const salt = crypto.randomBytes(16);
  const hash = crypto.scryptSync(password, salt, 32, { N: 16384, r: 8, p: 1 });
  return `scrypt$16384$8$1$${salt.toString("base64")}$${hash.toString("base64")}`;
}

function scryptCheck(password, stored) {
  const [kind, n, r, p, salt, hash] = String(stored || "").split("$");
  if (kind !== "scrypt") return false;
  const want = Buffer.from(hash, "base64");
  const got = crypto.scryptSync(password, Buffer.from(salt, "base64"), want.length, { N: +n, r: +r, p: +p });
  return crypto.timingSafeEqual(want, got);
}

// stary klient liczył w telefonie sha256("mealquo:" + hasło), serwer v1 trzymał sha256("auth:" + to).
// Telefony zalogowane przed v2 znają tylko ten skrót (legacy), nie hasło – logują się nim bez pytania o hasło.
const legacyAuth = (password) => sha256("auth:" + sha256("mealquo:" + password));
const legacyFromHash = (hash) => sha256("auth:" + hash);

function cleanEmail(email) {
  const e = String(email || "").trim().toLowerCase();
  return e.length <= 255 && /^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(e) ? e : "";
}

// opts (testy): verify – weryfikacja tokenów, mailer – wysyłka e-maili, env – konfiguracja
export function v2(pool, opts = {}) {
  const env = opts.env || process.env;
  const verify = opts.verify || makeVerifier();
  const mailer = opts.mailer || makeMailer(env);
  const googleIds = idList(env.GOOGLE_CLIENT_IDS);
  const appleAuds = idList(env.APPLE_AUDIENCES);
  const publicUrl = String(env.PUBLIC_URL || "https://mealquo.mealquo.workers.dev").replace(/\/$/, "");

  async function session(userId, device) {
    const token = crypto.randomBytes(32).toString("base64url");
    await pool.query("INSERT INTO sessions (token_hash, user_id, device) VALUES (?, ?, ?)", [sha256(token), userId, String(device || "").slice(0, 64)]);
    await pool.query("UPDATE users SET last_login = NOW() WHERE id = ?", [userId]);
    return token;
  }

  async function userOf(authHeader) {
    const m = /^Bearer (\S+)$/.exec(String(authHeader || ""));
    if (!m) return null;
    const [rows] = await pool.query(
      "SELECT u.id, u.email FROM sessions s JOIN users u ON u.id = s.user_id WHERE s.token_hash = ?", [sha256(m[1])]);
    if (!rows[0]) return null;
    pool.query("UPDATE sessions SET last_used = NOW() WHERE token_hash = ? AND last_used < NOW() - INTERVAL 1 HOUR", [sha256(m[1])]).catch(() => {});
    return rows[0];
  }

  async function tooManyFails(email) {
    const [rows] = await pool.query("SELECT COUNT(*) AS n FROM login_fails WHERE email = ? AND at > NOW() - INTERVAL ? MINUTE", [email, FAIL_WINDOW_MIN]);
    return rows[0].n >= FAIL_LIMIT;
  }

  // dziennik z v1 (jeden JSON) -> wpisy v2
  async function importLegacy(userId, text) {
    let book = {};
    try { book = JSON.parse(text || "{}"); } catch (_) {}
    const at = +book.rev || Date.now();
    const now = Date.now();
    const deleted = new Set((book.deleted || []).map(String));
    const rows = [];
    for (const m of book.meals || []) if (m && m.id != null && !deleted.has(String(m.id))) rows.push(["meal", String(m.id), m]);
    for (const w of book.workouts || []) if (w && w.id != null) rows.push(["workout", String(w.id), w]);
    for (const key of DOCS) if (book[key] !== undefined && book[key] !== null) rows.push(["doc", key, book[key]]);
    for (const [kind, id, data] of rows) {
      await pool.query("INSERT IGNORE INTO entries (user_id, kind, id, data, updated_at, stamp) VALUES (?, ?, ?, ?, ?, ?)",
        [userId, kind, id.slice(0, 64), JSON.stringify(data), at, now]);
    }
    return rows.length;
  }

  async function register(body) {
    const email = cleanEmail(body.email);
    const password = String(body.password || "");
    if (!email || password.length < 6) return [400, { error: "weak" }];
    const [have] = await pool.query("SELECT id FROM users WHERE email = ?", [email]);
    if (have[0]) return login(body);   // to samo hasło = logowanie, inne = 401
    const [legacy] = await pool.query("SELECT auth FROM journals WHERE id = ?", [sha256(email)]);
    if (legacy[0] && legacy[0].auth !== legacyAuth(password)) return [409, { error: "exists" }];
    if (legacy[0]) return login(body);
    const [res] = await pool.query("INSERT INTO users (email, pass_hash, legacy_auth) VALUES (?, ?, ?)", [email, scryptHash(password), legacyAuth(password)]);
    return [200, { token: await session(res.insertId, body.device), email, created: true }];
  }

  async function login(body) {
    const email = cleanEmail(body.email);
    const password = String(body.password || "");
    const legacy = /^[0-9a-f]{64}$/.test(String(body.legacy || "")) ? legacyFromHash(body.legacy) : "";
    if (!email || (!password && !legacy)) return [400, { error: "bad" }];
    if (await tooManyFails(email)) return [429, { error: "busy" }];
    const want = password ? legacyAuth(password) : legacy;
    const [rows] = await pool.query("SELECT id, pass_hash, legacy_auth FROM users WHERE email = ?", [email]);
    let user = rows[0] || null;
    let migrated = 0;
    if (user) {
      const ok = password && user.pass_hash ? scryptCheck(password, user.pass_hash) : user.legacy_auth === want;
      if (!ok) user = null;
      else if (password && !user.pass_hash) {
        // pierwsze logowanie hasłem po przejściu skrótem: zapisujemy prawdziwy hash
        await pool.query("UPDATE users SET pass_hash = ?, legacy_auth = ? WHERE id = ?", [scryptHash(password), want, user.id]);
      }
    } else {
      // konto z v1 (dziennik w journals) albo konto zapisane dotąd tylko w telefonie (np. Android)
      const [old] = await pool.query("SELECT auth, journal FROM journals WHERE id = ?", [sha256(email)]);
      // zwykłe logowanie hasłem nie zakłada konta (od tego jest /v2/register)
      if ((old[0] && old[0].auth !== want) || (!old[0] && password)) {
        user = null;
      } else {
        const [res] = await pool.query("INSERT INTO users (email, pass_hash, legacy_auth) VALUES (?, ?, ?)",
          [email, password ? scryptHash(password) : null, want]);
        user = { id: res.insertId };
        if (old[0]) migrated = await importLegacy(user.id, old[0].journal);
        else if (!password) migrated = -1;   // nowe konto ze skrótu: dane wyśle telefon
      }
    }
    if (!user) {
      await pool.query("INSERT INTO login_fails (email) VALUES (?)", [email]);
      return [401, { error: "auth" }];
    }
    return [200, { token: await session(user.id, body.device), email, migrated }];
  }

  async function sync(user, body) {
    const since = Math.max(0, +body.since || 0);
    const changes = Array.isArray(body.changes) ? body.changes : [];
    if (changes.length > MAX_CHANGES) return [413, { error: "size" }];
    const now = Date.now();
    let applied = 0;
    for (const c of changes) {
      const kind = String(c.kind || ""), id = String(c.id || "");
      if (!KINDS.has(kind) || !id || id.length > 64) continue;
      const data = c.deleted ? null : JSON.stringify(c.data ?? null);
      if (data && data.length > MAX_ENTRY) continue;
      const at = +c.updated_at || now;
      // nowszy wygrywa (czas zmiany w telefonie)
      const [res] = await pool.query(
        `INSERT INTO entries (user_id, kind, id, data, updated_at, deleted, stamp) VALUES (?, ?, ?, ?, ?, ?, ?)
         ON DUPLICATE KEY UPDATE
           data = IF(VALUES(updated_at) >= updated_at, VALUES(data), data),
           deleted = IF(VALUES(updated_at) >= updated_at, VALUES(deleted), deleted),
           stamp = IF(VALUES(updated_at) >= updated_at, VALUES(stamp), stamp),
           updated_at = GREATEST(updated_at, VALUES(updated_at))`,
        [user.id, kind, id, data, at, c.deleted ? 1 : 0, now]);
      if (res.affectedRows) applied++;
    }
    const [rows] = await pool.query(
      "SELECT kind, id, data, updated_at, deleted, stamp FROM entries WHERE user_id = ? AND stamp > ? ORDER BY stamp LIMIT 5000",
      [user.id, since]);
    const cursor = rows.length ? Math.max(since, ...rows.map((r) => +r.stamp)) : Math.max(since, now);
    return [200, {
      cursor, applied,
      changes: rows.map((r) => ({ kind: r.kind, id: r.id, updated_at: +r.updated_at, deleted: !!r.deleted, data: r.data ? JSON.parse(r.data) : null })),
    }];
  }

  async function exportAll(user) {
    const [rows] = await pool.query("SELECT kind, id, data, updated_at FROM entries WHERE user_id = ? AND deleted = 0", [user.id]);
    return [200, { email: user.email, exported: new Date().toISOString(),
      entries: rows.map((r) => ({ kind: r.kind, id: r.id, updated_at: +r.updated_at, data: r.data ? JSON.parse(r.data) : null })) }];
  }

  async function removeAccount(user, body) {
    const [rows] = await pool.query("SELECT pass_hash FROM users WHERE id = ?", [user.id]);
    if (rows[0] && rows[0].pass_hash && !scryptCheck(String(body.password || ""), rows[0].pass_hash)) return [401, { error: "auth" }];
    await pool.query("DELETE FROM journals WHERE id = ?", [sha256(user.email)]);
    await pool.query("DELETE FROM users WHERE id = ?", [user.id]);   // sesje, wpisy i zdjęcia kaskadowo
    await fs.rm(path.join(PHOTO_DIR, String(user.id)), { recursive: true, force: true });
    return [200, { ok: true }];
  }

  // zdjęcia posiłków: plik na dysku serwera, id nadaje telefon (np. m<id posiłku>)
  function photoPath(userId, id) {
    return path.join(PHOTO_DIR, String(userId), id + ".jpg");
  }
  async function photoPut(user, body) {
    const id = String(body.id || "");
    if (!/^[A-Za-z0-9_-]{1,64}$/.test(id)) return [400, { error: "bad" }];
    const bytes = Buffer.from(String(body.data || "").replace(/^data:[^,]*,/, ""), "base64");
    if (bytes.length < 100 || bytes.length > MAX_PHOTO) return [413, { error: "size" }];
    if (bytes[0] !== 0xff || bytes[1] !== 0xd8) return [400, { error: "jpeg" }];
    const [used] = await pool.query("SELECT COALESCE(SUM(size), 0) AS n FROM photos WHERE user_id = ?", [user.id]);
    if (+used[0].n + bytes.length > PHOTO_QUOTA) return [413, { error: "quota" }];
    const file = photoPath(user.id, id);
    await fs.mkdir(path.dirname(file), { recursive: true });
    await fs.writeFile(file, bytes);
    await pool.query("INSERT INTO photos (user_id, id, size) VALUES (?, ?, ?) ON DUPLICATE KEY UPDATE size = VALUES(size)", [user.id, id, bytes.length]);
    return [200, { ok: true, id }];
  }
  async function photoGet(user, body) {
    const id = String(body.id || "");
    if (!/^[A-Za-z0-9_-]{1,64}$/.test(id)) return [400, { error: "bad" }];
    try {
      const bytes = await fs.readFile(photoPath(user.id, id));
      return [200, { id, data: bytes.toString("base64") }];
    } catch (_) {
      return [404, { error: "missing" }];
    }
  }

  // --- logowanie Google / Apple: konto po e-mailu, powiązanie po sub; sesja jak przy haśle ---
  function authConfig() {
    return [200, {
      google: googleIds.length > 0, apple: appleAuds.length > 0,
      googleClientId: googleIds[0] || "",                                    // klient webowy (ten sam jako serverClientId na Androidzie)
      appleServicesId: String(env.APPLE_SERVICES_ID || "").trim(),           // Sign in with Apple JS (web); aplikacja iOS używa bundle ID
    }];
  }
  async function socialLogin(provider, body) {
    const cfg = provider === "google" ? { jwksUrl: GOOGLE.jwks, iss: GOOGLE.iss, aud: googleIds } : { jwksUrl: APPLE.jwks, iss: APPLE.iss, aud: appleAuds };
    if (!cfg.aud.length) return [501, { error: "off" }];
    const token = String(body.idToken || body.identityToken || "");
    if (!token || token.length > 8000) return [400, { error: "bad" }];
    let claims;
    try { claims = await verify(token, cfg); }
    catch (e) { return [401, { error: "token", reason: e.message }]; }
    if (provider === "google" && !(claims.email_verified === true || claims.email_verified === "true")) return [401, { error: "token", reason: "email" }];
    const sub = String(claims.sub).slice(0, 255);
    // Apple: e-mail jest w tokenie przy pierwszym logowaniu (także ukryty @privaterelay.appleid.com); potem szukamy po sub
    const email = cleanEmail(claims.email || (provider === "apple" ? body.email : ""));
    const col = provider === "google" ? "google_sub" : "apple_sub";
    let [rows] = await pool.query(`SELECT id, email FROM users WHERE ${col} = ?`, [sub]);
    let user = rows[0];
    if (!user && email) {
      [rows] = await pool.query("SELECT id, email FROM users WHERE email = ?", [email]);
      user = rows[0];
      if (user) await pool.query(`UPDATE users SET ${col} = ? WHERE id = ? AND ${col} IS NULL`, [sub, user.id]);
    }
    let created = false;
    if (!user) {
      if (!email) return [400, { error: "email" }];
      const [res] = await pool.query(`INSERT INTO users (email, pass_hash, legacy_auth, ${col}) VALUES (?, NULL, NULL, ?)`, [email, sub]);
      user = { id: res.insertId, email };
      created = true;
      // konto z v1 pod tym e-mailem: dziennik przechodzi jak przy logowaniu hasłem
      const [old] = await pool.query("SELECT journal FROM journals WHERE id = ?", [sha256(email)]);
      if (old[0]) await importLegacy(user.id, old[0].journal);
    }
    return [200, { token: await session(user.id, body.device), email: user.email, created, provider }];
  }

  // --- zgoda rodzica (13–15 lat): prośba e-mailem przez Brevo, potwierdzenie linkiem; bez BREVO_KEY tylko zapis ---
  async function consentRequest(user, body) {
    const parent = cleanEmail(body.parentEmail);
    if (!parent) return [400, { error: "bad" }];
    if (parent === user.email) return [400, { error: "same" }];
    const token = crypto.randomBytes(24).toString("base64url");
    await pool.query(
      `INSERT INTO parent_consents (user_id, parent_email, token_hash, requested_at, confirmed_at, mail_sent) VALUES (?, ?, ?, NOW(), NULL, 0)
       ON DUPLICATE KEY UPDATE parent_email = VALUES(parent_email), token_hash = VALUES(token_hash), requested_at = NOW(), confirmed_at = NULL, mail_sent = 0`,
      [user.id, parent, sha256(token)]);
    const lang = ["pl", "uk", "en"].includes(body.lang) ? body.lang : "pl";
    const mail = consentMail(lang, user.email, `${publicUrl}/consent?t=${token}`);
    const sent = await mailer.send(parent, mail.subject, mail.html);
    if (sent) await pool.query("UPDATE parent_consents SET mail_sent = 1 WHERE user_id = ?", [user.id]);
    return [200, { status: "pending", mail: sent, parentEmail: parent, mailer: mailer.enabled }];
  }
  async function consentStatus(user) {
    const [rows] = await pool.query("SELECT parent_email, confirmed_at, mail_sent FROM parent_consents WHERE user_id = ?", [user.id]);
    const r = rows[0];
    if (!r) return [200, { status: "none", mail: false, mailer: mailer.enabled }];
    return [200, { status: r.confirmed_at ? "confirmed" : "pending", parentEmail: r.parent_email, mail: !!r.mail_sent, mailer: mailer.enabled }];
  }
  async function consentConfirm(body) {
    const token = String(body.token || "");
    if (!/^[A-Za-z0-9_-]{20,80}$/.test(token)) return [400, { error: "bad" }];
    const [res] = await pool.query("UPDATE parent_consents SET confirmed_at = COALESCE(confirmed_at, NOW()) WHERE token_hash = ?", [sha256(token)]);
    if (!res.affectedRows && !res.changedRows) {
      const [rows] = await pool.query("SELECT 1 FROM parent_consents WHERE token_hash = ?", [sha256(token)]);
      if (!rows[0]) return [404, { error: "missing" }];
    }
    return [200, { ok: true }];
  }

  async function changePassword(user, body) {
    const next = String(body.new || "");
    if (next.length < 6) return [400, { error: "weak" }];
    const [rows] = await pool.query("SELECT pass_hash FROM users WHERE id = ?", [user.id]);
    if (rows[0] && rows[0].pass_hash && !scryptCheck(String(body.old || ""), rows[0].pass_hash)) return [401, { error: "auth" }];
    await pool.query("UPDATE users SET pass_hash = ?, legacy_auth = ? WHERE id = ?", [scryptHash(next), legacyAuth(next), user.id]);
    return [200, { ok: true }];
  }

  // [status, dane] dla ścieżki /v2/...
  return async function route(path, body, headers) {
    if (path === "/v2/register") return register(body);
    if (path === "/v2/login") return login(body);
    if (path === "/v2/auth/config") return authConfig();
    if (path === "/v2/auth/google") return socialLogin("google", body);
    if (path === "/v2/auth/apple") return socialLogin("apple", body);
    if (path === "/v2/consent/confirm") return consentConfirm(body);
    const user = await userOf(headers.authorization);
    if (!user) return [401, { error: "session" }];
    if (path === "/v2/sync") return sync(user, body);
    if (path === "/v2/logout") {
      await pool.query("DELETE FROM sessions WHERE token_hash = ?", [sha256(String(headers.authorization).slice(7))]);
      return [200, { ok: true }];
    }
    if (path === "/v2/export") return exportAll(user);
    if (path === "/v2/account/delete") return removeAccount(user, body);
    if (path === "/v2/password") return changePassword(user, body);
    if (path === "/v2/consent/request") return consentRequest(user, body);
    if (path === "/v2/consent/status") return consentStatus(user);
    if (path === "/v2/photo/put") return photoPut(user, body);
    if (path === "/v2/photo/get") return photoGet(user, body);
    return [404, { error: "missing" }];
  };
}
