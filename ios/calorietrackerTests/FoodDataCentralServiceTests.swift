import Foundation
import Testing
@testable import calorietracker

/// USDA FoodData Central as the second barcode source (#533). Responses come from
/// `FoodDataCentralFixtures`, a trimmed recording of a real Branded Foods search.
@Suite("USDA FoodData Central barcode lookup", .serialized)
@MainActor
struct FoodDataCentralServiceTests {
    private static let key = "fdc-test-key-123"

    @Test func exactGTINMatchMapsTheRecordedBrandedFood() async throws {
        let session = makeSession { request in json(request, 200, FoodDataCentralFixtures.cheeriosSearch) }
        defer { finish(session) }

        let found = try await FoodDataCentralService.lookup(barcode: " 016000275287 ", apiKey: Self.key, session: session)
        let result = try #require(found)

        // The first food in the response has another GTIN; only the exact match is used.
        #expect(result.name == "Cheerios Cereal")
        #expect(result.servingSizeGrams == 20)
        #expect(result.servingUnitOptions == [ServingUnitOption(unit: "serving", gramsPerUnit: 20, quantity: 1)])
        #expect(result.selectedServingUnit == "serving")
        // Branded values are per 100 g, scaled to the 20 g serving.
        #expect(result.calories == 72)
        #expect(abs(result.protein - 2.56) < 0.0001)
        #expect(abs(result.carbs - 14.88) < 0.0001)
        #expect(abs(result.fat - 1.282) < 0.0001)
        #expect(result.sugar == 1.0)
        #expect(result.addedSugar == 1.0)
        #expect(result.fiber == 2.1)
        #expect(result.saturatedFat == 0.3)
        #expect(result.transFat == 0)
        #expect(result.cholesterol == 0)
        #expect(result.sodium == 97.4)
        #expect(result.potassium == 128.2)
        #expect(result.calcium == 66.6)
        #expect(result.iron == 6.5)
        // FDC lists older label panels after the current one; the first entry wins.
        #expect(result.magnesium == 140)
        #expect(result.zinc == 5.2)
        #expect(result.vitaminC == 18)
        // Only International Units are given for vitamin D (205 IU x 0.025 µg, per 20 g).
        #expect(result.vitaminD == 1.0)
        #expect(result.vitaminA == nil)
        #expect(result.vitaminB12 == 1.5)
        #expect(result.folate == 100)

        let metadata = try #require(result.productMetadata)
        #expect(metadata.barcode == "016000275287")
        #expect(metadata.dataSource == FoodDataCentralService.dataSourceID)
        #expect(metadata.dataSourceName == "USDA FoodData Central")
        #expect(metadata.categories == ["Processed Cereal Products"])
        #expect(metadata.ingredientsText?.hasPrefix("WHOLE GRAIN OATS, CORN STARCH") == true)
        #expect(metadata.imageURL == nil)
    }

    @Test func keyIsSentOnlyInTheHeader() async throws {
        var captured: URLRequest?
        let session = makeSession { request in
            captured = request
            return json(request, 200, FoodDataCentralFixtures.cheeriosSearch)
        }
        defer { finish(session) }

        _ = try await FoodDataCentralService.lookup(barcode: "016000275287", apiKey: "  \(Self.key)\n", session: session)

        let request = try #require(captured)
        #expect(request.httpMethod == "POST")
        #expect(request.url == FoodDataCentralService.searchURL)
        #expect(request.url?.query == nil)
        #expect(request.url?.absoluteString.contains(Self.key) == false)
        #expect(request.value(forHTTPHeaderField: "X-Api-Key") == Self.key)
        #expect(request.timeoutInterval == FoodDataCentralService.requestTimeout)

        let body = try #require(bodyJSON(of: request))
        #expect(body["dataType"] as? [String] == ["Branded"])
        #expect(body["requireAllWords"] as? Bool == false)
        let words = (body["query"] as? String)?.split(separator: " ").map(String.init)
        #expect(words == ["016000275287", "16000275287", "0016000275287", "00016000275287"])
        #expect(String(data: try JSONSerialization.data(withJSONObject: body), encoding: .utf8)?.contains(Self.key) == false)
    }

    @Test func gtinVariantsCoverStrippedAndPaddedForms() {
        #expect(FoodDataCentralService.gtinQueryVariants(for: "016000275287") == [
            "016000275287", "16000275287", "0016000275287", "00016000275287",
        ])
        #expect(FoodDataCentralService.gtinQueryVariants(for: "40170725") == [
            "40170725", "000040170725", "0000040170725", "00000040170725",
        ])
        #expect(FoodDataCentralService.gtinQueryVariants(for: "0000000000000000123").first == "123")
        for invalid in ["", "abc", "12 34", "000000", "123456789012345", "١٢٣٤"] {
            #expect(FoodDataCentralService.gtinQueryVariants(for: invalid).isEmpty, "\(invalid)")
        }

        #expect(FoodDataCentralService.matchesGTIN("00016000275287", scanned: "016000275287"))
        #expect(FoodDataCentralService.matchesGTIN("016000275287", scanned: "0016000275287"))
        #expect(!FoodDataCentralService.matchesGTIN("00016000275288", scanned: "016000275287"))
        #expect(!FoodDataCentralService.matchesGTIN("160002752870", scanned: "016000275287"))
        #expect(!FoodDataCentralService.matchesGTIN(nil, scanned: "016000275287"))
    }

    @Test func foodsWithoutAnExactGTINAreNeverUsed() async throws {
        let session = makeSession { request in json(request, 200, FoodDataCentralFixtures.cheeriosSearch) }
        defer { finish(session) }

        let result = try await FoodDataCentralService.lookup(barcode: "016000275280", apiKey: Self.key, session: session)

        #expect(result == nil)
    }

    @Test func blankKeyOrNonGTINCodeSendsNothing() async throws {
        let session = makeSession { request in
            Issue.record("Nothing should be sent: \(request)")
            throw URLError(.badURL)
        }
        defer { finish(session) }

        let blankKey = try await FoodDataCentralService.lookup(barcode: "016000275287", apiKey: " \n", session: session)
        let letters = try await FoodDataCentralService.lookup(barcode: "ABC-123", apiKey: Self.key, session: session)
        let zeros = try await FoodDataCentralService.lookup(barcode: "0000", apiKey: Self.key, session: session)
        #expect(blankKey == nil)
        #expect(letters == nil)
        #expect(zeros == nil)
    }

    @Test func refusedKeyIsReportedAsInvalid() async {
        for status in [401, 403] {
            let session = makeSession { request in
                json(request, status, #"{"error":{"code":"API_KEY_INVALID","message":"An invalid api_key was supplied."}}"#)
            }
            await expectError(.invalidAPIKey, session: session)
            finish(session)
        }
    }

    @Test func rateLimitsServerErrorsBadBodiesAndNetworkFailuresAreUnavailable() async {
        let responses: [(Int, String)] = [
            (429, #"{"error":{"code":"OVER_RATE_LIMIT"}}"#),
            (503, "unavailable"),
            (200, "not json"),
            (200, #"{"totalHits":0}"#),
        ]
        for (status, body) in responses {
            let session = makeSession { request in json(request, status, body) }
            await expectError(.unavailable, session: session)
            finish(session)
        }
        for failure in [URLError(.timedOut), URLError(.notConnectedToInternet)] {
            let session = makeSession { _ in throw failure }
            await expectError(.unavailable, session: session)
            finish(session)
        }
    }

    @Test func cancellationRemainsCancellation() async {
        let session = makeSession { _ in throw URLError(.cancelled) }
        defer { finish(session) }

        do {
            _ = try await FoodDataCentralService.lookup(barcode: "016000275287", apiKey: Self.key, session: session)
            Issue.record("Expected cancellation")
        } catch is CancellationError {
        } catch {
            Issue.record("Expected CancellationError but received \(error)")
        }
    }

    @Test func labelNutrientsWinAndHouseholdTextGivesTheServingWeight() async throws {
        let session = makeSession { request in
            json(request, 200, #"""
            {"foods":[{
                "dataType":"Branded","gtinUpc":"041303020937","description":"GRANOLA BAR","brandOwner":"ACME FOODS",
                "servingSize":1,"servingSizeUnit":"PIECE","householdServingFullText":"1 bar (40 g)",
                "labelNutrients":{"calories":{"value":190},"protein":{"value":4},"sodium":{"value":95},"fat":{"value":7}},
                "foodNutrients":[
                    {"nutrientId":1008,"unitName":"KCAL","value":450},
                    {"nutrientId":1003,"unitName":"G","value":10},
                    {"nutrientId":1005,"unitName":"G","value":65},
                    {"nutrientId":1093,"unitName":"MG","value":300}
                ]
            }]}
            """#)
        }
        defer { finish(session) }

        let found = try await FoodDataCentralService.lookup(barcode: "041303020937", apiKey: Self.key, session: session)
        let result = try #require(found)

        #expect(result.name == "Acme Foods Granola Bar")
        #expect(result.servingSizeGrams == 40)
        #expect(result.calories == 190)
        #expect(result.protein == 4)
        #expect(result.fat == 7)
        #expect(result.sodium == 95)
        // No label value for carbs, so the per-100 g value is scaled to 40 g.
        #expect(abs(result.carbs - 26) < 0.0001)
    }

    @Test func productMetadataKeepsTheSourceAndDecodesOlderAndNewerLogs() throws {
        let legacy = #"{"barcode":"123","allergens":[],"traces":[],"labels":[],"categories":[]}"#
        let decoded = try JSONDecoder().decode(FoodProductMetadata.self, from: Data(legacy.utf8))
        #expect(decoded.dataSource == nil)
        #expect(decoded.dataSourceName == nil)

        var usda = decoded
        usda.dataSource = FoodDataCentralService.dataSourceID
        let roundTrip = try JSONDecoder().decode(FoodProductMetadata.self, from: JSONEncoder().encode(usda))
        #expect(roundTrip == usda)

        let future = #"{"barcode":"123","allergens":[],"traces":[],"labels":[],"categories":[],"dataSource":"someNewSource"}"#
        let newer = try JSONDecoder().decode(FoodProductMetadata.self, from: Data(future.utf8))
        #expect(newer.dataSource == "someNewSource")
        #expect(newer.dataSourceName == nil)
    }

    // MARK: - Helpers

    private func expectError(_ expected: FoodDataCentralService.LookupError, session: URLSession) async {
        do {
            _ = try await FoodDataCentralService.lookup(barcode: "016000275287", apiKey: Self.key, session: session)
            Issue.record("Expected \(expected)")
        } catch let error as FoodDataCentralService.LookupError {
            #expect(error == expected)
        } catch {
            Issue.record("Expected FoodDataCentralService.LookupError but received \(error)")
        }
    }
}

/// The order Open Food Facts, saved label (#485), then USDA (#533), against stubbed hosts.
@Suite("Barcode lookup order", .serialized)
@MainActor
struct BarcodeProductLookupTests {
    private static let key = "fdc-test-key-123"
    private static let code = "016000275287"

    @Test func openFoodFactsResultIsUsedWithoutAskingUSDA() async throws {
        var hosts: [String] = []
        let session = makeSession { request in
            hosts.append(request.url?.host ?? "")
            return json(request, 200, Self.openFoodFactsProduct)
        }
        defer { finish(session) }

        let outcome = try await lookup(session: session, key: Self.key)

        guard case .product(let result) = outcome else { Issue.record("Expected a product"); return }
        #expect(result.analysis.name == "Open Food Facts Oats")
        #expect(result.analysis.productMetadata?.dataSource == nil)
        #expect(hosts == ["world.openfoodfacts.org"])
    }

    @Test func savedLabelWinsOverUSDA() async throws {
        var hosts: [String] = []
        let session = makeSession { request in
            hosts.append(request.url?.host ?? "")
            return json(request, 404, #"{"status":0}"#)
        }
        defer { finish(session) }
        let label = FoodEntry(
            name: "My label",
            calories: 150,
            protein: 5,
            carbs: 20,
            fat: 4,
            source: .nutritionLabel,
            productMetadata: BarcodeLabelMemory.labelMetadata(nil, barcode: "0016000275287")
        )

        let outcome = try await lookup(session: session, key: Self.key, labels: [label])

        guard case .savedLabel(let entry) = outcome else { Issue.record("Expected the saved label"); return }
        #expect(entry.name == "My label")
        #expect(hosts == ["world.openfoodfacts.org"])
    }

    @Test func withoutAKeyNothingIsSentToUSDA() async {
        for key in [nil, "", "   "] as [String?] {
            var hosts: [String] = []
            let session = makeSession { request in
                hosts.append(request.url?.host ?? "")
                return json(request, 404, #"{"status":0}"#)
            }
            await expectOpenFoodFactsError(.productNotFound, session: session, key: key)
            #expect(hosts == ["world.openfoodfacts.org"])
            finish(session)
        }
    }

    @Test func usdaFillsInWhenOpenFoodFactsHasNoNutrition() async throws {
        var hosts: [String] = []
        let session = makeSession { request in
            hosts.append(request.url?.host ?? "")
            if request.url?.host == "api.nal.usda.gov" {
                #expect(request.value(forHTTPHeaderField: "X-Api-Key") == Self.key)
                return json(request, 200, FoodDataCentralFixtures.cheeriosSearch)
            }
            return json(request, 200, #"{"status":1,"product":{"product_name":"No nutrition"}}"#)
        }
        defer { finish(session) }

        let outcome = try await lookup(session: session, key: Self.key)

        guard case .product(let result) = outcome else { Issue.record("Expected a product"); return }
        #expect(result.analysis.name == "Cheerios Cereal")
        #expect(result.analysis.productMetadata?.dataSourceName == "USDA FoodData Central")
        #expect(result.productImageData == nil)
        #expect(hosts == ["world.openfoodfacts.org", "api.nal.usda.gov"])
    }

    @Test func usdaMissOrOutageKeepsTheOpenFoodFactsError() async {
        for (status, body) in [(200, #"{"foods":[]}"#), (503, "unavailable")] {
            let session = makeSession { request in
                request.url?.host == "api.nal.usda.gov"
                    ? json(request, status, body)
                    : json(request, 404, #"{"status":0}"#)
            }
            await expectOpenFoodFactsError(.productNotFound, session: session, key: Self.key)
            finish(session)
        }
    }

    @Test func refusedUSDAKeyIsReported() async {
        let session = makeSession { request in
            request.url?.host == "api.nal.usda.gov"
                ? json(request, 403, #"{"error":{"code":"API_KEY_INVALID"}}"#)
                : json(request, 404, #"{"status":0}"#)
        }
        defer { finish(session) }

        do {
            _ = try await lookup(session: session, key: Self.key)
            Issue.record("Expected the refused key")
        } catch let error as FoodDataCentralService.LookupError {
            #expect(error == .invalidAPIKey)
            #expect(error.localizedDescription.contains("USDA FoodData Central"))
        } catch {
            Issue.record("Expected FoodDataCentralService.LookupError but received \(error)")
        }
    }

    @Test func otherOpenFoodFactsFailuresDoNotFallBack() async {
        var hosts: [String] = []
        let session = makeSession { request in
            hosts.append(request.url?.host ?? "")
            return json(request, 429, #"{"status":0}"#)
        }
        defer { finish(session) }

        await expectOpenFoodFactsError(.rateLimited, session: session, key: Self.key)
        #expect(hosts == ["world.openfoodfacts.org"])
    }

    @Test func voiceAssistantScanUsesTheSavedLabelBeforeUSDA() async throws {
        var hosts: [String] = []
        let session = makeSession { request in
            hosts.append(request.url?.host ?? "")
            return json(request, 404, #"{"status":0}"#)
        }
        defer { finish(session) }
        let label = FoodEntry(
            name: "My label",
            calories: 150,
            protein: 5,
            carbs: 20,
            fat: 4,
            source: .nutritionLabel,
            servingSizeGrams: 40,
            productMetadata: BarcodeLabelMemory.labelMetadata(nil, barcode: "0016000275287")
        )

        let lookup = try await VoiceAssistantAnalyzer(session: session)
            .lookUpBarcode(Self.code, savedLabelEntries: [label])

        #expect(lookup.analysis.name == "My label")
        #expect(lookup.analysis.calories == 150)
        #expect(lookup.analysis.productMetadata?.barcode == "0016000275287")
        #expect(lookup.source == .nutritionLabel)
        #expect(lookup.image == nil)
        #expect(hosts == ["world.openfoodfacts.org"])
    }

    // MARK: - Helpers

    private static let openFoodFactsProduct = #"""
    {"status":1,"product":{"product_name":"Open Food Facts Oats","serving_quantity":40,
    "nutriments":{"energy-kcal_100g":380,"proteins_100g":13,"carbohydrates_100g":60,"fat_100g":7}}}
    """#

    private func lookup(
        session: URLSession,
        key: String?,
        labels: [FoodEntry] = []
    ) async throws -> BarcodeProductLookup.Outcome {
        try await BarcodeProductLookup.lookup(
            barcode: Self.code,
            savedLabelEntries: labels,
            usdaAPIKey: key,
            includesProductImage: false,
            session: session
        )
    }

    private func expectOpenFoodFactsError(
        _ expected: OpenFoodFactsService.LookupError,
        session: URLSession,
        key: String?
    ) async {
        do {
            _ = try await lookup(session: session, key: key)
            Issue.record("Expected \(expected)")
        } catch let error as OpenFoodFactsService.LookupError {
            #expect(error.localizedDescription == expected.localizedDescription)
        } catch {
            Issue.record("Expected OpenFoodFactsService.LookupError but received \(error)")
        }
    }
}

// MARK: - Stubbed transport

private func makeSession(handler: @escaping (URLRequest) throws -> (HTTPURLResponse, Data)) -> URLSession {
    StubFoodDataURLProtocol.handler = handler
    let configuration = URLSessionConfiguration.ephemeral
    configuration.protocolClasses = [StubFoodDataURLProtocol.self]
    return URLSession(configuration: configuration)
}

private func finish(_ session: URLSession) {
    session.invalidateAndCancel()
    StubFoodDataURLProtocol.handler = nil
}

private func json(_ request: URLRequest, _ statusCode: Int, _ body: String) -> (HTTPURLResponse, Data) {
    let response = HTTPURLResponse(
        url: request.url!,
        statusCode: statusCode,
        httpVersion: "HTTP/1.1",
        headerFields: ["Content-Type": "application/json"]
    )!
    return (response, Data(body.utf8))
}

/// URLSession hands a protocol the POST body as a stream.
private func bodyJSON(of request: URLRequest) -> [String: Any]? {
    var data = request.httpBody ?? Data()
    if data.isEmpty, let stream = request.httpBodyStream {
        stream.open()
        defer { stream.close() }
        var buffer = [UInt8](repeating: 0, count: 4096)
        while stream.hasBytesAvailable {
            let count = stream.read(&buffer, maxLength: buffer.count)
            guard count > 0 else { break }
            data.append(buffer, count: count)
        }
    }
    return (try? JSONSerialization.jsonObject(with: data)) as? [String: Any]
}

private final class StubFoodDataURLProtocol: URLProtocol {
    static var handler: ((URLRequest) throws -> (HTTPURLResponse, Data))?

    override class func canInit(with request: URLRequest) -> Bool {
        true
    }

    override class func canonicalRequest(for request: URLRequest) -> URLRequest {
        request
    }

    override func startLoading() {
        guard let handler = Self.handler else {
            client?.urlProtocol(self, didFailWithError: URLError(.unknown))
            return
        }

        do {
            let (response, data) = try handler(request)
            client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
            client?.urlProtocol(self, didLoad: data)
            client?.urlProtocolDidFinishLoading(self)
        } catch {
            client?.urlProtocol(self, didFailWithError: error)
        }
    }

    override func stopLoading() {}
}

/// A Branded Foods search recorded from api.nal.usda.gov on 2026-10-18 (query "cheerios"),
/// trimmed to the fields the app reads. The first food has a different GTIN; the second
/// lists entries from older label panels after its current values.
enum FoodDataCentralFixtures {
    static let cheeriosSearch = #"""
        {
          "totalHits": 2,
          "currentPage": 1,
          "totalPages": 1,
          "foods": [
            {
              "fdcId": 2777025,
              "description": "Cheerios Cereal",
              "dataType": "Branded",
              "gtinUpc": "00016000322622",
              "publishedDate": "2026-09-24",
              "brandOwner": "GENERAL MILLS SALES INC.",
              "brandName": "Cheerios",
              "ingredients": "Whole Grain Oats, Corn Starch, Sugar, Salt, Tripotassium Phosphate. Vitamin E (mixed tocopherols) Added to Preserve Freshness.Vitamins and Minerals: Calcium Carbonate, Iron and Zinc (mineral nutrients), Vitamin C (sodium ascorbate), A B Vitamin (niacinamide), Vitamin B6 (pyridoxine hydrochloride), Vitamin A (palmitate), Vitamin B1 (thiamin mononitrate), A B Vitamin (folic acid), Vitamin B12, Vitamin D3.",
              "marketCountry": "US",
              "foodCategory": "Processed Cereal Products",
              "packageWeight": "1 ONZ",
              "servingSizeUnit": "GRM",
              "servingSize": 28.0,
              "householdServingFullText": "1 bowl",
              "foodNutrients": [
                {"nutrientId": 1003, "nutrientName": "Protein", "nutrientNumber": "203", "unitName": "G", "value": 14.3},
                {"nutrientId": 1004, "nutrientName": "Total lipid (fat)", "nutrientNumber": "204", "unitName": "G", "value": 7.14},
                {"nutrientId": 1005, "nutrientName": "Carbohydrate, by difference", "nutrientNumber": "205", "unitName": "G", "value": 75.0},
                {"nutrientId": 1008, "nutrientName": "Energy", "nutrientNumber": "208", "unitName": "KCAL", "value": 357},
                {"nutrientId": 2000, "nutrientName": "Total Sugars", "nutrientNumber": "269", "unitName": "G", "value": 3.57},
                {"nutrientId": 1079, "nutrientName": "Fiber, total dietary", "nutrientNumber": "291", "unitName": "G", "value": 10.7},
                {"nutrientId": 1082, "nutrientName": "Fiber, soluble", "nutrientNumber": "295", "unitName": "G", "value": 4.0},
                {"nutrientId": 1087, "nutrientName": "Calcium, Ca", "nutrientNumber": "301", "unitName": "MG", "value": 321},
                {"nutrientId": 1089, "nutrientName": "Iron, Fe", "nutrientNumber": "303", "unitName": "MG", "value": 32.1},
                {"nutrientId": 1092, "nutrientName": "Potassium, K", "nutrientNumber": "306", "unitName": "MG", "value": 643},
                {"nutrientId": 1093, "nutrientName": "Sodium, Na", "nutrientNumber": "307", "unitName": "MG", "value": 500},
                {"nutrientId": 1095, "nutrientName": "Zinc, Zn", "nutrientNumber": "309", "unitName": "MG", "value": 3.93},
                {"nutrientId": 1106, "nutrientName": "Vitamin A, RAE", "nutrientNumber": "320", "unitName": "UG", "value": 193},
                {"nutrientId": 1114, "nutrientName": "Vitamin D (D2 + D3)", "nutrientNumber": "328", "unitName": "UG", "value": 10.0},
                {"nutrientId": 1162, "nutrientName": "Vitamin C, total ascorbic acid", "nutrientNumber": "401", "unitName": "MG", "value": 19.3},
                {"nutrientId": 1165, "nutrientName": "Thiamin", "nutrientNumber": "404", "unitName": "MG", "value": 0.0},
                {"nutrientId": 1167, "nutrientName": "Niacin", "nutrientNumber": "406", "unitName": "MG", "value": 3.43},
                {"nutrientId": 1175, "nutrientName": "Vitamin B-6", "nutrientNumber": "415", "unitName": "MG", "value": 0.607},
                {"nutrientId": 1177, "nutrientName": "Folate, total", "nutrientNumber": "417", "unitName": "UG", "value": 143},
                {"nutrientId": 1178, "nutrientName": "Vitamin B-12", "nutrientNumber": "418", "unitName": "UG", "value": 0.86},
                {"nutrientId": 1186, "nutrientName": "Folic acid", "nutrientNumber": "431", "unitName": "UG", "value": 89.0},
                {"nutrientId": 1235, "nutrientName": "Sugars, added", "nutrientNumber": "539", "unitName": "G", "value": 3.6},
                {"nutrientId": 1253, "nutrientName": "Cholesterol", "nutrientNumber": "601", "unitName": "MG", "value": 0.0},
                {"nutrientId": 1257, "nutrientName": "Fatty acids, total trans", "nutrientNumber": "605", "unitName": "G", "value": 0.0},
                {"nutrientId": 1258, "nutrientName": "Fatty acids, total saturated", "nutrientNumber": "606", "unitName": "G", "value": 0.0}
              ]
            },
            {
              "fdcId": 2517161,
              "description": "Cheerios Cereal",
              "dataType": "Branded",
              "gtinUpc": "00016000275287",
              "publishedDate": "2023-04-27",
              "brandOwner": "General Mills",
              "brandName": "Cheerios",
              "ingredients": "WHOLE GRAIN OATS, CORN STARCH, SUGAR, SALT, TRIPOTASSIUM PHOSPHATE. VITAMIN E (MIXED TOCOPHEROLS) ADDED TO PRESERVE FRESHNESS.VITAMINS AND MINERALS: CALCIUM CARBONATE, IRON AND ZINC (MINERAL NUTRIENTS), VITAMIN C (SODIUM ASCORBATE), A B VITAMIN (NIACINAMIDE), VITAMIN B6 (PYRIDOXINE HYDROCHLORIDE), VITAMIN A (PALMITATE), VITAMIN B1 (THIAMIN MONONITRATE), A B VITAMIN (FOLIC ACID), VITAMIN B12, VITAMIN D3.",
              "marketCountry": "United States",
              "foodCategory": "Processed Cereal Products",
              "packageWeight": "18 ONZ",
              "servingSizeUnit": "GRM",
              "servingSize": 20.0,
              "householdServingFullText": "3/4 cup (20g) (age 1-3 years)",
              "foodNutrients": [
                {"nutrientId": 1003, "nutrientName": "Protein", "nutrientNumber": "203", "unitName": "G", "value": 12.8},
                {"nutrientId": 1004, "nutrientName": "Total lipid (fat)", "nutrientNumber": "204", "unitName": "G", "value": 6.41},
                {"nutrientId": 1005, "nutrientName": "Carbohydrate, by difference", "nutrientNumber": "205", "unitName": "G", "value": 74.4},
                {"nutrientId": 1008, "nutrientName": "Energy", "nutrientNumber": "208", "unitName": "KCAL", "value": 359},
                {"nutrientId": 2000, "nutrientName": "Total Sugars", "nutrientNumber": "269", "unitName": "G", "value": 5.13},
                {"nutrientId": 1079, "nutrientName": "Fiber, total dietary", "nutrientNumber": "291", "unitName": "G", "value": 10.3},
                {"nutrientId": 1082, "nutrientName": "Fiber, soluble", "nutrientNumber": "295", "unitName": "G", "value": 3.0},
                {"nutrientId": 1087, "nutrientName": "Calcium, Ca", "nutrientNumber": "301", "unitName": "MG", "value": 333},
                {"nutrientId": 1089, "nutrientName": "Iron, Fe", "nutrientNumber": "303", "unitName": "MG", "value": 32.3},
                {"nutrientId": 1090, "nutrientName": "Magnesium, Mg", "nutrientNumber": "304", "unitName": "MG", "value": 700},
                {"nutrientId": 1091, "nutrientName": "Phosphorus, P", "nutrientNumber": "305", "unitName": "MG", "value": 750},
                {"nutrientId": 1092, "nutrientName": "Potassium, K", "nutrientNumber": "306", "unitName": "MG", "value": 641},
                {"nutrientId": 1093, "nutrientName": "Sodium, Na", "nutrientNumber": "307", "unitName": "MG", "value": 487},
                {"nutrientId": 1095, "nutrientName": "Zinc, Zn", "nutrientNumber": "309", "unitName": "MG", "value": 26.2},
                {"nutrientId": 1104, "nutrientName": "Vitamin A, IU", "nutrientNumber": "318", "unitName": "IU", "value": 3750.0},
                {"nutrientId": 1110, "nutrientName": "Vitamin D (D2 + D3), International Units", "nutrientNumber": "324", "unitName": "IU", "value": 205},
                {"nutrientId": 1162, "nutrientName": "Vitamin C, total ascorbic acid", "nutrientNumber": "401", "unitName": "MG", "value": 90.0},
                {"nutrientId": 1165, "nutrientName": "Thiamin", "nutrientNumber": "404", "unitName": "MG", "value": 2.0},
                {"nutrientId": 1175, "nutrientName": "Vitamin B-6", "nutrientNumber": "415", "unitName": "MG", "value": 3.0},
                {"nutrientId": 1177, "nutrientName": "Folate, total", "nutrientNumber": "417", "unitName": "UG", "value": 500},
                {"nutrientId": 1178, "nutrientName": "Vitamin B-12", "nutrientNumber": "418", "unitName": "UG", "value": 7.5},
                {"nutrientId": 1186, "nutrientName": "Folic acid", "nutrientNumber": "431", "unitName": "UG", "value": 115},
                {"nutrientId": 1235, "nutrientName": "Sugars, added", "nutrientNumber": "539", "unitName": "G", "value": 5.1},
                {"nutrientId": 1253, "nutrientName": "Cholesterol", "nutrientNumber": "601", "unitName": "MG", "value": 0.0},
                {"nutrientId": 1257, "nutrientName": "Fatty acids, total trans", "nutrientNumber": "605", "unitName": "G", "value": 0.0},
                {"nutrientId": 1258, "nutrientName": "Fatty acids, total saturated", "nutrientNumber": "606", "unitName": "G", "value": 1.28},
                {"nutrientId": 1292, "nutrientName": "Fatty acids, total monounsaturated", "nutrientNumber": "645", "unitName": "G", "value": 2.56},
                {"nutrientId": 1293, "nutrientName": "Fatty acids, total polyunsaturated", "nutrientNumber": "646", "unitName": "G", "value": 2.56},
                {"nutrientId": 1008, "nutrientName": "Energy", "nutrientNumber": "208", "unitName": "KCAL", "value": 359},
                {"nutrientId": 1090, "nutrientName": "Magnesium, Mg", "nutrientNumber": "304", "unitName": "MG", "value": 154},
                {"nutrientId": 1008, "nutrientName": "Energy", "nutrientNumber": "208", "unitName": "KCAL", "value": 117}
              ]
            }
          ]
        }
    """#
}
