import SwiftUI

// Mealquo na iOS: interfejs z serwera Mealquo (ten sam co na iPhonie w Safari) w WKWebView
// plus natywne: przypomnienia (lokalne powiadomienia), Apple Health, udostępnianie, linki.
@main
struct MealquoApp: App {
    @Environment(\.scenePhase) private var phase

    var body: some Scene {
        WindowGroup {
            WebView()
                .ignoresSafeArea(.container, edges: .bottom)
                .background(Color(red: 0.965, green: 0.945, blue: 0.918))
        }
        .onChange(of: phase) { now in
            // po powrocie do aplikacji przypomnienia liczone od nowa (nowy dzień, nowe dane)
            if now == .active { Reminders.refresh() }
        }
    }
}
