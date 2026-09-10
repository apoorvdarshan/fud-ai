import Foundation

enum OpenFoodFactsService {
    struct LookupResult {
        let analysis: GeminiService.FoodAnalysis
        let productImageData: Data?
    }

    enum LookupError: LocalizedError {
        case invalidBarcode
        case productNotFound
        case missingNutrition
        case rateLimited
        case serviceUnavailable
        case invalidResponse
        case offline
        case timeout
        case networkError(Error)

        var errorDescription: String? {
            switch self {
            case .invalidBarcode:
                String(
                    localized: "barcode.lookup.invalid_barcode",
                    defaultValue: "That barcode could not be read. Try scanning it again.",
                    table: "BarcodeLookup",
                    comment: "Error shown when the scanned barcode is empty or cannot form a safe request."
                )
            case .productNotFound:
                String(
                    localized: "barcode.lookup.product_not_found",
                    defaultValue: "Product not found in Open Food Facts. Scan the nutrition label instead.",
                    table: "BarcodeLookup",
                    comment: "Error shown when Open Food Facts does not contain the scanned product."
                )
            case .missingNutrition:
                String(
                    localized: "barcode.lookup.missing_nutrition",
                    defaultValue: "This barcode was found, but nutrition data is incomplete. Scan the nutrition label instead.",
                    table: "BarcodeLookup",
                    comment: "Error shown when a barcode exists but has no usable calorie or macronutrient data."
                )
            case .rateLimited:
                String(
                    localized: "barcode.lookup.rate_limited",
                    defaultValue: "Too many barcode lookups. Wait a moment and try again.",
                    table: "BarcodeLookup",
                    comment: "Error shown when Open Food Facts temporarily rate-limits barcode requests."
                )
            case .serviceUnavailable:
                String(
                    localized: "barcode.lookup.service_unavailable",
                    defaultValue: "Open Food Facts is temporarily unavailable. Try again later.",
                    table: "BarcodeLookup",
                    comment: "Error shown when the Open Food Facts service returns a server error."
                )
            case .invalidResponse:
                String(
                    localized: "barcode.lookup.invalid_response",
                    defaultValue: "Open Food Facts returned an unexpected response.",
                    table: "BarcodeLookup",
                    comment: "Error shown when a barcode response cannot be understood."
                )
            case .offline:
                String(localized: "barcode.lookup.offline", defaultValue: "You appear to be offline. Check your connection and try scanning again.", table: "BarcodeLookup", comment: "Barcode lookup offline guidance")
            case .timeout:
                String(localized: "barcode.lookup.timeout", defaultValue: "Open Food Facts took too long to respond. Try scanning again.", table: "BarcodeLookup", comment: "Barcode lookup timed out")
            case .networkError:
                String(
                    localized: "barcode.lookup.network_error",
                    defaultValue: "Barcode lookup failed. Check your connection and try again.",
                    table: "BarcodeLookup",
                    comment: "Error shown when a barcode request fails because of a network problem."
                )
            }
        }
    }

    static func lookup(
        barcode: String,
        session: URLSession = .shared
    ) async throws -> GeminiService.FoodAnalysis {
        let code = barcode.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let url = requestURL(for: code) else {
            throw LookupError.invalidBarcode
        }

        var request = URLRequest(url: url)
        request.setValue(userAgent, forHTTPHeaderField: "User-Agent")
        request.setValue("application/json", forHTTPHeaderField: "Accept")

        do {
            let (data, response) = try await session.data(for: request)
            guard let http = response as? HTTPURLResponse else {
                throw LookupError.invalidResponse
            }

            switch http.statusCode {
            case 404:
                // The v2 endpoint returns a useful status: 0 JSON body with HTTP 404
                // for a valid barcode that is not in the database.
                throw LookupError.productNotFound
            case 429:
                throw LookupError.rateLimited
            case 500..<600:
                throw LookupError.serviceUnavailable
            case 200..<300:
                break
            default:
                throw LookupError.invalidResponse
            }

            let decoded: OpenFoodFactsResponse
            do {
                decoded = try JSONDecoder().decode(OpenFoodFactsResponse.self, from: data)
            } catch {
                throw LookupError.invalidResponse
            }

            guard decoded.status != 0, let product = decoded.product else {
                throw LookupError.productNotFound
            }

            return try analysis(from: product, barcode: code)
        } catch let error as LookupError {
            throw error
        } catch is CancellationError {
            throw CancellationError()
        } catch let error as URLError where error.code == .cancelled {
            throw CancellationError()
        } catch {
            switch AIErrorKind.network(error) {
            case .offline: throw LookupError.offline
            case .timeout: throw LookupError.timeout
            default: throw LookupError.networkError(error)
            }
        }
    }

    static func lookupWithImage(
        barcode: String,
        session: URLSession = .shared
    ) async throws -> LookupResult {
        let analysis = try await lookup(barcode: barcode, session: session)
        let imageData = try await productImageData(
            from: analysis.productMetadata?.imageURL,
            session: session
        )
        return LookupResult(analysis: analysis, productImageData: imageData)
    }

    private static let fields = [
        "product_name", "generic_name", "brands", "quantity",
        "product_quantity", "product_quantity_unit", "serving_size", "serving_quantity",
        "nutriments", "ingredients_text", "allergens_tags", "traces_tags",
        "nutriscore_grade", "nova_group", "ecoscore_grade", "labels_tags",
        "categories_tags", "image_front_url",
    ].joined(separator: ",")

    private static func requestURL(for code: String) -> URL? {
        guard !code.isEmpty,
              code.utf8.count <= 24,
              code.utf8.allSatisfy({ (48...57).contains($0) }),
              var components = URLComponents(
                string: "https://world.openfoodfacts.org/api/v2/product/\(code).json"
              )
        else {
            return nil
        }

        components.queryItems = [URLQueryItem(name: "fields", value: fields)]
        if let languageCode = Locale.current.language.languageCode?.identifier {
            components.queryItems?.append(URLQueryItem(name: "lc", value: languageCode))
        }
        return components.url
    }

    private static var userAgent: String {
        let version = Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "1.0"
        return "FudAI/\(version) (https://fud-ai.app)"
    }

    private static func analysis(
        from product: OpenFoodFactsProduct,
        barcode: String
    ) throws -> GeminiService.FoodAnalysis {
        guard let nutriments = product.nutriments else { throw LookupError.missingNutrition }

        let servingGrams = max(
            product.servingQuantity?.value ?? grams(from: product.servingSize) ?? 100,
            1
        )
        let scale = servingGrams / 100

        let calories = servingValue("energy-kcal", in: nutriments, scale: scale)
            ?? servingValue("energy", in: nutriments, scale: scale).map { $0 * 0.23900573614 }
        let protein = servingValue("proteins", in: nutriments, scale: scale)
        let carbs = servingValue("carbohydrates", in: nutriments, scale: scale)
        let fat = servingValue("fat", in: nutriments, scale: scale)

        guard calories != nil || protein != nil || carbs != nil || fat != nil else {
            throw LookupError.missingNutrition
        }

        let name = productName(from: product, barcode: barcode)
        let servingOption = ServingUnitOption(unit: "serving", gramsPerUnit: servingGrams, quantity: 1)
        var servingOptions = [servingOption]
        if let packageGrams = packageGrams(from: product),
           abs(packageGrams - servingGrams) > 0.01 {
            servingOptions.append(ServingUnitOption(unit: "package", gramsPerUnit: packageGrams, quantity: 1))
        }
        let novaGroup = product.novaGroup
            .map { Int($0.value.rounded()) }
            .flatMap { (1...4).contains($0) ? $0 : nil }
        let metadata = FoodProductMetadata(
            barcode: barcode,
            packageQuantity: firstNonEmpty(product.quantity),
            ingredientsText: firstNonEmpty(product.ingredientsText),
            allergens: displayTags(product.allergenTags, limit: 16),
            traces: displayTags(product.traceTags, limit: 16),
            nutriScore: normalizedScore(product.nutriScore),
            novaGroup: novaGroup,
            ecoScore: normalizedScore(product.ecoScore),
            labels: displayTags(product.labelTags, limit: 12),
            categories: displayTags(product.categoryTags, limit: 8),
            imageURL: product.imageFrontURL.flatMap(URL.init(string:))
        )

        return GeminiService.FoodAnalysis(
            name: name,
            calories: Int(round(calories ?? 0)),
            protein: protein ?? 0,
            carbs: carbs ?? 0,
            fat: fat ?? 0,
            servingSizeGrams: servingGrams,
            emoji: "🏷️",
            sugar: rounded(servingValue("sugars", in: nutriments, scale: scale)),
            addedSugar: rounded(servingValue("added-sugars", in: nutriments, scale: scale)),
            fiber: rounded(servingValue("fiber", in: nutriments, scale: scale)),
            saturatedFat: rounded(servingValue("saturated-fat", in: nutriments, scale: scale)),
            monounsaturatedFat: rounded(servingValue("monounsaturated-fat", in: nutriments, scale: scale)),
            polyunsaturatedFat: rounded(servingValue("polyunsaturated-fat", in: nutriments, scale: scale)),
            cholesterol: milligrams(servingValue("cholesterol", in: nutriments, scale: scale)),
            caffeine: milligrams(servingValue("caffeine", in: nutriments, scale: scale)),
            supplementalNutrients: Dictionary(uniqueKeysWithValues: SupplementalNutrient.allCases.compactMap { nutrient in
                rounded(servingValue(nutrient.jsonKey.replacingOccurrences(of: "_", with: "-"), in: nutriments, scale: scale))
                    .map { (nutrient.rawValue, $0) }
            }),
            sodium: milligrams(servingValue("sodium", in: nutriments, scale: scale)),
            potassium: milligrams(servingValue("potassium", in: nutriments, scale: scale)),
            transFat: rounded(servingValue("trans-fat", in: nutriments, scale: scale)),
            calcium: milligrams(servingValue("calcium", in: nutriments, scale: scale)),
            iron: milligrams(servingValue("iron", in: nutriments, scale: scale)),
            magnesium: milligrams(servingValue("magnesium", in: nutriments, scale: scale)),
            zinc: milligrams(servingValue("zinc", in: nutriments, scale: scale)),
            vitaminA: micrograms(servingValue("vitamin-a", in: nutriments, scale: scale)),
            vitaminC: milligrams(servingValue("vitamin-c", in: nutriments, scale: scale)),
            vitaminD: micrograms(servingValue("vitamin-d", in: nutriments, scale: scale)),
            vitaminB12: micrograms(servingValue("vitamin-b12", in: nutriments, scale: scale)),
            vitaminE: milligrams(servingValue("vitamin-e", in: nutriments, scale: scale)),
            vitaminK: micrograms(servingValue("vitamin-k", in: nutriments, scale: scale)),
            folate: micrograms(servingValue("folates", in: nutriments, scale: scale)),
            omega3: rounded(servingValue("omega-3-fat", in: nutriments, scale: scale)),
            servingUnitOptions: servingOptions,
            selectedServingUnit: servingOption.unit,
            selectedServingQuantity: 1,
            productMetadata: metadata
        )
    }

    private static func servingValue(
        _ key: String,
        in nutriments: OpenFoodFactsNutriments,
        scale: Double
    ) -> Double? {
        if let serving = nutriments.value(for: "\(key)_serving") {
            return serving
        }
        if let per100g = nutriments.value(for: "\(key)_100g") {
            return per100g * scale
        }
        return nil
    }

    private static func productName(from product: OpenFoodFactsProduct, barcode: String) -> String {
        let primary = firstNonEmpty(product.productName, product.genericName)
        let brand = product.brands?
            .split(separator: ",")
            .first
            .map { String($0).trimmingCharacters(in: .whitespacesAndNewlines) }

        if let primary, let brand, !primary.localizedCaseInsensitiveContains(brand) {
            return "\(brand) \(primary)"
        }
        return primary ?? brand ?? String(
            localized: "barcode.product.fallback_name",
            defaultValue: "Barcode \(barcode)",
            table: "BarcodeLookup",
            comment: "Fallback food name. The placeholder is the scanned barcode."
        )
    }

    private static func firstNonEmpty(_ values: String?...) -> String? {
        values
            .compactMap { $0?.trimmingCharacters(in: .whitespacesAndNewlines) }
            .first { !$0.isEmpty }
    }

    private static func packageGrams(from product: OpenFoodFactsProduct) -> Double? {
        if let value = product.productQuantity?.value,
           let unit = product.productQuantityUnit?.lowercased() {
            switch unit {
            case "kg": return value * 1_000
            case "mg": return value / 1_000
            case "g": return value
            case "l": return value * 1_000
            case "ml": return value
            default: break
            }
        }
        guard let quantity = product.quantity?.trimmingCharacters(in: .whitespacesAndNewlines),
              quantity.range(
                of: #"^[0-9]+(?:[.,][0-9]+)?\s*(?:kg|mg|g|oz|ml|l)$"#,
                options: [.regularExpression, .caseInsensitive]
              ) != nil
        else { return nil }
        return grams(from: quantity)
    }

    private static func displayTags(_ tags: [String]?, limit: Int) -> [String] {
        var seen = Set<String>()
        return (tags ?? []).compactMap { raw in
            let withoutLanguage = raw.replacingOccurrences(
                of: #"^[a-z]{2}:"#,
                with: "",
                options: .regularExpression
            )
            let display = withoutLanguage
                .replacingOccurrences(of: "-", with: " ")
                .replacingOccurrences(of: "_", with: " ")
                .trimmingCharacters(in: .whitespacesAndNewlines)
            guard !display.isEmpty else { return nil }
            let key = display.lowercased()
            guard seen.insert(key).inserted else { return nil }
            return display.localizedCapitalized
        }.prefix(limit).map(\.self)
    }

    private static func normalizedScore(_ value: String?) -> String? {
        guard let value = firstNonEmpty(value) else { return nil }
        let normalized = value.lowercased()
        guard normalized != "unknown", normalized != "not-applicable" else { return nil }
        return value.uppercased()
    }

    private static func productImageData(from url: URL?, session: URLSession) async throws -> Data? {
        guard let url,
              url.scheme == "https",
              url.host == "images.openfoodfacts.org"
        else { return nil }

        var request = URLRequest(url: url)
        request.setValue(userAgent, forHTTPHeaderField: "User-Agent")
        request.setValue("image/*", forHTTPHeaderField: "Accept")

        let data: Data
        let response: URLResponse
        do {
            (data, response) = try await session.data(for: request)
        } catch let error as URLError where error.code == .cancelled {
            throw CancellationError()
        } catch is CancellationError {
            throw CancellationError()
        } catch {
            return nil
        }

        guard let http = response as? HTTPURLResponse,
              (200..<300).contains(http.statusCode),
              http.mimeType?.hasPrefix("image/") == true,
              data.count <= 5_000_000
        else { return nil }
        return data
    }

    private static func rounded(_ value: Double?) -> Double? {
        value.map { round($0 * 10) / 10 }
    }

    private static func milligrams(_ grams: Double?) -> Double? {
        grams.map { round($0 * 1000 * 10) / 10 }
    }

    private static func micrograms(_ grams: Double?) -> Double? {
        grams.map { round($0 * 1_000_000 * 10) / 10 }
    }

    private static func grams(from servingSize: String?) -> Double? {
        guard var text = servingSize?.lowercased() else { return nil }
        text = text.replacingOccurrences(of: ",", with: ".")
        text = text.replacingOccurrences(of: "fl. oz", with: "fl oz")

        let pattern = #"([0-9]+(?:\.[0-9]+)?)\s*(fl oz|kg|mg|g|oz|ml|l)"#
        guard let regex = try? NSRegularExpression(pattern: pattern),
              let match = regex.firstMatch(in: text, range: NSRange(text.startIndex..., in: text)),
              let valueRange = Range(match.range(at: 1), in: text),
              let unitRange = Range(match.range(at: 2), in: text),
              let value = Double(text[valueRange])
        else {
            return nil
        }

        switch String(text[unitRange]) {
        case "kg": return value * 1000
        case "mg": return value / 1000
        case "oz": return value * 28.3495
        case "fl oz": return value * 29.5735
        case "ml": return value
        case "l": return value * 1000
        default: return value
        }
    }

    private struct OpenFoodFactsResponse: Decodable {
        let status: Int?
        let product: OpenFoodFactsProduct?
    }

    private struct OpenFoodFactsProduct: Decodable {
        let productName: String?
        let genericName: String?
        let brands: String?
        let servingSize: String?
        let servingQuantity: FlexibleDouble?
        let nutriments: OpenFoodFactsNutriments?
        let quantity: String?
        let productQuantity: FlexibleDouble?
        let productQuantityUnit: String?
        let ingredientsText: String?
        let allergenTags: [String]?
        let traceTags: [String]?
        let nutriScore: String?
        let novaGroup: FlexibleDouble?
        let ecoScore: String?
        let labelTags: [String]?
        let categoryTags: [String]?
        let imageFrontURL: String?

        private enum CodingKeys: String, CodingKey {
            case productName = "product_name"
            case genericName = "generic_name"
            case brands
            case servingSize = "serving_size"
            case servingQuantity = "serving_quantity"
            case nutriments
            case quantity
            case productQuantity = "product_quantity"
            case productQuantityUnit = "product_quantity_unit"
            case ingredientsText = "ingredients_text"
            case allergenTags = "allergens_tags"
            case traceTags = "traces_tags"
            case nutriScore = "nutriscore_grade"
            case novaGroup = "nova_group"
            case ecoScore = "ecoscore_grade"
            case labelTags = "labels_tags"
            case categoryTags = "categories_tags"
            case imageFrontURL = "image_front_url"
        }
    }

    private struct OpenFoodFactsNutriments: Decodable {
        private let values: [String: Double]

        func value(for key: String) -> Double? {
            values[key]
        }

        init(from decoder: Decoder) throws {
            let container = try decoder.container(keyedBy: DynamicCodingKey.self)
            var parsed: [String: Double] = [:]

            for key in container.allKeys {
                if let value = try? container.decode(FlexibleDouble.self, forKey: key) {
                    parsed[key.stringValue] = value.value
                }
            }

            values = parsed
        }
    }

    private struct FlexibleDouble: Decodable {
        let value: Double

        init(from decoder: Decoder) throws {
            let container = try decoder.singleValueContainer()
            if let double = try? container.decode(Double.self) {
                value = double
            } else if let int = try? container.decode(Int.self) {
                value = Double(int)
            } else {
                let string = try container.decode(String.self)
                    .trimmingCharacters(in: .whitespacesAndNewlines)
                    .replacingOccurrences(of: ",", with: ".")
                guard let parsed = Double(string) else {
                    throw DecodingError.dataCorruptedError(in: container, debugDescription: "Not a number")
                }
                value = parsed
            }
        }
    }

    private struct DynamicCodingKey: CodingKey {
        let stringValue: String
        let intValue: Int?

        init?(stringValue: String) {
            self.stringValue = stringValue
            self.intValue = nil
        }

        init?(intValue: Int) {
            self.stringValue = "\(intValue)"
            self.intValue = intValue
        }
    }
}
