// E-maile przez Brevo (API transakcyjne). Bez BREVO_KEY nic nie wysyła i zwraca false – reszta działa normalnie.
export function makeMailer(env = process.env, fetcher = fetch) {
  const key = String(env.BREVO_KEY || "").trim();
  const from = { email: String(env.MAIL_FROM || "no-reply@mealquo.app"), name: String(env.MAIL_FROM_NAME || "Mealquo") };
  return {
    enabled: !!key,
    // true gdy wysłane, false gdy brak konfiguracji albo błąd (logowany)
    async send(to, subject, html) {
      if (!key) return false;
      try {
        const res = await fetcher("https://api.brevo.com/v3/smtp/email", {
          method: "POST",
          headers: { "Content-Type": "application/json", "api-key": key, Accept: "application/json" },
          body: JSON.stringify({ sender: from, to: [{ email: String(to) }], subject: String(subject), htmlContent: String(html) }),
          signal: AbortSignal.timeout(10000),
        });
        if (!res.ok) { console.error("brevo", res.status, (await res.text().catch(() => "")).slice(0, 200)); return false; }
        return true;
      } catch (e) {
        console.error("brevo", e.message);
        return false;
      }
    },
  };
}

const esc = (s) => String(s).replace(/[&<>"]/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" })[c]);

// Wspólny szablon e-maili Mealquo: tabele i style w linii (Gmail, Outlook, Apple Mail), kolory jak w aplikacji.
// base: adres aplikacji (PUBLIC_URL) – logo i link do polityki prywatności.
export function emailLayout({ lang = "pl", base = "https://mealquo.app", preheader = "", title, paragraphs = [], button, note = "", reason = "" }) {
  const t = (pl, uk, en) => (lang === "uk" ? uk : lang === "en" ? en : pl);
  const b = String(base).replace(/\/$/, "");
  const p = paragraphs.map((x) => `<p style="margin:0 0 14px;font:16px/1.55 -apple-system,'Segoe UI',Roboto,Helvetica,Arial,sans-serif;color:#141a17">${x}</p>`).join("");
  const btn = button ? `<table role="presentation" cellpadding="0" cellspacing="0" style="margin:22px 0 6px"><tr><td style="background:#1f4d3f;border-radius:14px">
      <a href="${esc(button.href)}" style="display:inline-block;padding:14px 24px;font:600 16px -apple-system,'Segoe UI',Roboto,Helvetica,Arial,sans-serif;color:#ffffff;text-decoration:none;border-radius:14px">${esc(button.label)}</a></td></tr></table>` : "";
  return `<!doctype html><html lang="${esc(lang)}"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<meta name="color-scheme" content="light"><meta name="supported-color-schemes" content="light"><title>${esc(title)}</title></head>
<body style="margin:0;padding:0;background:#f4f1ec">
<div style="display:none;max-height:0;overflow:hidden;opacity:0">${esc(preheader)}</div>
<table role="presentation" width="100%" cellpadding="0" cellspacing="0" style="background:#f4f1ec"><tr><td align="center" style="padding:28px 14px">
  <table role="presentation" width="100%" cellpadding="0" cellspacing="0" style="max-width:520px">
    <tr><td style="padding:0 6px 18px">
      <table role="presentation" cellpadding="0" cellspacing="0"><tr>
        <td style="vertical-align:middle"><img src="${b}/img/icon-192.png" width="40" height="40" alt="" style="display:block;border-radius:11px"></td>
        <td style="vertical-align:middle;padding-left:10px;font:700 20px -apple-system,'Segoe UI',Roboto,Helvetica,Arial,sans-serif;color:#1f4d3f;letter-spacing:-.2px">Mealquo</td>
      </tr></table></td></tr>
    <tr><td style="background:#ffffff;border-radius:22px;padding:30px 28px 26px;box-shadow:0 6px 24px rgba(20,26,23,.06)">
      <h1 style="margin:0 0 16px;font:700 24px/1.25 -apple-system,'Segoe UI',Roboto,Helvetica,Arial,sans-serif;color:#141a17;letter-spacing:-.3px">${esc(title)}</h1>
      ${p}${btn}
      ${note ? `<p style="margin:18px 0 0;font:13px/1.5 -apple-system,'Segoe UI',Roboto,Helvetica,Arial,sans-serif;color:#6b736c">${note}</p>` : ""}
    </td></tr>
    <tr><td style="padding:18px 10px 0;font:12px/1.55 -apple-system,'Segoe UI',Roboto,Helvetica,Arial,sans-serif;color:#7d847c;text-align:center">
      ${reason ? `${reason}<br>` : ""}${t("Mealquo – kalorie i makro ze zdjęcia talerza", "Mealquo – калорії та макро з фото тарілки", "Mealquo – calories and macros from a photo of your plate")} ·
      <a href="${b}/privacy" style="color:#7d847c;text-decoration:underline">${t("Polityka prywatności", "Політика конфіденційності", "Privacy policy")}</a>
    </td></tr>
  </table></td></tr></table></body></html>`;
}

// list do rodzica z linkiem potwierdzającym zgodę (PL/UK/EN)
export function consentMail(lang, childEmail, link, base) {
  const t = (pl, uk, en) => (lang === "uk" ? uk : lang === "en" ? en : pl);
  const subject = t("Prośba o zgodę rodzica – Mealquo", "Прохання про згоду батьків – Mealquo", "Parent consent request – Mealquo");
  const html = emailLayout({
    lang, base: base || new URL(link).origin,
    preheader: t("Potwierdź jednym kliknięciem, że nastolatek może korzystać z Mealquo.", "Підтвердь одним натисканням, що підліток може користуватися Mealquo.", "Confirm with one tap that the teen may use Mealquo."),
    title: t("Prośba o zgodę rodzica", "Прохання про згоду батьків", "Parent consent request"),
    paragraphs: [
      t(`Osoba w wieku 13–15 lat założyła konto w Mealquo na adres <b>${esc(childEmail)}</b> i podała Twój e-mail jako rodzica lub opiekuna.`,
        `Особа віком 13–15 років створила акаунт у Mealquo на адресу <b>${esc(childEmail)}</b> і вказала твою пошту як батька чи опікуна.`,
        `Someone aged 13–15 created a Mealquo account for <b>${esc(childEmail)}</b> and gave your e-mail as a parent or guardian.`),
      t("Konto działa w <b>trybie nastolatka</b>: bez celu odchudzania i bez deficytu kalorii. Zamiast tego są misje o zdrowych nawykach: regularne posiłki, białko, warzywa, ruch i woda zamiast słodkich napojów.",
        "Акаунт працює в <b>режимі підлітка</b>: без цілі схуднення і без дефіциту калорій. Натомість є місії про здорові звички: регулярне харчування, білок, овочі, рух і вода замість солодких напоїв.",
        "The account runs in <b>teen mode</b>: no weight-loss goal and no calorie deficit. Instead there are healthy-habit missions: regular meals, protein, vegetables, movement and water instead of sugary drinks."),
      t("Jeśli zgadzasz się na korzystanie z aplikacji, potwierdź:", "Якщо погоджуєшся на користування застосунком, підтвердь:", "If you agree to the use of the app, confirm:"),
    ],
    button: { href: link, label: t("Potwierdzam zgodę", "Підтверджую згоду", "I confirm my consent") },
    note: t(`Jeśli przycisk nie działa, otwórz link: <a href="${esc(link)}" style="color:#1f4d3f">${esc(link)}</a>`,
      `Якщо кнопка не працює, відкрий посилання: <a href="${esc(link)}" style="color:#1f4d3f">${esc(link)}</a>`,
      `If the button doesn't work, open this link: <a href="${esc(link)}" style="color:#1f4d3f">${esc(link)}</a>`),
    reason: t("Dostajesz tę wiadomość, bo ktoś podał Twój adres jako rodzica w Mealquo. Jeśli to pomyłka, zignoruj ją.",
      "Ти отримуєш цей лист, бо хтось вказав твою адресу як батька в Mealquo. Якщо це помилка, проігноруй його.",
      "You're receiving this because someone gave your address as a parent in Mealquo. If this is a mistake, ignore it."),
  });
  return { subject, html };
}
