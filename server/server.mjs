// Mealquo API: dzienniki w MySQL. Wywołuje je tylko Worker Cloudflare (nagłówek X-Mealquo-Backend).
// Worker sprawdza e-mail i hasło i przekazuje tu gotowe skróty: id = sha256(e-mail), auth = sha256("auth:" + hash hasła).
// Słucha tylko na 127.0.0.1; z zewnątrz przez nginx: https://erp.interlook.pl/mealquo-api/
import http from "node:http";
import crypto from "node:crypto";
import mysql from "mysql2/promise";
import { v2 } from "./v2.mjs";

const env = process.env;
const MAX_BODY = 6 * 1024 * 1024;
const pool = mysql.createPool({
  host: env.DB_HOST || "localhost", user: env.DB_USER, password: env.DB_PASSWORD, database: env.DB_NAME,
  connectionLimit: 5, charset: "utf8mb4",
});

const route2 = v2(pool);
setInterval(() => pool.query("DELETE FROM login_fails WHERE at < NOW() - INTERVAL 1 DAY").catch(() => {}), 3600 * 1000);

function same(a, b) {
  const x = Buffer.from(String(a || "")), y = Buffer.from(String(b || ""));
  return x.length === y.length && crypto.timingSafeEqual(x, y);
}

function reply(res, status, data) {
  res.writeHead(status, { "Content-Type": "application/json; charset=utf-8", "Cache-Control": "no-store" });
  res.end(JSON.stringify(data));
}

async function journal(body) {
  const id = String(body.id || ""), auth = String(body.auth || "");
  if (!/^[0-9a-f]{64}$/.test(id) || !/^[0-9a-f]{64}$/.test(auth)) return [400, { error: "bad" }];
  const [rows] = await pool.query("SELECT auth, journal FROM journals WHERE id = ?", [id]);
  const saved = rows[0];
  if (saved && !same(saved.auth, auth)) return [403, { error: "auth" }];
  if (body.action === "pull") return [200, saved ? { found: true, journal: saved.journal } : { found: false }];
  if (body.action === "push") {
    const text = typeof body.journal === "string" ? body.journal : JSON.stringify(body.journal || {});
    if (text.length > MAX_BODY) return [413, { error: "size" }];
    await pool.query("INSERT INTO journals (id, auth, journal) VALUES (?, ?, ?) ON DUPLICATE KEY UPDATE journal = VALUES(journal)", [id, auth, text]);
    return [200, { ok: true }];
  }
  if (body.action === "delete") {
    if (saved) await pool.query("DELETE FROM journals WHERE id = ?", [id]);
    return [200, { ok: true }];
  }
  return [400, { error: "bad" }];
}

const server = http.createServer((req, res) => {
  if (req.method === "GET" && req.url === "/health") return reply(res, 200, { ok: true });
  const isV2 = req.url.startsWith("/v2/");
  if (req.method !== "POST" || (req.url !== "/journal" && !isV2)) return reply(res, 404, { error: "missing" });
  if (!env.BACKEND_TOKEN || !same(req.headers["x-mealquo-backend"], env.BACKEND_TOKEN)) return reply(res, 403, { error: "token" });
  let size = 0;
  const chunks = [];
  req.on("data", (c) => {
    size += c.length;
    if (size > MAX_BODY) { reply(res, 413, { error: "size" }); req.destroy(); return; }
    chunks.push(c);
  });
  req.on("end", async () => {
    if (res.writableEnded) return;
    try {
      const body = JSON.parse(Buffer.concat(chunks).toString("utf8") || "{}");
      const [status, data] = isV2 ? await route2(req.url, body, req.headers) : await journal(body);
      reply(res, status, data);
    } catch (e) {
      console.error(req.url, e.message);
      reply(res, 500, { error: "server" });
    }
  });
});

server.listen(+env.PORT || 8790, "127.0.0.1", () => console.log("mealquo-api na 127.0.0.1:" + (env.PORT || 8790)));
