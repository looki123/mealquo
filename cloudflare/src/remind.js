// Przypomnienia na iPhone (Web Push): te same pory i teksty co Remind/RemindReceiver w aplikacji Android.
// cfg to obiekt z syncReminders() w app.js; tz to strefa czasowa telefonu (np. "Europe/Warsaw").

const WINDOW_MIN = 30; // przypomnienie wychodzi w ciągu 30 min od swojej pory (cron co 5 min)
export const MISSION_MAX = 3;   // przypomnień o misjach dziennie (tak samo Remind.java i Reminders.swift)
export const MISSION_SLOTS = 6; // najwyżej tyle pozycji missions z aplikacji

export function localNow(tz, date = new Date()) {
  const parts = Object.fromEntries(new Intl.DateTimeFormat("en-CA", {
    timeZone: tz || "Europe/Warsaw", year: "numeric", month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit", hourCycle: "h23",
  }).formatToParts(date).map((p) => [p.type, p.value]));
  return { day: `${parts.year}-${parts.month}-${parts.day}`, min: +parts.hour * 60 + +parts.minute };
}

function minutes(hhmm) {
  const m = /^(\d{1,2}):(\d{2})$/.exec(String(hhmm || ""));
  return m ? +m[1] * 60 + +m[2] : null;
}

function text(lang, pl, uk, en) {
  return lang === "uk" ? uk : lang === "en" ? en : pl;
}

// Lista { key, title, body } do wysłania teraz. sentKeys: klucze już wysłane dziś.
export function due(cfg, tz, sentKeys = [], date = new Date()) {
  cfg = cfg || {};
  const now = localNow(tz, date);
  const lang = cfg.lang || "pl";
  const same = cfg.day === now.day;
  const meals = same ? +cfg.meals || 0 : 0;
  const slots = [];
  slots.push({ key: "planlunch", at: "10:15" }, { key: "plandinner", at: "16:15" });
  if (cfg.evening !== false) slots.push({ key: "evening", at: cfg.eveningAt || "21:00" });
  if (cfg.smart !== false) {
    (cfg.windows || []).slice(0, 5).forEach((w, i) => { if (w && w.at) slots.push({ key: "habit" + i, at: w.at, w }); });
  }
  // misje dnia (teksty i pory z app.js: missionNudges), tylko niezaliczone z dzisiejszego stanu, najwyżej 3 dziennie
  const missions = same ? (Array.isArray(cfg.missions) ? cfg.missions : []).slice(0, MISSION_SLOTS) : [];
  missions.forEach((m) => { if (m && m.key && m.at && m.title && m.body) slots.push({ key: "mission:" + String(m.key).slice(0, 20), at: m.at, m }); });
  let missionsSent = sentKeys.filter((k) => String(k).indexOf("mission:") === 0).length;
  const out = [];
  for (const slot of slots) {
    const at = minutes(slot.at);
    if (at == null || now.min < at || now.min >= at + WINDOW_MIN || sentKeys.includes(slot.key)) continue;
    if (slot.m) {
      if (missionsSent >= MISSION_MAX || at < 8 * 60 || at > 21 * 60) continue;
      missionsSent++;
      out.push({ key: slot.key, title: String(slot.m.title).slice(0, 60), body: String(slot.m.body).slice(0, 200) });
      continue;
    }
    if (slot.key === "planlunch" || slot.key === "plandinner") {
      const lunch = slot.key === "planlunch";
      if (same && (lunch ? cfg.planLunch : cfg.planDinner)) continue;
      out.push({ key: slot.key,
        title: lunch ? text(lang, "Przed lunchem", "Перед обідом", "Before lunch") : text(lang, "Plany na wieczór", "Плани на вечір", "Plans for tonight"),
        body: lunch
          ? text(lang, "Co przed lunchem: zamówienie, gotowanie czy restauracja?", "Що перед обідом: замовлення, готування чи ресторан?", "Before lunch: order, cook, or a restaurant?")
          : text(lang, "Co na wieczór: zamówienie, gotowanie czy restauracja?", "Що ввечері: замовлення, готування чи ресторан?", "Tonight: order, cook, or a restaurant?") });
    } else if (slot.key === "evening") {
      out.push({ key: slot.key,
        title: text(lang, "Wieczorne podsumowanie", "Вечірній підсумок", "Evening summary"),
        body: meals === 0
          ? text(lang, "Dziś jeszcze nic nie dodane.", "Сьогодні ще нічого не додано.", "Nothing logged today yet.")
          : `${Math.round(+cfg.eaten || 0)} / ${Math.round(+cfg.goal || 0)} kcal` });
    } else {
      const { from = 0, to = 0, usual = "" } = slot.w;
      const covered = same && (cfg.logged || []).some((m) => m >= from && m <= to);
      if (covered) continue;
      out.push({ key: slot.key,
        title: text(lang, "Zwykła pora", "Звична пора", "Usual time"),
        body: text(lang, `Zwykle około ${usual} coś dodajesz. Ta pora jest jeszcze pusta.`,
          `Зазвичай близько ${usual} ти щось додаєш. Зараз порожньо.`,
          `You usually log something around ${usual}. This slot is still empty.`) });
    }
  }
  return out;
}
