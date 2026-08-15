import SwiftUI
import Shared

@main
struct iOSApp: App {
    @Environment(\.scenePhase) private var scenePhase

    init() {
        IosHttp.shared.client = PinnedCompanionClient()
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
                .onChange(of: scenePhase) { phase in
                    if phase != .active {
                        MirrorSession.shared.onBackground?.invoke()
                    }
                }
                .onOpenURL { url in
                    MirrorSession.shared.onLink?.invoke(url.absoluteString)
                }
        }
    }
}
