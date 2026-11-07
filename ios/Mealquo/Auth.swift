import AuthenticationServices
import UIKit

// „Zaloguj przez Apple”: natywne okno Apple, identityToken (JWT) idzie do app.js, które wysyła go na /api/v2/auth/apple.
// E-mail i imię Apple podaje tylko przy pierwszym logowaniu (może być ukryty adres @privaterelay.appleid.com).
final class AppleAuth: NSObject, ASAuthorizationControllerDelegate, ASAuthorizationControllerPresentationContextProviding {
    static let shared = AppleAuth()
    private var done: ((String?, String?, String?) -> Void)?

    func signIn(done: @escaping (String?, String?, String?) -> Void) {
        self.done = done
        let request = ASAuthorizationAppleIDProvider().createRequest()
        request.requestedScopes = [.email, .fullName]
        let controller = ASAuthorizationController(authorizationRequests: [request])
        controller.delegate = self
        controller.presentationContextProvider = self
        DispatchQueue.main.async { controller.performRequests() }
    }

    func authorizationController(controller: ASAuthorizationController, didCompleteWithAuthorization authorization: ASAuthorization) {
        guard let cred = authorization.credential as? ASAuthorizationAppleIDCredential,
              let data = cred.identityToken, let token = String(data: data, encoding: .utf8) else {
            done?(nil, nil, nil)
            done = nil
            return
        }
        let name = [cred.fullName?.givenName, cred.fullName?.familyName].compactMap { $0 }.joined(separator: " ")
        done?(token, cred.email, name.isEmpty ? nil : name)
        done = nil
    }

    func authorizationController(controller: ASAuthorizationController, didCompleteWithError error: Error) {
        done?(nil, nil, nil)
        done = nil
    }

    func presentationAnchor(for controller: ASAuthorizationController) -> ASPresentationAnchor {
        UIApplication.shared.connectedScenes.compactMap { ($0 as? UIWindowScene)?.keyWindow }.first ?? ASPresentationAnchor()
    }
}
