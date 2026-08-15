import UIKit
import SwiftUI
import Shared

struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        MainViewControllerKt.MainViewController()
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}

struct ContentView: View {
    @Environment(\.scenePhase) private var scenePhase

    var body: some View {
        ZStack {
            ComposeView()
                .ignoresSafeArea()
            if scenePhase != .active {
                // Hide the thread in the app switcher snapshot (OWASP M6).
                Color(red: 0.10, green: 0.09, blue: 0.08)
                    .ignoresSafeArea()
                    .overlay(Text("Mirror").foregroundStyle(.white.opacity(0.8)))
            }
        }
    }
}
