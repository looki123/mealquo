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

// list do rodzica z linkiem potwierdzającym zgodę (PL/UK/EN)
export function consentMail(lang, childEmail, link) {
  const t = (pl, uk, en) => (lang === "uk" ? uk : lang === "en" ? en : pl);
  const subject = t("Zgoda rodzica – Mealquo", "Згода батьків – Mealquo", "Parent consent – Mealquo");
  const html = `<div style="font:16px/1.5 system-ui,sans-serif;max-width:560px;margin:0 auto;padding:24px;color:#1b241f">
  <h2 style="margin:0 0 12px">Mealquo</h2>
  <p>${t(`Osoba w wieku 13–15 lat założyła konto w aplikacji Mealquo (liczenie kalorii i zdrowe nawyki) na adres <b>${esc(childEmail)}</b> i podała Twój e-mail jako rodzica lub opiekuna.`,
    `Особа віком 13–15 років створила акаунт у застосунку Mealquo (підрахунок калорій і здорові звички) на адресу <b>${esc(childEmail)}</b> і вказала твою пошту як батька чи опікуна.`,
    `Someone aged 13–15 created a Mealquo account (calorie counting and healthy habits) for <b>${esc(childEmail)}</b> and gave your e-mail as a parent or guardian.`)}</p>
  <p>${t("Konto działa w trybie nastolatka: bez celu odchudzania i bez deficytu kalorii, z misjami o zdrowych nawykach. Jeśli zgadzasz się na korzystanie z aplikacji, kliknij:",
    "Акаунт працює в режимі підлітка: без цілі схуднення і без дефіциту калорій, з місіями про здорові звички. Якщо погоджуєшся на користування застосунком, натисни:",
    "The account runs in teen mode: no weight-loss goal and no calorie deficit, with healthy-habit missions. If you agree to the use of the app, click:")}</p>
  <p style="margin:20px 0"><a href="${esc(link)}" style="background:#1d3b34;color:#fff;text-decoration:none;padding:12px 18px;border-radius:12px;display:inline-block">${t("Potwierdzam zgodę", "Підтверджую згоду", "I confirm my consent")}</a></p>
  <p style="color:#7d847c;font-size:14px">${t("Jeśli to nie Ty, zignoruj tę wiadomość. Link: ", "Якщо це не ти, проігноруй цей лист. Посилання: ", "If this is not you, ignore this message. Link: ")}${esc(link)}</p>
</div>`;
  return { subject, html };
}
