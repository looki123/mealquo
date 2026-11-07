// Mealquo na iPhone (PWA): strona z ../web oraz API z kluczami Gemini i Claude, które nie trafiają do przeglądarki.
// /api/ai        – zapytanie do Gemini (tekst, zdjęcie, nagranie, wyszukiwanie); zapas: Claude Sonnet 5.5
// /api/fix       – poprawka talerza po komentarzu: Claude Sonnet 5.5, zapas Gemini (też dla aplikacji Android)
// /api/recognize – rozpoznanie talerza w formacie starego API (zapas w app.js)
// /api/journal   – kopia dziennika w KV (push/pull po e-mailu i hashu hasła)
// /api/v2/*      – konta na serwerze i synchronizacja wpisów (MySQL na VPS, server/v2.mjs)
// /api/push      – subskrypcja Web Push i ustawienia przypomnień; cron co 5 min wysyła przypomnienia
// /api/coach     – trener AI (cele, misje, komentarz do dnia i tygodnia): Claude Sonnet 5.5, zapas Gemini; prompt składa serwer
import Anthropic from "@anthropic-ai/sdk";
import { coachPrompt, coachSchema, MAX_COACH_BODY } from "./coach.js";
import { send, knownEndpoint } from "./push.js";
import { due, localNow } from "./remind.js";

const MODELS = ["gemini-3.5-flash-lite", "gemini-flash-lite-latest", "gemini-3-flash-preview"];
const CLAUDE = "claude-sonnet-5-5";
const DEADLINE_MS = 25000;
const MAX_BODY = 8 * 1024 * 1024;
const MAX_PROMPT = 12000;
const MAX_JOURNAL = 4 * 1024 * 1024;

export default {
  async scheduled(event, env, ctx) {
    ctx.waitUntil(remindAll(env));
  },

  async fetch(req, env) {
    const url = new URL(req.url);
    if (!url.pathname.startsWith("/api/")) return env.ASSETS.fetch(req);
    if (req.method !== "POST") return json({ error: "method" }, 405);
    // z naszej strony (przeglądarka wysyła Origin przy fetch POST) albo z aplikacji Android (X-Mealquo-App)
    const origin = req.headers.get("Origin");
    const app = env.APP_TOKEN && req.headers.get("X-Mealquo-App") === env.APP_TOKEN;
    if (!app && (!origin || origin !== url.origin)) return json({ error: "origin" }, 403);
    const ip = req.headers.get("CF-Connecting-IP") || "unknown";
    if (env.LIMIT) {
      const { success } = await env.LIMIT.limit({ key: ip });
      if (!success) return json({ error: "busy" }, 429);
    }
    if (+(req.headers.get("Content-Length") || 0) > MAX_BODY) return json({ error: "size" }, 413);
    let body;
    try {
      const raw = await req.text();
      if (raw.length > MAX_BODY) return json({ error: "size" }, 413);
      if (url.pathname === "/api/coach" && raw.length > MAX_COACH_BODY) return json({ error: "size" }, 413);
      body = JSON.parse(raw);
    } catch (_) {
      return json({ error: "bad" }, 400);
    }
    if (url.pathname === "/api/ai") return ai(body, env);
    if (url.pathname === "/api/fix") return fix(body, env);
    if (url.pathname === "/api/recognize") return recognize(body, env);
    if (url.pathname === "/api/journal") return journal(body, env);
    if (url.pathname === "/api/push") return pushSave(body, env);
    if (url.pathname === "/api/coach") return coach(body, env);
    if (url.pathname.startsWith("/api/v2/") && env.BACKEND_URL) return v2(url.pathname.slice(4), body, req, env);
    return json({ error: "missing" }, 404);
  },
};

function json(data, status = 200) {
  return new Response(JSON.stringify(data), {
    status,
    headers: { "Content-Type": "application/json; charset=utf-8", "Cache-Control": "no-store" },
  });
}

const STATUS = { quota: 429, timeout: 504, offline: 502, server: 502, key: 502, bad: 502 };

async function ai(body, env) {
  const prompt = String(body.prompt || "").slice(0, MAX_PROMPT);
  if (!prompt) return json({ error: "bad" }, 400);
  const parts = [];
  if (body.image) parts.push({ inline_data: { mime_type: "image/jpeg", data: clean(body.image) } });
  if (body.audio) parts.push({ inline_data: { mime_type: audioMime(body.mime), data: clean(body.audio) } });
  parts.push({ text: prompt });
  const request = { contents: [{ role: "user", parts }] };
  if (body.search) {
    request.tools = [{ google_search: {} }];
    request.generationConfig = { temperature: 0.3, thinkingConfig: { thinkingLevel: "low" } };
  } else {
    request.generationConfig = { temperature: 0.2, responseMimeType: "application/json", thinkingConfig: { thinkingLevel: "low" } };
  }
  const out = await tryModels(request, env);
  if (out.text) return json({ text: out.text });
  // Claude nie przyjmuje nagrań, a wyszukiwanie zostaje przy Gemini
  if (!body.audio && !body.search) {
    const backup = await claude(prompt, body.image, null, env);
    if (backup.text) return json({ text: backup.text, engine: "claude" });
  }
  return json({ error: out.error }, STATUS[out.error] || 502);
}

// Poprawka po komentarzu: tu liczy się dokładność, więc najpierw Claude Sonnet 5.5, potem Gemini.
// Prompt składa serwer, więc endpoint nie służy jako ogólny dostęp do modeli.
async function fix(body, env) {
  const comment = String(body.comment || "").slice(0, 800);
  const prompt = platePrompt(comment, body.lang);
  const image = body.image ? clean(body.image) : "";
  const first = await claude(prompt, image, PLATE_SCHEMA, env);
  if (first.text) return json({ text: first.text, engine: "claude" });
  const parts = [{ text: prompt }];
  if (image) parts.unshift({ inline_data: { mime_type: "image/jpeg", data: image } });
  const out = await tryModels({
    contents: [{ role: "user", parts }],
    generationConfig: { temperature: 0.2, responseMimeType: "application/json", thinkingConfig: { thinkingLevel: "low" } },
  }, env);
  if (out.text) return json({ text: out.text, engine: "gemini" });
  return json({ error: out.error }, STATUS[out.error] || 502);
}

// Trener: dane z aplikacji (profil, ostatnie dni, waga, misje) w prompt składany tutaj, odpowiedź wg schematu.
// Najpierw Claude Sonnet 5.5 (ton i konkret), zapas Gemini w trybie JSON.
async function coach(body, env) {
  const kind = String(body.kind || "");
  const built = coachPrompt(kind, body);
  if (!built) return json({ error: "bad" }, 400);
  const schema = coachSchema(kind);
  const first = await claude(built, "", schema, env);
  if (first.text) return json({ text: first.text, engine: "claude" });
  const out = await tryModels({
    contents: [{ role: "user", parts: [{ text: built }] }],
    generationConfig: { temperature: 0.4, responseMimeType: "application/json", thinkingConfig: { thinkingLevel: "low" } },
  }, env);
  if (out.text) return json({ text: out.text, engine: "gemini" });
  return json({ error: out.error || first.error || "server" }, STATUS[out.error] || 502);
}

function hintLang(lang) {
  return lang === "uk" ? "українською" : lang === "en" ? "in English" : "po polsku";
}

// ten sam prompt co Gemini.platePrompt() w aplikacji Android
function platePrompt(comment, lang) {
  const precise = " Nazwa maksymalnie konkretna, nigdy jednym słowem. Zakazane same: mięso, danie, sałatka, zupa, ryba, kurczak, napój, makaron. "
    + "W nazwie podaj rodzaj, część, kolor, sposób przyrządzenia i dodatek albo sos, jeśli to widać. "
    + "Dobrze: pierś z kurczaka grillowana z ryżem i brokułem; schabowy panierowany z ziemniakami i mizerią; zupa pomidorowa z ryżem; jabłko zielone; latte z mlekiem. "
    + "Markę z etykiety wpisz, gdy da się ją odczytać. name_pl, name_uk i name_en mają być tak samo szczegółowe.";
  const extra = comment.trim()
    ? " Użytkownik poprawia rozpoznanie. To jest ważniejsze niż samo zdjęcie: " + comment + ". "
      + "Jeśli zjadł tylko część, grams i kcal to ta część (połowa = 50% kcal). "
      + "Jeśli dopowiada sól, oliwę, sos albo składnik, dopisz to do nazwy i dolicz kcal. "
      + "reason: jedno zdanie " + hintLang(lang) + ", konkretnie co się zmieniło po komentarzu (porcja, składnik, kcal). Nie zostawiaj reason pustego. "
    : "";
  return "Na zdjęciu jest jedzenie albo napój." + extra
    + " Zwróć wyłącznie JSON: {\"reason\":\"\",\"items\":[{\"name_pl\":\"jabłko zielone\",\"name_uk\":\"зелене яблуко\",\"name_en\":\"green apple\",\"count\":1,\"grams\":150,\"kcal\":78,\"p\":0.4,\"c\":21,\"f\":0.2}]}."
    + " Najbliższe duże danie z przodu jest obowiązkowe. Miska sałaty i grillowanej cukinii to sałatka, nie ryba. "
    + "Puszka z tyłu za miską nie wchodzi do items. Puszka z przodu: marka z etykiety, grams = ml. "
    + "Winogrona, orzechy, jagody i chipsy: nie wpisuj count 1, jeśli widać kiść albo garść. Podaj przybliżoną liczbę. grams to cała porcja."
    + " Butelka wody też jest napojem: 0 kcal, grams = ml z etykiety."
    + " Kapelusz grzyba z blaszkami to grzyb. Duża beżowa kania to kania (czubajka kania), nie puste items i nie mięso."
    + " Nie dopisuj tła, drugiego kieliszka ani talerza z głębi, nawet jeśli coś tam widać. Max 3." + precise;
}

const PLATE_SCHEMA = {
  type: "object",
  properties: {
    reason: { type: "string" },
    items: {
      type: "array",
      items: {
        type: "object",
        properties: {
          name_pl: { type: "string" }, name_uk: { type: "string" }, name_en: { type: "string" },
          count: { type: "number" }, grams: { type: "number" }, kcal: { type: "number" },
          p: { type: "number" }, c: { type: "number" }, f: { type: "number" },
        },
        required: ["name_pl", "name_uk", "name_en", "count", "grams", "kcal", "p", "c", "f"],
        additionalProperties: false,
      },
    },
  },
  required: ["reason", "items"],
  additionalProperties: false,
};

// Claude Sonnet 5.5: niski effort (szybciej), JSON wg schematu albo z promptu; null przy błędzie.
// fallbacks: "default" – gdy filtr bezpieczeństwa odmówi, API samo ponawia na innym modelu.
async function claude(prompt, image, schema, env) {
  if (!env.ANTHROPIC_API_KEY) return { error: "off" };
  const client = new Anthropic({ apiKey: env.ANTHROPIC_API_KEY, maxRetries: 1, timeout: 25000 });
  const content = [];
  if (image) content.push({ type: "image", source: { type: "base64", media_type: "image/jpeg", data: clean(image) } });
  content.push({ type: "text", text: prompt });
  try {
    const response = await client.beta.messages.create({
      model: CLAUDE,
      max_tokens: 4000,
      betas: ["server-side-fallback-2026-07-01"],
      fallbacks: "default",
      output_config: schema ? { effort: "low", format: { type: "json_schema", schema } } : { effort: "low" },
      messages: [{ role: "user", content }],
    });
    if (response.stop_reason === "refusal") return { error: "bad" };
    let text = response.content.filter((b) => b.type === "text").map((b) => b.text).join("").trim();
    const a = text.indexOf("{");
    const b = text.lastIndexOf("}");
    if (a >= 0 && b > a) text = text.slice(a, b + 1);
    JSON.parse(text);
    return { text };
  } catch (e) {
    if (e instanceof Anthropic.RateLimitError) return { error: "quota" };
    if (e instanceof Anthropic.AuthenticationError || e instanceof Anthropic.PermissionDeniedError) return { error: "key" };
    if (e instanceof Anthropic.APIConnectionTimeoutError) return { error: "timeout" };
    if (e instanceof Anthropic.APIError) return { error: "server" };
    return { error: "bad" };
  }
}

async function recognize(body, env) {
  const comment = String(body.comment || "").slice(0, 500);
  const prompt = "Na zdjęciu jest jedzenie albo napój." + (comment ? " Użytkownik dopowiada: " + comment + "." : "")
    + " Zwróć wyłącznie JSON {\"items\":[{\"name_pl\":\"\",\"name_uk\":\"\",\"name_en\":\"\",\"grams\":150,\"kcal\":0,\"p\":0,\"c\":0,\"f\":0}]}."
    + " Najbliższe duże danie z przodu jest obowiązkowe. Pomiń tło. Max 3.";
  if (!body.image) return json({ error: "bad" }, 400);
  const out = await tryModels({
    contents: [{ role: "user", parts: [{ inline_data: { mime_type: "image/jpeg", data: clean(body.image) } }, { text: prompt }] }],
    generationConfig: { temperature: 0.2, responseMimeType: "application/json", thinkingConfig: { thinkingLevel: "low" } },
  }, env);
  if (!out.text) return json({ error: out.error }, STATUS[out.error] || 502);
  const items = (JSON.parse(out.text).items || []).map((it) => ({
    n_pl: it.name_pl, n_uk: it.name_uk, n_en: it.name_en,
    g: +it.grams || 150, kcal: +it.kcal || 0, p: +it.p || 0, c: +it.c || 0, f: +it.f || 0, src: "gemini",
  }));
  return json({ items, comment, engine: "gemini" });
}

// data:...;base64,xxx -> xxx
function clean(b64) {
  const s = String(b64 || "");
  const comma = s.indexOf(",");
  return s.startsWith("data:") && comma > 0 ? s.slice(comma + 1) : s;
}

function audioMime(mime) {
  const m = String(mime || "").toLowerCase();
  return ["audio/wav", "audio/mp3", "audio/aac", "audio/ogg", "audio/flac", "audio/webm"].includes(m) ? m : "audio/wav";
}

async function tryModels(request, env) {
  const end = Date.now() + DEADLINE_MS;
  const payload = JSON.stringify(request);
  let error = "server";
  for (const model of MODELS) {
    const left = end - Date.now();
    if (left < 2000) { error = "timeout"; break; }
    const res = await post(model, payload, Math.min(left, 20000), env);
    if (res.text) return res;
    error = res.error;
    if (error === "key") break;
  }
  return { error };
}

async function post(model, payload, ms, env) {
  const ctrl = new AbortController();
  const timer = setTimeout(() => ctrl.abort(), ms);
  try {
    const res = await fetch("https://generativelanguage.googleapis.com/v1beta/models/" + model + ":generateContent", {
      method: "POST",
      headers: { "Content-Type": "application/json", "x-goog-api-key": env.GEMINI_KEY },
      body: payload,
      signal: ctrl.signal,
    });
    if (!res.ok) {
      const s = res.status;
      return { error: s === 429 ? "quota" : s === 401 || s === 403 ? "key" : s >= 500 ? "server" : "bad" };
    }
    const data = await res.json();
    const parts = data?.candidates?.[0]?.content?.parts || [];
    let text = parts.filter((p) => !p.thought && p.text).map((p) => p.text).join("").trim();
    const a = text.indexOf("{");
    const b = text.lastIndexOf("}");
    if (a >= 0 && b > a) text = text.slice(a, b + 1);
    JSON.parse(text);
    return { text };
  } catch (e) {
    return { error: e && e.name === "AbortError" ? "timeout" : "bad" };
  } finally {
    clearTimeout(timer);
  }
}

async function sha256(text) {
  const h = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(text));
  return [...new Uint8Array(h)].map((x) => x.toString(16).padStart(2, "0")).join("");
}

// Dziennik: klucz od e-maila, dostęp tylko z tym samym hashem hasła (zapisany jako hash hasha).
// Baza: MySQL na VPS (BACKEND_URL, na czas testów serwer ERP), bez niej KV JOURNAL.
async function journal(body, env) {
  const email = String(body.email || "").trim().toLowerCase();
  const hash = String(body.hash || "");
  if (!email || hash.length < 32) return json({ error: "bad" }, 400);
  const id = await sha256(email);
  const auth = await sha256("auth:" + hash);
  if (env.BACKEND_URL) return backend({ action: body.action, id, auth, journal: body.action === "push" ? JSON.stringify(body.journal || {}) : undefined }, env);
  if (!env.JOURNAL) return json({ error: "off" }, 501);
  const key = "j:" + id;
  const saved = await env.JOURNAL.get(key, "json");
  if (saved && saved.auth !== auth) return json({ error: "auth" }, 403);
  if (body.action === "pull") {
    return json(saved ? { found: true, journal: saved.journal } : { found: false });
  }
  if (body.action === "push") {
    const text = JSON.stringify(body.journal || {});
    if (text.length > MAX_JOURNAL) return json({ error: "size" }, 413);
    await env.JOURNAL.put(key, JSON.stringify({ auth, journal: text, at: Date.now() }));
    return json({ ok: true });
  }
  return json({ error: "bad" }, 400);
}

// Subskrypcja przypomnień z iPhone'a: { subscription, cfg, tz } albo { subscription, off: true }
async function pushSave(body, env) {
  if (!env.PUSH) return json({ error: "off" }, 501);
  const sub = body.subscription || {};
  if (!knownEndpoint(sub.endpoint) || !sub.keys || !sub.keys.p256dh || !sub.keys.auth) return json({ error: "bad" }, 400);
  const key = "push:" + (await sha256(sub.endpoint)).slice(0, 40);
  if (body.off) {
    await env.PUSH.delete(key);
    return json({ ok: true });
  }
  const cfg = body.cfg && typeof body.cfg === "object" ? body.cfg : {};
  if (JSON.stringify(cfg).length > 20000) return json({ error: "size" }, 413);
  let tz = String(body.tz || "Europe/Warsaw");
  try { localNow(tz); } catch (_) { tz = "Europe/Warsaw"; }
  const old = await env.PUSH.get(key, "json");
  await env.PUSH.put(key, JSON.stringify({
    sub: { endpoint: sub.endpoint, keys: { p256dh: sub.keys.p256dh, auth: sub.keys.auth } },
    cfg, tz, sent: old && old.sent || { day: "", keys: [] }, at: Date.now(),
  }));
  return json({ ok: true });
}

async function remindAll(env) {
  if (!env.PUSH || !env.VAPID_PRIVATE) return;
  let cursor;
  do {
    const page = await env.PUSH.list({ prefix: "push:", cursor });
    cursor = page.list_complete ? null : page.cursor;
    for (const { name } of page.keys) {
      const rec = await env.PUSH.get(name, "json");
      if (!rec) continue;
      const today = localNow(rec.tz).day;
      const sent = rec.sent && rec.sent.day === today ? rec.sent.keys : [];
      const list = due(rec.cfg, rec.tz, sent);
      if (!list.length) continue;
      let gone = false;
      for (const n of list) {
        const status = await send(rec.sub, JSON.stringify({ title: n.title, body: n.body, tag: n.key }), env).catch(() => 0);
        if (status === 404 || status === 410) { gone = true; break; }
        sent.push(n.key);
      }
      if (gone) await env.PUSH.delete(name);
      else await env.PUSH.put(name, JSON.stringify({ ...rec, sent: { day: today, keys: sent } }));
    }
  } while (cursor);
}

async function backend(payload, env) {
  if (!["pull", "push", "delete"].includes(payload.action)) return json({ error: "bad" }, 400);
  if (payload.journal && payload.journal.length > MAX_JOURNAL) return json({ error: "size" }, 413);
  try {
    const res = await fetch(env.BACKEND_URL + "/journal", {
      method: "POST",
      headers: { "Content-Type": "application/json", "X-Mealquo-Backend": env.BACKEND_TOKEN },
      body: JSON.stringify(payload),
    });
    const data = await res.json().catch(() => ({ error: "server" }));
    return json(data, res.status);
  } catch (_) {
    return json({ error: "server" }, 502);
  }
}

// API v2: Worker przekazuje zapytanie (z tokenem sesji użytkownika) do usługi na VPS
const V2 = new Set(["/v2/register", "/v2/login", "/v2/logout", "/v2/sync", "/v2/export", "/v2/account/delete", "/v2/password", "/v2/photo/put", "/v2/photo/get"]);
async function v2(path, body, req, env) {
  if (!V2.has(path)) return json({ error: "missing" }, 404);
  const headers = { "Content-Type": "application/json", "X-Mealquo-Backend": env.BACKEND_TOKEN };
  const auth = req.headers.get("Authorization");
  if (auth && auth.startsWith("Bearer ")) headers.Authorization = auth.slice(0, 200);
  try {
    const res = await fetch(env.BACKEND_URL + path, { method: "POST", headers, body: JSON.stringify(body) });
    const data = await res.json().catch(() => ({ error: "server" }));
    return json(data, res.status);
  } catch (_) {
    return json({ error: "server" }, 502);
  }
}
