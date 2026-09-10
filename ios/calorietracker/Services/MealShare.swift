import Foundation
import UIKit

/// Encodes/decodes a logged meal into a `fudai://add-meal?d=<base64url>` deep link so it
/// can be shared (AirDrop, Messages, any app) and imported directly into Fud AI — including
/// cross-platform. The payload schema is byte-identical to the Android `MealShare`, so a
/// link produced on one platform imports on the other.
enum MealShare {
    static let scheme = "fudai"
    static let host = "add-meal"
    /// Universal Link host + path — tapping this in a messenger opens the app directly
    /// (verified via /.well-known/apple-app-site-association), no browser.
    static let webHost = "www.fud-ai.app"
    static let webPath = "/add-meal"
    private static let version = 1
    private static let shareSession: URLSession = {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.timeoutIntervalForRequest = 5
        configuration.timeoutIntervalForResource = 5
        configuration.waitsForConnectivity = false
        return URLSession(configuration: configuration)
    }()

    // MARK: - Encode

    /// A shareable link carrying every entry's nutrients (no image). Uses an https://fud-ai.app
    /// URL so messengers (WhatsApp etc.) make it tappable — the page then opens the app via the
    /// fudai://add-meal scheme. `d` is byte-identical to the scheme link, so import is unchanged.
    static func link(for entries: [FoodEntry]) -> URL? {
        let payload: [String: Any] = ["v": version, "meals": entries.map(mealDict)]
        guard JSONSerialization.isValidJSONObject(payload),
              let data = try? JSONSerialization.data(withJSONObject: payload),
              !data.isEmpty else { return nil }
        var comps = URLComponents()
        comps.scheme = "https"
        comps.host = webHost
        comps.path = webPath
        comps.queryItems = [URLQueryItem(name: "d", value: base64url(data))]
        return comps.url
    }

    private static let shareUserAgent = "FudAI/1.0 (iOS; MealShare)"

    /// Try the short-link service, falling back to the self-contained link on any failure.
    static func preferredLink(
        for entries: [FoodEntry],
        send: (URLRequest) async throws -> (Data, URLResponse) = { try await shareSession.data(for: $0) }
    ) async -> URL? {
        guard let fallback = link(for: entries),
              let encoded = URLComponents(url: fallback, resolvingAgainstBaseURL: false)?.queryItems?.first(where: { $0.name == "d" })?.value,
              let payload = data(fromBase64url: encoded) else { return nil }
        var request = URLRequest(url: URL(string: "https://\(webHost)/api/meal-shares")!)
        request.httpMethod = "POST"
        request.timeoutInterval = 5
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue(shareUserAgent, forHTTPHeaderField: "User-Agent")
        request.httpBody = payload
        // One attempt only: Worker 429s use Retry-After: 60, so an immediate retry
        // still fails and only delays the share sheet.
        do {
            let (data, response) = try await send(request)
            let status = (response as? HTTPURLResponse)?.statusCode ?? 0
            guard status == 201,
                  let result = try JSONSerialization.jsonObject(with: data) as? [String: Any],
                  let value = result["url"] as? String,
                  let url = URL(string: value),
                  url.scheme == "https", url.host == webHost,
                  url.user == nil, url.password == nil, url.port == nil,
                  url.query == nil, url.fragment == nil,
                  url.path.range(of: "^/m/[A-Za-z0-9_-]{22}$", options: .regularExpression) != nil
            else { return fallback }
            return url
        } catch { return fallback }
    }

    /// Human-readable summary plus the import link — the text put on the share sheet.
    static func shareText(for entries: [FoodEntry], using shareLink: URL? = nil) -> String {
        var lines = entries.map { e -> String in
            let macros = "\(Int(e.protein.rounded()))P · \(Int(e.carbs.rounded()))C · \(Int(e.fat.rounded()))F"
            let prefix = e.emoji.map { "\($0) " } ?? ""
            return "\(prefix)\(e.name) — \(e.calories) kcal · \(macros)"
        }
        if let link = shareLink ?? link(for: entries) {
            lines.append("")
            lines.append("Open in Fud AI to add:")
            lines.append(link.absoluteString)
        }
        return lines.joined(separator: "\n")
    }

    private nonisolated static func mealDict(_ e: FoodEntry) -> [String: Any] {
        var d: [String: Any] = [
            "name": e.name,
            "calories": e.calories,
            "protein": e.protein,
            "carbs": e.carbs,
            "fat": e.fat,
            "mealType": e.mealType.rawValue,
        ]
        if let emoji = e.emoji { d["emoji"] = emoji }
        func put(_ key: String, _ v: Double?) { if let v { d[key] = v } }
        put("sugar", e.sugar); put("addedSugar", e.addedSugar); put("fiber", e.fiber)
        put("saturatedFat", e.saturatedFat); put("monounsaturatedFat", e.monounsaturatedFat)
        put("polyunsaturatedFat", e.polyunsaturatedFat); put("cholesterol", e.cholesterol)
        put("caffeine", e.caffeine); put("sodium", e.sodium); put("potassium", e.potassium); put("transFat", e.transFat)
        if !e.supplementalNutrients.isEmpty { d["supplementalNutrients"] = e.supplementalNutrients }
        put("calcium", e.calcium); put("iron", e.iron); put("magnesium", e.magnesium); put("zinc", e.zinc)
        put("vitaminA", e.vitaminA); put("vitaminC", e.vitaminC); put("vitaminD", e.vitaminD)
        put("vitaminB12", e.vitaminB12); put("vitaminE", e.vitaminE); put("vitaminK", e.vitaminK)
        put("folate", e.folate); put("omega3", e.omega3)
        put("servingSizeGrams", e.servingSizeGrams)
        if !e.servingUnitOptions.isEmpty {
            d["servingUnitOptions"] = e.servingUnitOptions.map { option in
                var value: [String: Any] = [
                    "unit": option.unit,
                    "gramsPerUnit": option.gramsPerUnit,
                ]
                if let quantity = option.quantity { value["quantity"] = quantity }
                return value
            }
        }
        if let unit = e.selectedServingUnit { d["selectedServingUnit"] = unit }
        put("selectedServingQuantity", e.selectedServingQuantity)
        if let note = e.customNote { d["customNote"] = note }
        if e.progressiveMeal { d["progressiveMeal"] = true }
        if !e.ingredients.isEmpty {
            d["ingredients"] = e.ingredients.map { ingredient in
                [
                    "name": ingredient.name,
                    "grams": ingredient.grams,
                    "calories": ingredient.calories,
                    "protein": ingredient.protein,
                    "carbs": ingredient.carbs,
                    "fat": ingredient.fat,
                ] as [String: Any]
            }
        }
        return d
    }

    // MARK: - Decode

    /// True for both the custom scheme and the https Universal Link that carry a shared meal.
    static func handles(_ url: URL) -> Bool {
        if url.scheme == scheme, url.host == host { return true }
        if url.scheme == "https",
           url.host == webHost || url.host == "fud-ai.app",
           url.path == webPath { return true }
        return false
    }

    /// Parse a shared-meal link (custom scheme or https Universal Link) back into fresh
    /// `FoodEntry` values (new ids, logged now).
    static func meals(from url: URL) -> [FoodEntry]? {
        guard handles(url),
              let comps = URLComponents(url: url, resolvingAgainstBaseURL: false),
              let encoded = comps.queryItems?.first(where: { $0.name == "d" })?.value,
              let data = data(fromBase64url: encoded),
              let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let mealsArr = obj["meals"] as? [[String: Any]]
        else { return nil }
        let entries = mealsArr.compactMap(entry(from:))
        return entries.isEmpty ? nil : entries
    }

    private nonisolated static func entry(from d: [String: Any]) -> FoodEntry? {
        guard let name = (d["name"] as? String), !name.isEmpty,
              let calories = (d["calories"] as? NSNumber)?.intValue else { return nil }
        func dbl(_ k: String) -> Double? { (d[k] as? NSNumber)?.doubleValue }
        let meal = MealType(rawValue: (d["mealType"] as? String) ?? "") ?? .currentMeal
        let ingredients = (d["ingredients"] as? [[String: Any]] ?? []).compactMap { item -> MealIngredient? in
            guard let itemName = (item["name"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines),
                  !itemName.isEmpty,
                  let grams = (item["grams"] as? NSNumber)?.doubleValue,
                  grams > 0
            else { return nil }
            return MealIngredient(
                name: itemName,
                grams: grams,
                calories: (item["calories"] as? NSNumber)?.intValue ?? 0,
                protein: (item["protein"] as? NSNumber)?.doubleValue ?? 0,
                carbs: (item["carbs"] as? NSNumber)?.doubleValue ?? 0,
                fat: (item["fat"] as? NSNumber)?.doubleValue ?? 0
            )
        }
        let servingUnitOptions = (d["servingUnitOptions"] as? [[String: Any]] ?? []).compactMap { item -> ServingUnitOption? in
            guard let unit = (item["unit"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines),
                  !unit.isEmpty,
                  let gramsPerUnit = (item["gramsPerUnit"] as? NSNumber)?.doubleValue,
                  gramsPerUnit > 0
            else { return nil }
            return ServingUnitOption(
                unit: unit,
                gramsPerUnit: gramsPerUnit,
                quantity: (item["quantity"] as? NSNumber)?.doubleValue
            )
        }
        return FoodEntry(
            name: name,
            calories: calories,
            protein: dbl("protein") ?? 0,
            carbs: dbl("carbs") ?? 0,
            fat: dbl("fat") ?? 0,
            emoji: d["emoji"] as? String,
            source: .manual,
            mealType: meal,
            sugar: dbl("sugar"), addedSugar: dbl("addedSugar"), fiber: dbl("fiber"),
            saturatedFat: dbl("saturatedFat"), monounsaturatedFat: dbl("monounsaturatedFat"),
            polyunsaturatedFat: dbl("polyunsaturatedFat"), cholesterol: dbl("cholesterol"),
            caffeine: dbl("caffeine"),
            supplementalNutrients: (d["supplementalNutrients"] as? [String: Any])?.compactMapValues { value in
                (value as? NSNumber)?.doubleValue
            } ?? [:],
            sodium: dbl("sodium"), potassium: dbl("potassium"), transFat: dbl("transFat"),
            calcium: dbl("calcium"), iron: dbl("iron"), magnesium: dbl("magnesium"), zinc: dbl("zinc"),
            vitaminA: dbl("vitaminA"), vitaminC: dbl("vitaminC"), vitaminD: dbl("vitaminD"),
            vitaminB12: dbl("vitaminB12"), vitaminE: dbl("vitaminE"), vitaminK: dbl("vitaminK"),
            folate: dbl("folate"), omega3: dbl("omega3"),
            servingSizeGrams: dbl("servingSizeGrams"),
            servingUnitOptions: servingUnitOptions,
            selectedServingUnit: d["selectedServingUnit"] as? String,
            selectedServingQuantity: dbl("selectedServingQuantity"),
            customNote: d["customNote"] as? String,
            progressiveMeal: (d["progressiveMeal"] as? Bool) ?? false,
            ingredients: ingredients
        )
    }

    // MARK: - Share sheet

    /// Present the system share sheet directly via UIKit (avoids the blank-page issue of a
    /// SwiftUI `.sheet` nested inside another sheet).
    static func presentShareSheet(for entries: [FoodEntry]) {
        guard !entries.isEmpty else { return }
        Task { @MainActor in
            let link = await preferredLink(for: entries)
            guard !Task.isCancelled else { return }
            presentShareText(shareText(for: entries, using: link))
        }
    }

    private static func presentShareText(_ text: String) {
        let av = UIActivityViewController(activityItems: [text], applicationActivities: nil)
        guard let scene = UIApplication.shared.connectedScenes.first(where: { $0.activationState == .foregroundActive }) as? UIWindowScene,
              let root = scene.windows.first(where: { $0.isKeyWindow })?.rootViewController else { return }
        var top = root
        while let presented = top.presentedViewController { top = presented }
        av.popoverPresentationController?.sourceView = top.view
        av.popoverPresentationController?.sourceRect = CGRect(x: top.view.bounds.midX, y: top.view.bounds.midY, width: 0, height: 0)
        av.popoverPresentationController?.permittedArrowDirections = []
        top.present(av, animated: true)
    }

    // MARK: - base64url helpers

    private static func base64url(_ data: Data) -> String {
        data.base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }

    private static func data(fromBase64url s: String) -> Data? {
        var b64 = s.replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")
        let pad = b64.count % 4
        if pad > 0 { b64 += String(repeating: "=", count: 4 - pad) }
        return Data(base64Encoded: b64)
    }
}
