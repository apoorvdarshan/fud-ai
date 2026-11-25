import Foundation
import Testing
import UIKit
@testable import calorietracker

/// Opt-in local smoke test. Place the pinned, verified E2B model in Documents/GemmaSmoke
/// in an isolated Debug simulator. CI does not download or exercise the large model.
@MainActor
struct LocalGemmaRuntimeSmokeTests {
    private nonisolated static var root: URL {
        URL.documentsDirectory.appending(path: "GemmaSmoke", directoryHint: .isDirectory)
    }

    @Test(.enabled(if: FileManager.default.fileExists(atPath: root.appending(path: "verified.sha256").path)))
    func pinnedRuntimeProducesAReadablePhotoMealOffline() async throws {
        let manager = Gemma4LocalModelManager(rootDirectory: Self.root)
        #expect(manager.isDownloaded)
        let image = UIGraphicsImageRenderer(size: CGSize(width: 384, height: 384)).image { context in
            UIColor.darkGray.setFill()
            context.fill(CGRect(x: 0, y: 0, width: 384, height: 384))
            UIColor.white.setFill()
            context.cgContext.fillEllipse(in: CGRect(x: 35, y: 35, width: 314, height: 314))
            UIColor(red: 0.83, green: 0.66, blue: 0.40, alpha: 1).setFill()
            context.cgContext.fillEllipse(in: CGRect(x: 88, y: 88, width: 208, height: 208))
        }
        let jpeg = try GeminiService.encodedJPEGData(for: image)
        let prompt = """
        The photo is a diagram of a 150 g bowl of cooked oatmeal. Use the description to estimate the meal.
        Return one JSON object with name, calories, protein, carbs, fat, serving_size_grams, ingredients, and unit_options.
        \(CanonicalFoodAllergens.prompt)
        """
        let output = try await GeminiService.localGemmaResponse(prompt: prompt, expectsJSON: true) {
            try await manager.generate(prompt: $0, images: [jpeg], maxOutputTokens: 1024)
        }
        let analysis = try GeminiService.parseFoodAnalysis(from: output)
        #expect(!analysis.name.isEmpty)
        #expect(analysis.calories > 0)
    }
}
