// SPDX-License-Identifier: MIT
import SwiftUI

@main
struct OpenDistressWatchApp: App {
    @Environment(\.scenePhase) private var scenePhase
    @StateObject private var controller = OpenDistressController()

    var body: some Scene {
        WindowGroup {
            VStack(spacing: 12) {
                Button(controller.buttonTitle) {
                    controller.activateOrRetry()
                }
                .buttonStyle(.borderedProminent)
                .tint(.red)
                .disabled(!controller.buttonEnabled)

                Text(controller.status)
                    .font(.footnote)
                    .multilineTextAlignment(.center)
                    .accessibilityLabel("Alert evidence status: \(controller.status)")
            }
            .padding()
            .onAppear { controller.setSceneActive(scenePhase == .active) }
            .onChange(of: scenePhase) { phase in
                controller.setSceneActive(phase == .active)
            }
        }
    }
}
