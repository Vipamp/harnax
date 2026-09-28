import HarnaxFeatures
import SwiftUI

@main
struct HarnaxApp: App {
    var body: some Scene {
        WindowGroup {
            #if DEBUG
            HarnaxDebugEntrance.root()
            #else
            HarnaxRootView(dependencies: .live())
            #endif
        }
    }
}
