// Trener AI Mealquo: prompt składa serwer z danych liczbowych od aplikacji (nigdy z dowolnego tekstu),
// odpowiedź jest JSON-em wg schematu. Trzy rodzaje:
//  goal – ocena nowego celu (realny? tempo?) i kamienie milowe
//  week – podsumowanie tygodnia, fokus i wyzwanie na nowy tydzień, trzy misje dnia
//  day  – krótki komentarz do dnia (po kilku posiłkach) z podpowiedzią na jutro
// Zasady bezpieczeństwa są w promptcie: minimum kalorii, tempo do 1% masy ciała na tydzień, łagodny ton.

export const MAX_COACH_BODY = 60 * 1000;

// misje dnia i wyzwania tygodnia, które zna aplikacja (app.js: QUESTS, WEEKLY)
export const DAILY_IDS = ["kcal", "protein", "log3", "veg", "move", "nosnack", "breakfast", "weigh", "nodrink"];
export const WEEKLY_IDS = ["kcalDays", "proteinDays", "logDays", "workouts", "weigh2", "vegDays", "noSnackDays"];

const str = (v, n) => String(v == null ? "" : v).replace(/[\u0000-\u001f<>]/g, " ").slice(0, n);
const num = (v, lo, hi) => {
  const x = Math.round(+v || 0);
  return Math.max(lo, Math.min(hi, Number.isFinite(x) ? x : lo));
};
const dec = (v, lo, hi) => {
  const x = Math.round((+v || 0) * 10) / 10;
  return Math.max(lo, Math.min(hi, Number.isFinite(x) ? x : lo));
};

function langName(lang) {
  return lang === "uk" ? "ukraińskim" : lang === "en" ? "angielskim" : "polskim";
}

function profileLines(p) {
  p = p || {};
  const goal = p.goal === "lose" ? "schudnąć" : p.goal === "gain" ? "przytyć" : "utrzymać wagę";
  const pace = p.pace === "easy" ? "spokojne" : p.pace === "hard" ? "mocne" : "normalne";
  const diet = { vegan: "wege/wegańska", highp: "wysokobiałkowa", dia: "cukrzyca / niskie węgle" }[p.diet] || "zwykła";
  const age = num(p.age, 10, 90);
  if (p.teen || age < 16) {
    // nastolatek 13–15 lat: bez odchudzania i deficytu; o wadze i kaloriach tylko jako o paliwie do wzrostu
    return [
      `Osoba: ${p.sex === "female" ? "dziewczyna" : "chłopak"}, ${age} lat (nastolatek), ${num(p.height, 120, 230)} cm. Dieta: ${diet}.`,
      `TRYB NASTOLATKA: celem jest zdrowy wzrost i dobre nawyki, nie waga. Nie proponuj chudnięcia, deficytu kalorii, liczenia kalorii jako celu, ważenia ani komentarzy o sylwetce. `
      + `Plan ${num(p.kcal, 800, 6000)} kcal to orientacyjne zapotrzebowanie, nie limit: jedzenie mniej nie jest sukcesem. Mów o regularnych posiłkach, białku, warzywach i owocach, wodzie zamiast słodkich napojów, ruchu i śnie. `
      + `Jeśli pyta o odchudzanie, zaproś do rozmowy z rodzicem albo lekarzem, ciepło i bez straszenia.`,
    ];
  }
  const lines = [
    `Osoba: ${p.sex === "female" ? "kobieta" : "mężczyzna"}, ${age} lat, ${num(p.height, 120, 230)} cm, waga ${dec(p.weight, 30, 300)} kg.`,
    `Cel: ${goal}, waga docelowa ${dec(p.target, 30, 300)} kg, tempo ${pace} (${dec(p.kgWeek, 0, 2)} kg/tydz.), plan ${num(p.kcal, 800, 6000)} kcal dziennie, białko ${num(p.protein, 20, 400)} g, dieta: ${diet}.`,
  ];
  if (p.startWeight) lines.push(`Waga na starcie celu: ${dec(p.startWeight, 30, 300)} kg (${num(p.daysIn, 0, 2000)} dni temu).`);
  const pledge = p.pledge || {};
  const hard = { sweet: "słodycze", weekend: "weekendy", hunger: "głód" }[pledge.hard];
  if (hard) lines.push(`Sam mówi, że najtrudniejsze będą: ${hard}${pledge.snack === "hard" ? "; wieczorne podjadanie będzie ciężko odpuścić" : ""}${pledge.where === "out" ? "; je częściej na mieście" : ""}.`);
  return lines;
}

function dayLines(days) {
  return (Array.isArray(days) ? days : []).slice(0, 14).map((d) => {
    const meals = (Array.isArray(d.meals) ? d.meals : []).slice(0, 8).map((m) => `${str(m.t, 60)} ${num(m.kcal, 0, 5000)} kcal`).join("; ");
    const work = num(d.burn, 0, 5000);
    return `${str(d.d, 10)}: ${num(d.kcal, 0, 20000)} kcal (plan ${num(d.plan, 0, 20000)}), białko ${num(d.p, 0, 2000)} g, węgle ${num(d.c, 0, 5000)} g, tłuszcz ${num(d.f, 0, 2000)} g, posiłków ${num(d.n, 0, 50)}${work ? `, trening ${work} kcal` : ""}${meals ? `. Posiłki: ${meals}` : ""}`;
  });
}

function weightLines(ws) {
  const list = (Array.isArray(ws) ? ws : []).slice(-20).map((w) => `${str(w.d, 10)} ${dec(w.kg, 30, 300)} kg`);
  return list.length ? `Pomiary wagi: ${list.join(", ")}.` : "Brak pomiarów wagi.";
}

const RULES = "Jesteś trenerem w aplikacji Mealquo (liczenie kalorii ze zdjęć talerza). Mówisz do użytkownika na ty, ciepło i konkretnie, jak dobry znajomy, który zna się na odżywianiu. "
  + "Chwal to, co poszło dobrze, zanim coś poprawisz. Nigdy nie zawstydzasz, nie moralizujesz, nie straszysz. Nie proponujesz głodówek, detoksów, pomijania posiłków ani jedzenia poniżej planu kalorii. "
  + "Bezpieczne tempo to najwyżej 1% masy ciała na tydzień; wolniej jest w porządku. Nie stawiasz diagnoz medycznych. Bez emoji, bez markdownu, bez wykrzykników w nadmiarze. Liczby podawaj zaokrąglone. ";

export function coachPrompt(kind, body) {
  const lang = ["pl", "uk", "en"].includes(body.lang) ? body.lang : "pl";
  const out = `Wszystkie teksty w odpowiedzi napisz w języku ${langName(lang)}. Zwróć wyłącznie JSON.`;
  const prof = profileLines(body.profile).join(" ");
  if (kind === "goal") {
    if (body.profile && (body.profile.teen || num(body.profile.age, 10, 90) < 16)) return null;   // nastolatek nie ustawia celu wagi
    return RULES + "\nUżytkownik właśnie ustawia nowy cel.\n" + prof + "\n" + weightLines(body.weights)
      + "\nOceń, czy cel i tempo są realne i zdrowe. verdict: \"ok\" gdy tak, \"fast\" gdy tempo lub cel są zbyt ostre (np. docelowa waga poniżej zdrowego BMI 18.5 albo tempo powyżej 1% masy ciała), \"slow\" gdy można śmiało odrobinę przyspieszyć. "
      + "message: 2–3 zdania do użytkownika: co myślisz o celu, co będzie najważniejsze w pierwszych tygodniach (odnieś się do tego, co sam uznał za trudne). "
      + "milestones: 2–4 kamienie milowe między wagą startową a docelową (kg malejące przy chudnięciu, rosnące przy tyciu; ostatni = waga docelowa), każdy z krótką etykietą (do 5 słów), bez ocen wyglądu. "
      + "firstWeek: jedno zdanie, co zrobić w pierwszym tygodniu. "
      + out + " Format: {\"verdict\":\"ok\",\"message\":\"\",\"milestones\":[{\"kg\":80,\"label\":\"\"}],\"firstWeek\":\"\"}";
  }
  const teen = !!(body.profile && (body.profile.teen || num(body.profile.age, 10, 90) < 16));
  if (kind === "week") {
    const days = dayLines(body.days);
    const game = body.game || {};
    const done = (Array.isArray(game.done) ? game.done : []).slice(0, 30).map((x) => str(x, 20)).join(", ");
    return RULES + "\nKończy się tydzień, zaczynasz z użytkownikiem następny.\n" + prof + "\n" + weightLines(body.weights)
      + "\nOstatnie dni (najstarszy pierwszy):\n" + (days.length ? days.join("\n") : "Brak zapisanych dni.")
      + `\nSeria dni z wpisami: ${num(game.streak, 0, 2000)}. Poziom w grze: ${num(game.level, 1, 99)}. Misje ukończone w tym tygodniu: ${done || "żadna"}. Poprzednie wyzwanie tygodnia: ${str(game.lastWeekly, 40) || "brak"}${game.lastWeeklyDone ? " (zrobione)" : ""}.`
      + "\nNapisz review: 2–3 zdania o minionym tygodniu, z konkretem z dziennika (nazwa posiłku, liczba dni w planie, trend wagi). Jeśli dni było mało, zachęć bez wyrzutów. "
      + "focus: jedno zdanie, na czym skupić się w tym tygodniu (jedna rzecz). "
      + `weekly: wyzwanie tygodnia dopasowane do dziennika: id z listy ${WEEKLY_IDS.join(", ")} (kcalDays = dni w planie kalorii, proteinDays = dni z białkiem na miejscu, logDays = dni z zapisanymi co najmniej 2 posiłkami, workouts = treningi, weigh2 = ważenia, vegDays = dni z warzywami lub owocami, noSnackDays = wieczory bez jedzenia po 21) i target: liczba od 2 do 6 (weigh2 zawsze 2), odrobinę ponad to, co już wychodzi. `
      + (teen
        ? `daily: dokładnie 3 różne id misji dnia z listy protein, log3, veg, move, breakfast, nodrink (protein = białko, log3 = zapisać 3 posiłki, veg = warzywa lub owoce, move = ruch, breakfast = śniadanie z białkiem, nodrink = dzień bez słodkich napojów); bez kcal, nosnack i weigh. weekly tylko z: logDays, proteinDays, vegDays, workouts. `
        : `daily: dokładnie 3 różne id misji dnia z listy ${DAILY_IDS.filter((x) => x !== "weigh" && x !== "nodrink").join(", ")} (kcal = zmieścić się w kaloriach, protein = białko, log3 = zapisać 3 posiłki, veg = warzywa lub owoce, move = ruch, nosnack = wieczór bez podjadania, breakfast = śniadanie z białkiem); wybierz te, które najbardziej pomogą tej osobie. `)
      + (teen ? "milestone: jedno zdanie o nawyku, który najbardziej się utrwalił albo wart jest utrwalenia (bez wagi). " : "milestone: jedno zdanie o najbliższym kamieniu milowym wagi (ile brakuje, realny termin) albo o utrzymaniu, gdy cel to utrzymać wagę. ")
      + "tips: 2 krótkie, konkretne podpowiedzi (do 12 słów każda) pasujące do tego, co je. "
      + out + " Format: {\"review\":\"\",\"focus\":\"\",\"weekly\":{\"id\":\"kcalDays\",\"target\":4},\"daily\":[\"kcal\",\"protein\",\"veg\"],\"milestone\":\"\",\"tips\":[\"\",\"\"]}";
  }
  if (kind === "day") {
    const today = body.today || {};
    const meals = (Array.isArray(today.meals) ? today.meals : []).slice(0, 12).map((m) => `${str(m.time, 5)} ${str(m.t, 60)} ${num(m.kcal, 0, 5000)} kcal, białko ${num(m.p, 0, 500)} g`).join("; ");
    return RULES + "\n" + prof
      + `\nDziś (godzina ${num(today.hour, 0, 23)}): zjedzone ${num(today.kcal, 0, 20000)} z ${num(today.plan, 0, 20000)} kcal, białko ${num(today.p, 0, 2000)} z ${num(today.pGoal, 0, 500)} g, węgle ${num(today.c, 0, 5000)} g, tłuszcz ${num(today.f, 0, 2000)} g${today.burn ? `, trening ${num(today.burn, 0, 5000)} kcal` : ""}. Posiłki: ${meals || "brak"}.`
      + `\nSeria dni: ${num(today.streak, 0, 2000)}. Misje dnia: ${str(today.quests, 200) || "brak"}.`
      + "\nNapisz text: 2 zdania. Pierwsze o tym dniu, z konkretem (co było dobre, co ewentualnie przesunąć), drugie z jedną prostą podpowiedzią na resztę dnia albo na jutro. Jeśli zostało dużo kalorii, nie każ ich dojadać na siłę, ale dbaj o białko. "
      + out + " Format: {\"text\":\"\"}";
  }
  return null;
}

export function coachSchema(kind) {
  if (kind === "goal") {
    return {
      type: "object",
      properties: {
        verdict: { type: "string", enum: ["ok", "fast", "slow"] },
        message: { type: "string" },
        milestones: { type: "array", items: { type: "object", properties: { kg: { type: "number" }, label: { type: "string" } }, required: ["kg", "label"], additionalProperties: false } },
        firstWeek: { type: "string" },
      },
      required: ["verdict", "message", "milestones", "firstWeek"],
      additionalProperties: false,
    };
  }
  if (kind === "week") {
    return {
      type: "object",
      properties: {
        review: { type: "string" },
        focus: { type: "string" },
        weekly: { type: "object", properties: { id: { type: "string", enum: WEEKLY_IDS }, target: { type: "integer" } }, required: ["id", "target"], additionalProperties: false },
        daily: { type: "array", items: { type: "string", enum: DAILY_IDS } },
        milestone: { type: "string" },
        tips: { type: "array", items: { type: "string" } },
      },
      required: ["review", "focus", "weekly", "daily", "milestone", "tips"],
      additionalProperties: false,
    };
  }
  if (kind === "day") {
    return { type: "object", properties: { text: { type: "string" } }, required: ["text"], additionalProperties: false };
  }
  return null;
}
