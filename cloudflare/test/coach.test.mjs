// node test/coach.test.mjs – prompt trenera: składanie z danych, czyszczenie, limity, schematy
import assert from "node:assert/strict";
import { coachPrompt, coachSchema, DAILY_IDS, WEEKLY_IDS } from "../src/coach.js";

const profile = { sex: "female", age: 34, height: 168, weight: 74.4, target: 66, goal: "lose", pace: "normal", kgWeek: 0.5, kcal: 1650, protein: 140, diet: "normal", pledge: { snack: "hard", hard: "sweet", where: "home" } };

// goal: profil i ważenia trafiają do promptu, język wg lang
{
  const p = coachPrompt("goal", { lang: "uk", profile, weights: [{ d: "2026-11-01", kg: 75 }] });
  assert.ok(p.includes("kobieta, 34 lat, 168 cm, waga 74.4 kg"));
  assert.ok(p.includes("waga docelowa 66 kg"));
  assert.ok(p.includes("2026-11-01 75 kg"));
  assert.ok(p.includes("ukraińskim"));
  assert.ok(p.includes("słodycze"));
}

// week: dni i posiłki, znaki sterujące i nawiasy usunięte, listy przycięte
{
  const days = Array.from({ length: 30 }, (_, i) => ({ d: `2026-10-${String(i + 1).padStart(2, "0")}`, kcal: 1500 + i, plan: 1650, p: 120, c: 150, f: 50, n: 3, burn: i % 2 ? 300 : 0,
    meals: Array.from({ length: 12 }, (_, j) => ({ t: `<b>Zupa</b>\u0001 ${"x".repeat(100)}${j}`, kcal: 300 })) }));
  const p = coachPrompt("week", { lang: "pl", profile, days, weights: [], game: { streak: 5, level: 3, done: ["kcal", "veg"], lastWeekly: "kcalDays", lastWeeklyDone: true } });
  assert.ok(!p.includes("<b>"));
  assert.ok(!p.includes("\u0001"));
  assert.equal((p.match(/^2026-10-/gm) || []).length, 14, "najwyżej 14 dni");
  assert.equal((p.match(/Zupa/g) || []).length, 14 * 8, "najwyżej 8 posiłków na dzień");
  assert.ok(p.includes("Brak pomiarów wagi"));
  assert.ok(p.includes("Seria dni z wpisami: 5"));
  assert.ok(p.includes("polskim"));
  assert.ok(p.length < 30000);
}

// day: dzisiejsze posiłki
{
  const p = coachPrompt("day", { lang: "en", profile, today: { hour: 19, kcal: 1200, plan: 1650, p: 80, pGoal: 140, c: 100, f: 40, meals: [{ time: "08:10", t: "Owsianka", kcal: 350, p: 12 }], streak: 2, quests: "kcal, protein" } });
  assert.ok(p.includes("08:10 Owsianka 350 kcal"));
  assert.ok(p.includes("angielskim"));
}

// nieznany rodzaj i liczby poza zakresem
assert.equal(coachPrompt("hack", { profile }), null);
assert.ok(coachPrompt("goal", { profile: { weight: -5, age: 999 } }).includes("waga 30 kg"));
assert.ok(coachPrompt("goal", { profile: { weight: -5, age: 999 } }).includes("90 lat"));

// schematy: wartości tylko z list znanych aplikacji
assert.deepEqual(coachSchema("week").properties.weekly.properties.id.enum, WEEKLY_IDS);
assert.deepEqual(coachSchema("week").properties.daily.items.enum, DAILY_IDS);
assert.equal(coachSchema("day").required[0], "text");
assert.equal(coachSchema("nope"), null);

// nastolatek: bez celu wagi, inne misje, brak oceny celu
{
  const teen = { ...profile, age: 14, teen: true };
  const p = coachPrompt("week", { lang: "pl", profile: teen, days: [], weights: [], game: {} });
  assert.ok(p.includes("TRYB NASTOLATKA"));
  assert.ok(!p.includes("waga docelowa"), "bez celu wagi");
  assert.ok(p.includes("nodrink") && p.includes("bez kcal, nosnack i weigh"));
  assert.ok(p.includes("logDays, proteinDays, vegDays, workouts"));
  assert.equal(coachPrompt("goal", { lang: "pl", profile: teen }), null);
  assert.ok(coachPrompt("day", { lang: "pl", profile: { ...profile, age: 15 }, today: {} }).includes("nastolatek"), "15 lat bez flagi też");
  assert.ok(!coachPrompt("day", { lang: "pl", profile: { ...profile, age: 16 }, today: {} }).includes("nastolatek"));
}
// kroki: misja steps tylko gdy telefon je liczy
{
  const withSteps = coachPrompt("week", { lang: "pl", profile, days: [{ d: "2026-11-01", kcal: 1500, plan: 1650, p: 100, c: 150, f: 50, n: 3, steps: 8421, meals: [] }], weights: [], game: { steps: true, stepsAvg: 6800, stepsGoal: 7000, stepsHit: 5 } });
  assert.ok(withSteps.includes("8421 kroków") && withSteps.includes("steps = 8000 kroków"));
  assert.ok(withSteps.includes("średnio 6800 dziennie") && withSteps.includes("dotychczasowy cel 7000") && withSteps.includes("stepsGoal: cel kroków"));
  const noSteps = coachPrompt("week", { lang: "pl", profile, days: [], weights: [], game: {} });
  assert.ok(!noSteps.includes("steps = 8000") && noSteps.includes("stepsGoal: 0"));
  assert.deepEqual(coachSchema("week").required.slice(-2), ["stepsGoal", "stepsWhy"]);
}
console.log("coach: ok");
