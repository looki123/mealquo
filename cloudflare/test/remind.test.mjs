// node test/remind.test.mjs
import { due, localNow } from "../src/remind.js";
import assert from "node:assert/strict";

// 2026-11-07 21:05 w Warszawie (UTC+1) = 20:05 UTC
const at = (hhmmUtc) => new Date(`2026-11-07T${hhmmUtc}:00Z`);
assert.deepEqual(localNow("Europe/Warsaw", at("20:05")), { day: "2026-11-07", min: 21 * 60 + 5 });

const cfg = { lang: "pl", day: "2026-11-07", meals: 2, eaten: 1450, goal: 2000, evening: true, eveningAt: "21:00", smart: true,
  windows: [{ at: "13:00", from: 12 * 60 + 30, to: 14 * 60, usual: "13:15" }], logged: [8 * 60], planLunch: false, planDinner: true };

let d = due(cfg, "Europe/Warsaw", [], at("20:05"));
assert.deepEqual(d.map((x) => x.key), ["evening"]);
assert.equal(d[0].body, "1450 / 2000 kcal");
assert.equal(due(cfg, "Europe/Warsaw", ["evening"], at("20:05")).length, 0, "nie wysyła drugi raz");
assert.equal(due(cfg, "Europe/Warsaw", [], at("20:40")).length, 0, "po 30 min już nie");

// zwykła pora 13:00, nic nie dodane między 12:30 a 14:00 -> przypomnienie
d = due(cfg, "Europe/Warsaw", [], at("12:02"));
assert.deepEqual(d.map((x) => x.key), ["habit0"]);
assert.match(d[0].body, /13:15/);
// posiłek o 12:45 -> bez przypomnienia
assert.equal(due({ ...cfg, logged: [12 * 60 + 45] }, "Europe/Warsaw", [], at("12:02")).length, 0);

// plan na lunch o 10:15, kolacja już zaplanowana
assert.deepEqual(due(cfg, "Europe/Warsaw", [], at("09:20")).map((x) => x.key), ["planlunch"]);
assert.equal(due(cfg, "Europe/Warsaw", [], at("15:20")).length, 0);

// nowy dzień: dane z wczoraj nie liczą się jako dzisiejsze
d = due({ ...cfg, day: "2026-11-06" }, "Europe/Warsaw", [], at("20:05"));
assert.equal(d[0].body, "Dziś jeszcze nic nie dodane.");

// wyłączone wieczorne i inteligentne
assert.deepEqual(due({ ...cfg, evening: false, smart: false }, "Europe/Warsaw", [], at("20:05")), []);
// po ukraińsku
assert.equal(due({ ...cfg, lang: "uk" }, "Europe/Warsaw", [], at("20:05"))[0].title, "Вечірній підсумок");
console.log("przypomnienia OK");
