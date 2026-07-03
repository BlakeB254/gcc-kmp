import Foundation
import MetricKit

/// Apple-native crash/hang diagnostics via MetricKit.
///
/// The OS delivers `MXDiagnosticPayload`s on the launch *after* a crash or
/// hang. Each payload is persisted as JSON under
/// `Documents/gcc-crash-diagnostics/` — retrievable through the Files app
/// (UIFileSharingEnabled) and summarized into the in-app 🐞 diagnostics
/// panel by the Kotlin side (`installCrashReporting`).
///
/// This complements — not replaces — the crash reports TestFlight uploads to
/// App Store Connect / Xcode Organizer automatically: MetricKit gives us the
/// same signal *in the field*, attached to the tester's screenshots, without
/// waiting for the Organizer round-trip.
final class CrashDiagnosticsObserver: NSObject, MXMetricManagerSubscriber {
    static let shared = CrashDiagnosticsObserver()

    static var diagnosticsDirectory: URL {
        let docs = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        return docs.appendingPathComponent("gcc-crash-diagnostics", isDirectory: true)
    }

    func start() {
        MXMetricManager.shared.add(self)
    }

    func didReceive(_ payloads: [MXDiagnosticPayload]) {
        let dir = Self.diagnosticsDirectory
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let stamp = ISO8601DateFormatter().string(from: Date())
            .replacingOccurrences(of: ":", with: "-")

        for (index, payload) in payloads.enumerated() {
            let crashes = payload.crashDiagnostics?.count ?? 0
            let hangs = payload.hangDiagnostics?.count ?? 0
            guard crashes > 0 || hangs > 0 else { continue }

            let url = dir.appendingPathComponent("diagnostic-\(stamp)-\(index).json")
            try? payload.jsonRepresentation().write(to: url)
            NSLog("GCC-DIAG ERR [crash] MetricKit payload saved: %@ (crashes=%d hangs=%d)",
                  url.lastPathComponent, crashes, hangs)
        }
    }
}
