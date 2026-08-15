import AuthenticationServices
import UIKit
import Shared

/// Sign in with Apple. The identity token is handed to Kotlin and never logged.
final class IosAppleSignIn: NSObject, AppleSignInHost, ASAuthorizationControllerDelegate, ASAuthorizationControllerPresentationContextProviding {
    private var pending: ((String?, String?) -> Void)?

    func signIn(onResult: @escaping (String?, String?) -> Void) {
        DispatchQueue.main.async {
            self.pending = onResult
            let request = ASAuthorizationAppleIDProvider().createRequest()
            request.requestedScopes = []
            let controller = ASAuthorizationController(authorizationRequests: [request])
            controller.delegate = self
            controller.presentationContextProvider = self
            controller.performRequests()
        }
    }

    func presentationAnchor(for controller: ASAuthorizationController) -> ASPresentationAnchor {
        let scene = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first
        return scene?.windows.first { $0.isKeyWindow } ?? UIWindow()
    }

    func authorizationController(
        controller: ASAuthorizationController,
        didCompleteWithAuthorization authorization: ASAuthorization
    ) {
        guard let credential = authorization.credential as? ASAuthorizationAppleIDCredential,
              let data = credential.identityToken,
              let token = String(data: data, encoding: .utf8),
              token.split(separator: ".").count == 3
        else {
            finish(token: nil, error: "Apple ID sign-in failed.")
            return
        }
        finish(token: token, error: nil)
    }

    func authorizationController(controller: ASAuthorizationController, didCompleteWithError error: Error) {
        let cancelled = (error as NSError).code == ASAuthorizationError.canceled.rawValue
        finish(token: nil, error: cancelled ? "Apple ID sign-in cancelled." : "Apple ID sign-in failed.")
    }

    private func finish(token: String?, error: String?) {
        let cb = pending
        pending = nil
        DispatchQueue.main.async { cb?(token, error) }
    }
}
