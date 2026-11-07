import SwiftUI
import WebKit

struct WebView: UIViewRepresentable {
    static let host = "mealquo.mealquo.workers.dev"
    static let home = URL(string: "https://\(host)/")!

    func makeCoordinator() -> Bridge { Bridge() }

    func makeUIView(context: Context) -> WKWebView {
        let config = WKWebViewConfiguration()
        // app-bound domain: service worker (offline, zdjęcia posiłków) działa jak w Safari
        config.limitsNavigationsToAppBoundDomains = true
        config.allowsInlineMediaPlayback = true
        config.mediaTypesRequiringUserActionForPlayback = []
        let controller = WKUserContentController()
        controller.add(context.coordinator, name: "mealquo")
        controller.addUserScript(WKUserScript(source: Bridge.script, injectionTime: .atDocumentEnd, forMainFrameOnly: true))
        config.userContentController = controller

        let web = WKWebView(frame: .zero, configuration: config)
        web.uiDelegate = context.coordinator
        web.navigationDelegate = context.coordinator
        web.isOpaque = false
        web.backgroundColor = UIColor(red: 0.965, green: 0.945, blue: 0.918, alpha: 1)
        web.scrollView.contentInsetAdjustmentBehavior = .never
        context.coordinator.web = web
        web.load(URLRequest(url: Self.home))
        return web
    }

    func updateUIView(_ uiView: WKWebView, context: Context) {}
}
