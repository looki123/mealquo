import Foundation
import UserNotifications

// Przypomnienia jako lokalne powiadomienia iOS (WKWebView nie ma Web Push).
// Pory, warunki i teksty jak cloudflare/src/remind.js i RemindReceiver na Androidzie.
enum Reminders {
    private static let key = "mealquoReminders"
    private static let ids = ["planlunch", "plandinner", "evening", "habit0", "habit1", "habit2", "habit3", "habit4"]

    static func askPermission(done: @escaping (Bool) -> Void) {
        UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound, .badge]) { ok, _ in
            if ok { refresh() }
            done(ok)
        }
    }

    static func apply(_ json: String) {
        UserDefaults.standard.set(json, forKey: key)
        refresh()
    }

    static func refresh() {
        guard let json = UserDefaults.standard.string(forKey: key), let data = json.data(using: .utf8),
              let cfg = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] else { return }
        let center = UNUserNotificationCenter.current()
        center.removePendingNotificationRequests(withIdentifiers: ids)
        for n in plan(cfg, now: Date()) {
            let content = UNMutableNotificationContent()
            content.title = n.title
            content.body = n.body
            if cfg["sound"] as? Bool != false { content.sound = .default }
            let parts = Calendar.current.dateComponents([.year, .month, .day, .hour, .minute], from: n.at)
            center.add(UNNotificationRequest(identifier: n.id, content: content,
                                             trigger: UNCalendarNotificationTrigger(dateMatching: parts, repeats: false)))
        }
    }

    struct Note { let id: String; let at: Date; let title: String; let body: String }

    // najbliższe wystąpienie każdej pory: dziś (z danymi z dziś), a jeśli minęła – jutro (dzień jeszcze pusty)
    static func plan(_ cfg: [String: Any], now: Date) -> [Note] {
        let lang = cfg["lang"] as? String ?? "pl"
        func t(_ pl: String, _ uk: String, _ en: String) -> String { lang == "uk" ? uk : lang == "en" ? en : pl }
        let cal = Calendar.current
        let format = DateFormatter()
        format.locale = Locale(identifier: "en_US_POSIX")
        format.dateFormat = "yyyy-MM-dd"
        let today = format.string(from: now)

        var slots: [(String, String, [String: Any]?)] = [("planlunch", "10:15", nil), ("plandinner", "16:15", nil)]
        if cfg["evening"] as? Bool != false { slots.append(("evening", cfg["eveningAt"] as? String ?? "21:00", nil)) }
        if cfg["smart"] as? Bool != false, let windows = cfg["windows"] as? [[String: Any]] {
            for (i, w) in windows.prefix(5).enumerated() { if let at = w["at"] as? String { slots.append(("habit\(i)", at, w)) } }
        }

        var out: [Note] = []
        for (id, hhmm, w) in slots {
            let hm = hhmm.split(separator: ":").compactMap { Int($0) }
            guard hm.count == 2, let todayAt = cal.date(bySettingHour: hm[0], minute: hm[1], second: 0, of: now) else { continue }
            let isToday = todayAt > now
            guard let at = isToday ? todayAt : cal.date(byAdding: .day, value: 1, to: todayAt) else { continue }
            let same = isToday && (cfg["day"] as? String) == today
            let meals = same ? (cfg["meals"] as? Int ?? 0) : 0
            switch id {
            case "planlunch", "plandinner":
                let lunch = id == "planlunch"
                if same && (cfg[lunch ? "planLunch" : "planDinner"] as? Bool ?? false) { continue }
                out.append(Note(id: id, at: at,
                    title: lunch ? t("Przed lunchem", "Перед обідом", "Before lunch") : t("Plany na wieczór", "Плани на вечір", "Plans for tonight"),
                    body: lunch ? t("Co przed lunchem: zamówienie, gotowanie czy restauracja?", "Що перед обідом: замовлення, готування чи ресторан?", "Before lunch: order, cook, or a restaurant?")
                                : t("Co na wieczór: zamówienie, gotowanie czy restauracja?", "Що ввечері: замовлення, готування чи ресторан?", "Tonight: order, cook, or a restaurant?")))
            case "evening":
                let eaten = Int((cfg["eaten"] as? Double ?? Double(cfg["eaten"] as? Int ?? 0)).rounded())
                let goal = Int((cfg["goal"] as? Double ?? Double(cfg["goal"] as? Int ?? 0)).rounded())
                out.append(Note(id: id, at: at, title: t("Wieczorne podsumowanie", "Вечірній підсумок", "Evening summary"),
                    body: meals == 0 ? t("Dziś jeszcze nic nie dodane.", "Сьогодні ще нічого не додано.", "Nothing logged today yet.") : "\(eaten) / \(goal) kcal"))
            default:
                let from = w?["from"] as? Int ?? 0, to = w?["to"] as? Int ?? 0
                let usual = w?["usual"] as? String ?? ""
                let logged = same ? (cfg["logged"] as? [Int] ?? []) : []
                if logged.contains(where: { $0 >= from && $0 <= to }) { continue }
                out.append(Note(id: id, at: at, title: t("Zwykła pora", "Звична пора", "Usual time"),
                    body: t("Zwykle około \(usual) coś dodajesz. Ta pora jest jeszcze pusta.", "Зазвичай близько \(usual) ти щось додаєш. Зараз порожньо.",
                            "You usually log something around \(usual). This slot is still empty.")))
            }
        }
        return out
    }
}
