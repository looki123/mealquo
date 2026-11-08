import UIKit
import WebKit
import UserNotifications

// Mostek JS <-> iOS. web.js (wersja na iPhone'a) daje window.MealquoNative; tu podmieniamy funkcje,
// które natywnie działają lepiej albo w WKWebView nie działają wcale (Web Push).
final class Bridge: NSObject, WKScriptMessageHandler, WKUIDelegate, WKNavigationDelegate {
    weak var web: WKWebView?
    private let health = Health()

    static var version: String {
        Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "ios"
    }

    static var script: String {
        """
        (function () {
          const send = (cmd, args) => window.webkit.messageHandlers.mealquo.postMessage({ cmd, args: args === undefined ? null : args });
          const N = window.MealquoNative || (window.MealquoNative = {});
          window.__iosPush = window.__iosPush || "ask";
          N.appVersion = () => "\(version) iOS";
          N.setReminders = (json) => send("setReminders", String(json || "{}"));
          N.askNotify = () => send("askNotify");
          N.pushState = () => window.__iosPush;
          N.readWatch = (day) => send("readWatch", String(day || ""));
          N.readSteps = (day) => send("readSteps", String(day || ""));
          N.askSteps = () => send("readSteps", "");
          N.shareText = (name, text) => send("shareText", { name: String(name || "mealquo.txt"), text: String(text || "") });
          N.openUrl = (url) => send("openUrl", String(url || ""));
          N.appleReady = () => true;
          N.appleSignIn = () => send("appleSignIn");
          send("pushState");
        })();
        """
    }

    func userContentController(_ controller: WKUserContentController, didReceive message: WKScriptMessage) {
        guard let body = message.body as? [String: Any], let cmd = body["cmd"] as? String else { return }
        let args = body["args"]
        switch cmd {
        case "setReminders":
            if let json = args as? String { Reminders.apply(json) }
        case "askNotify":
            Reminders.askPermission { _ in
                self.pushState()
                self.js("typeof render === 'function' && render()")
            }
        case "pushState":
            pushState()
        case "readWatch":
            health.read(day: args as? String ?? "") { code in self.js(code) }
        case "readSteps":
            let day = (args as? String).flatMap { $0.isEmpty ? nil : $0 } ?? Bridge.today()
            health.steps(day: day) { code in self.js(code) }
        case "shareText":
            if let a = args as? [String: Any] { share(name: a["name"] as? String ?? "mealquo.txt", text: a["text"] as? String ?? "") }
        case "appleSignIn":
            AppleAuth.shared.signIn { token, email, name in
                if let token = token {
                    self.js("window.onAppleToken && window.onAppleToken(\(Bridge.quote(token)), \(Bridge.quote(email ?? "")), \(Bridge.quote(name ?? "")))")
                } else {
                    self.js("window.onAppleFail && window.onAppleFail()")
                }
            }
        case "openUrl":
            if let s = args as? String, let url = URL(string: s), url.scheme == "https" {
                DispatchQueue.main.async { UIApplication.shared.open(url) }
            }
        default:
            break
        }
    }

    static func today() -> String {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.dateFormat = "yyyy-MM-dd"
        return f.string(from: Date())
    }

    // napis jako literał JS (JSON)
    static func quote(_ s: String) -> String {
        let data = (try? JSONSerialization.data(withJSONObject: [s])) ?? Data()
        let arr = String(data: data, encoding: .utf8) ?? "[\"\"]"
        return String(arr.dropFirst().dropLast())
    }

    func js(_ code: String) {
        DispatchQueue.main.async { self.web?.evaluateJavaScript(code, completionHandler: nil) }
    }

    private func pushState() {
        UNUserNotificationCenter.current().getNotificationSettings { s in
            let state: String
            switch s.authorizationStatus {
            case .authorized, .provisional, .ephemeral: state = "on"
            case .denied: state = "denied"
            default: state = "ask"
            }
            self.js("window.__iosPush = '\(state)'")
        }
    }

    private func share(name: String, text: String) {
        DispatchQueue.main.async {
            let safe = name.range(of: "^[A-Za-z0-9._-]{1,64}$", options: .regularExpression) != nil ? name : "mealquo.txt"
            let url = FileManager.default.temporaryDirectory.appendingPathComponent(safe)
            try? text.data(using: .utf8)?.write(to: url)
            let sheet = UIActivityViewController(activityItems: [url], applicationActivities: nil)
            sheet.popoverPresentationController?.sourceView = self.web
            self.top()?.present(sheet, animated: true)
        }
    }

    private func top() -> UIViewController? {
        var vc = UIApplication.shared.connectedScenes.compactMap { ($0 as? UIWindowScene)?.keyWindow }.first?.rootViewController
        while let next = vc?.presentedViewController { vc = next }
        return vc
    }

    // aparat i mikrofon (getUserMedia) tylko dla strony Mealquo
    func webView(_ webView: WKWebView, requestMediaCapturePermissionFor origin: WKSecurityOrigin, initiatedByFrame frame: WKFrameInfo,
                 type: WKMediaCaptureType, decisionHandler: @escaping (WKPermissionDecision) -> Void) {
        decisionHandler(origin.host == WebView.host ? .prompt : .deny)
    }

    // linki poza Mealquo w Safari
    func webView(_ webView: WKWebView, decidePolicyFor action: WKNavigationAction, decisionHandler: @escaping (WKNavigationActionPolicy) -> Void) {
        if let url = action.request.url, let scheme = url.scheme, scheme.hasPrefix("http"), url.host != WebView.host {
            UIApplication.shared.open(url)
            decisionHandler(.cancel)
            return
        }
        decisionHandler(.allow)
    }

    func webView(_ webView: WKWebView, createWebViewWith configuration: WKWebViewConfiguration, for action: WKNavigationAction,
                 windowFeatures: WKWindowFeatures) -> WKWebView? {
        if let url = action.request.url {
            if url.host == WebView.host { webView.load(action.request) } else { UIApplication.shared.open(url) }
        }
        return nil
    }

    // alert() i confirm() z app.js (np. potwierdzenie usunięcia konta)
    func webView(_ webView: WKWebView, runJavaScriptAlertPanelWithMessage message: String, initiatedByFrame frame: WKFrameInfo,
                 completionHandler: @escaping () -> Void) {
        let alert = UIAlertController(title: nil, message: message, preferredStyle: .alert)
        alert.addAction(UIAlertAction(title: "OK", style: .default) { _ in completionHandler() })
        top()?.present(alert, animated: true) ?? completionHandler()
    }

    func webView(_ webView: WKWebView, runJavaScriptConfirmPanelWithMessage message: String, initiatedByFrame frame: WKFrameInfo,
                 completionHandler: @escaping (Bool) -> Void) {
        let alert = UIAlertController(title: nil, message: message, preferredStyle: .alert)
        alert.addAction(UIAlertAction(title: "Anuluj", style: .cancel) { _ in completionHandler(false) })
        alert.addAction(UIAlertAction(title: "OK", style: .destructive) { _ in completionHandler(true) })
        if let vc = top() { vc.present(alert, animated: true) } else { completionHandler(false) }
    }
}
