import CoreImage
import CoreML
import Vision

/// Stage-2 purpose-trained litter crop classifier (Blake 2026-07-28: "an
/// effective pattern recognition algo" — binary/typed litter recognition
/// trained on TACO + the app's own labelled field crops, not Apple's generic
/// scene taxonomy).
///
/// GRACEFUL SLOT: activates only when the compiled model
/// (`LitterCropClassifier.mlmodelc` / `.mlpackage` compiled at build time)
/// ships in the app bundle. No model = `isAvailable` false and the AR
/// pipeline behaves exactly as before — the app does NOT wait on training.
/// Drop the trained model into the Xcode project (target membership iosApp)
/// and this class starts feeding purpose-trained labels into the exact same
/// decision seam the Apple classifier uses today.
///
/// Model contract (scripts/litter_model/export_coreml.py):
///  - image classification, class labels = canonical GCC item types
///    ("plastic_bottle", "can", "trash_bag", …) plus "background"
///  - outputs ride the same "identifier|confidence" packet strings as
///    `classifyRegionLabels`, PREPENDED so the purpose-trained verdict
///    outranks the generic taxonomy in Kotlin's first-match rules
///    (GroundExclusionRules / TrashDetectionRules).
final class LitterCropClassifier {

    static let shared = LitterCropClassifier()

    private let model: VNCoreMLModel?

    private init() {
        // Accept either a pre-compiled .mlmodelc (checked-in artifact) or a
        // .mlpackage Xcode compiled into .mlmodelc under the same name.
        guard let url = Bundle.main.url(forResource: "LitterCropClassifier", withExtension: "mlmodelc"),
              let ml = try? MLModel(contentsOf: url),
              let vn = try? VNCoreMLModel(for: ml) else {
            model = nil
            return
        }
        model = vn
    }

    var isAvailable: Bool { model != nil }

    /// Top labels for an already-cropped CIImage, in the shared
    /// "identifier|confidence" packet format. Empty when no model shipped or
    /// inference failed (callers fall through to the Apple classifier alone).
    func classify(cropped: CIImage, top: Int = 5) -> [String] {
        guard let model else { return [] }
        let request = VNCoreMLRequest(model: model)
        request.imageCropAndScaleOption = .scaleFill
        let handler = VNImageRequestHandler(ciImage: cropped, options: [:])
        guard (try? handler.perform([request])) != nil else { return [] }
        let results = (request.results as? [VNClassificationObservation]) ?? []
        return results
            .sorted { $0.confidence > $1.confidence }
            .prefix(top)
            .map { "\($0.identifier)|\($0.confidence)" }
    }
}
