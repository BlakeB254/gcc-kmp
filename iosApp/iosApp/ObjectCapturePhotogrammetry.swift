import Foundation
import RealityKit
import SwiftUI
import UIKit
import ComposeApp

/// Guided Object Capture + on-device photogrammetry (iOS 17).
///
/// Implements the Kotlin `ObjectCaptureBridgeSurface` contract: hosts
/// `ObjectCaptureView` (SwiftUI) for the guided orbit, then reconstructs a
/// `.reduced`-detail USDZ with `PhotogrammetrySession` and reports the
/// model's measured bounds. Registered from the AppDelegate ONLY when
/// `ObjectCaptureSession.isSupported` — the registry's absence is Kotlin's
/// capability check, so unsupported devices never show the entry point.
final class ObjectCaptureBridgeImpl: NSObject, ObjectCaptureBridgeSurface {

    /// Bounding boxes overstate loose piles — assume ~50% fill.
    private static let bboxFillFactor = 0.5

    private var session: ObjectCaptureSession?
    private var hosting: UIHostingController<CaptureHostView>?
    private var photogrammetry: PhotogrammetrySession?
    private var stateTask: Task<Void, Never>?
    private var reconstructing = false
    private var finished = false

    private let imagesDir: URL
    // Kotlin function types box primitives across the bridge (KotlinInt /
    // KotlinDouble), so the blocks carry objects, not raw numbers.
    private var onState: ((KotlinInt, KotlinDouble) -> Void)?
    private var onResult: ((String?, KotlinDouble, KotlinDouble, KotlinDouble, KotlinDouble) -> Void)?

    override init() {
        imagesDir = FileManager.default.temporaryDirectory
            .appendingPathComponent("object-capture-\(UUID().uuidString)", isDirectory: true)
        try? FileManager.default.createDirectory(at: imagesDir, withIntermediateDirectories: true)
        super.init()
    }

    // MARK: - ObjectCaptureBridgeSurface

    func view() -> UIView {
        MainActor.assumeIsolated {
            if let hosting { return hosting.view }
            let session = ObjectCaptureSession()
            self.session = session
            var configuration = ObjectCaptureSession.Configuration()
            configuration.isOverCaptureEnabled = false
            session.start(imagesDirectory: imagesDir, configuration: configuration)
            observeState(session)
            let controller = UIHostingController(rootView: CaptureHostView(session: session))
            controller.view.backgroundColor = .black
            hosting = controller
            return controller.view
        }
    }

    func advance() {
        MainActor.assumeIsolated {
            guard let session else { return }
            switch session.state {
            case .ready:
                _ = session.startDetecting()
            case .detecting:
                session.startCapturing()
            default:
                break
            }
        }
    }

    func finishCapture() {
        MainActor.assumeIsolated {
            guard let session, case .capturing = session.state else { return }
            session.finish()
        }
    }

    func cancel() {
        MainActor.assumeIsolated {
            stateTask?.cancel()
            stateTask = nil
            session?.cancel()
            session = nil
            photogrammetry?.cancel()
            photogrammetry = nil
            cleanupImages()
        }
    }

    func setListener(
        onState: ((KotlinInt, KotlinDouble) -> Void)?,
        onResult: ((String?, KotlinDouble, KotlinDouble, KotlinDouble, KotlinDouble) -> Void)?
    ) {
        self.onState = onState
        self.onResult = onResult
    }

    // MARK: - Capture state

    @MainActor
    private func observeState(_ session: ObjectCaptureSession) {
        stateTask = Task { @MainActor [weak self] in
            for await state in session.stateUpdates {
                guard let self else { return }
                switch state {
                case .initializing: self.emitState(0)
                case .ready: self.emitState(1)
                case .detecting: self.emitState(2)
                case .capturing: self.emitState(3)
                case .finishing: self.emitState(4)
                case .completed: self.startReconstruction()
                case .failed(let error):
                    NSLog("GCC-DIAG object-capture session failed: \(error)")
                    self.emitState(6)
                    self.emitResult(nil, .zero)
                @unknown default: break
                }
            }
        }
    }

    // MARK: - Photogrammetry

    @MainActor
    private func startReconstruction() {
        guard !reconstructing else { return }
        reconstructing = true
        emitState(4)

        let outDir = FileManager.default
            .urls(for: .documentDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("object-captures", isDirectory: true)
        try? FileManager.default.createDirectory(at: outDir, withIntermediateDirectories: true)
        let outURL = outDir.appendingPathComponent("\(UUID().uuidString).usdz")

        do {
            let pg = try PhotogrammetrySession(
                input: imagesDir,
                configuration: PhotogrammetrySession.Configuration()
            )
            photogrammetry = pg
            Task { [weak self] in
                var extents: SIMD3<Float> = .zero
                do {
                    for try await output in pg.outputs {
                        switch output {
                        case .requestProgress(_, let fractionComplete):
                            await MainActor.run { self?.emitState(4, progress: fractionComplete) }
                        case .requestComplete(_, let result):
                            if case .bounds(let box) = result {
                                extents = box.extents
                            }
                        case .processingComplete:
                            await MainActor.run {
                                self?.emitState(5, progress: 1.0)
                                self?.emitResult(outURL.path, extents)
                                self?.cleanupImages()
                            }
                        case .requestError(_, let error):
                            NSLog("GCC-DIAG photogrammetry request error: \(error)")
                        default:
                            break
                        }
                    }
                } catch {
                    NSLog("GCC-DIAG photogrammetry failed: \(error)")
                    await MainActor.run {
                        self?.emitState(6)
                        self?.emitResult(nil, .zero)
                        self?.cleanupImages()
                    }
                }
            }
            try pg.process(requests: [
                .modelFile(url: outURL, detail: .reduced),
                .bounds,
            ])
        } catch {
            NSLog("GCC-DIAG photogrammetry session init failed: \(error)")
            emitState(6)
            emitResult(nil, .zero)
            cleanupImages()
        }
    }

    // MARK: - Helpers

    private func emitState(_ raw: Int32, progress: Double = 0) {
        DispatchQueue.main.async {
            self.onState?(KotlinInt(int: raw), KotlinDouble(double: progress))
        }
    }

    /// Reports the USDZ path and measured bbox; volume assumes a loose-pile
    /// fill factor since a bounding box overstates irregular heaps.
    ///
    /// `.bounds` can hand back a degenerate box (NaN/inf/non-positive extents)
    /// when photogrammetry couldn't resolve real geometry — feeding that into
    /// the volume math and onward to Kotlin is worse than just reporting no
    /// dimensions, so a bad box is treated exactly like the `.zero`/no-bounds
    /// failure paths above (dims reported as 0, which Kotlin's
    /// `attachObjectCapture` already treats as "no dimensions").
    private func emitResult(_ path: String?, _ extents: SIMD3<Float>) {
        guard !finished else { return }
        finished = true
        let rawWidth = Double(extents.x)
        let rawHeight = Double(extents.y)
        let rawDepth = Double(extents.z)
        let extentsValid = rawWidth.isFinite && rawHeight.isFinite && rawDepth.isFinite &&
            rawWidth > 0 && rawHeight > 0 && rawDepth > 0
        let width = extentsValid ? rawWidth : 0
        let height = extentsValid ? rawHeight : 0
        let depth = extentsValid ? rawDepth : 0
        let volumeLiters = width * height * depth * Self.bboxFillFactor * 1000.0
        DispatchQueue.main.async {
            self.onResult?(
                path,
                KotlinDouble(double: width),
                KotlinDouble(double: height),
                KotlinDouble(double: depth),
                KotlinDouble(double: volumeLiters)
            )
        }
    }

    private func cleanupImages() {
        try? FileManager.default.removeItem(at: imagesDir)
    }
}

private struct CaptureHostView: View {
    let session: ObjectCaptureSession

    var body: some View {
        ObjectCaptureView(session: session)
    }
}

enum GccObjectCapture {
    /// Called once from `AppDelegate.didFinishLaunching`. Registered only on
    /// capable hardware (LiDAR + A14+) — absence = feature hidden in Kotlin.
    static func register() {
        // Called from didFinishLaunching (main thread); isSupported is
        // MainActor-isolated.
        MainActor.assumeIsolated {
            guard ObjectCaptureSession.isSupported else { return }
            ObjectCaptureRegistry.shared.factory = { ObjectCaptureBridgeImpl() }
        }
    }
}
