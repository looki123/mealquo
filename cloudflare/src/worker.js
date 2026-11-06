// Mealquo na iPhone (PWA): strona z ../web oraz API z kluczem Gemini, który nie trafia do przeglądarki.
// /api/ai        – zapytanie do Gemini (tekst, zdjęcie, nagranie, wyszukiwanie)
// /api/recognize – rozpoznanie talerza w formacie starego API (zapas w app.js)
// /api/journal   – kopia dziennika w KV (push/pull po e-mailu i hashu hasła)

const MODELS = ["gemini-3.5-flash-lite", "gemini-flash-lite-latest", "gemini-3-flash-preview"];
const DEADLINE_MS = 25000;
const MAX_BODY = 8 * 1024 * 1024;
const MAX_PROMPT = 12000;
const MAX_JOURNAL = 4 * 1024 * 1024;

export default {
  async fetch(req, env) {
    const url = new URL(req.url);
    if (!url.pathname.startsWith("/api/")) return env.ASSETS.fetch(req);
    if (req.method !== "POST") return json({ error: "method" }, 405);
    // tylko z naszej strony (przeglądarka zawsze wysyła Origin przy fetch POST)
    const origin = req.headers.get("Origin");
    if (!origin || origin !== url.origin) return json({ error: "origin" }, 403);
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
      body = JSON.parse(raw);
    } catch (_) {
      return json({ error: "bad" }, 400);
    }
    if (url.pathname === "/api/ai") return ai(body, env);
    if (url.pathname === "/api/recognize") return recognize(body, env);
    if (url.pathname === "/api/journal") return journal(body, env);
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
  return json({ error: out.error }, STATUS[out.error] || 502);
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

// Dziennik w KV: klucz od e-maila, dostęp tylko z tym samym hashem hasła (zapisany jako hash hasha)
async function journal(body, env) {
  if (!env.JOURNAL) return json({ error: "off" }, 501);
  const email = String(body.email || "").trim().toLowerCase();
  const hash = String(body.hash || "");
  if (!email || hash.length < 32) return json({ error: "bad" }, 400);
  const key = "j:" + await sha256(email);
  const auth = await sha256("auth:" + hash);
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
