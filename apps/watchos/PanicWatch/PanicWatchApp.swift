// SPDX-License-Identifier: MIT
import SwiftUI

@main
struct PanicWatchApp: App {
    @Environment(\.scenePhase) private var scenePhase
    @StateObject private var controller = PanicController()

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
