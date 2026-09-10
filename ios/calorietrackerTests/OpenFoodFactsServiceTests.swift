import Foundation
import Testing
@testable import calorietracker

@Suite("Open Food Facts barcode lookup", .serialized)
@MainActor
struct OpenFoodFactsServiceTests {
    @Test func successfulLookupPreservesLeadingZerosInPath() async throws {
        let session = makeSession { request in
            #expect(request.url?.absoluteString.contains("/0012345678901.json?") == true)
            return response(
                for: request,
                statusCode: 200,
                json: #"""
                {
                    "status": 1,
                    "product": {
                        "product_name": "Test Product",
                        "quantity": "250 g",
                        "ingredients_text": "Oats, milk",
                        "allergens_tags": ["en:milk"],
                        "traces_tags": ["en:nuts"],
                        "nutriscore_grade": "a",
                        "nova_group": 2,
                        "ecoscore_grade": "b",
                        "labels_tags": ["en:organic"],
                        "categories_tags": ["en:cereals"],
                        "image_front_url": "https://images.openfoodfacts.org/test.jpg",
                        "serving_quantity": 50,
                        "nutriments": {
                            "energy-kcal_100g": 200,
                            "proteins_100g": 10,
                            "carbohydrates_100g": 30,
                            "fat_100g": 5
                        }
                    }
                }
                """#
            )
        }
        defer { finish(session) }

        let result = try await OpenFoodFactsService.lookup(barcode: "  0012345678901\n", session: session)

        #expect(result.name == "Test Product")
        #expect(result.calories == 100)
        #expect(result.protein == 5)
        #expect(result.carbs == 15)
        #expect(result.fat == 2.5)
        #expect(result.servingSizeGrams == 50)
        #expect(result.servingUnitOptions.map(\.unit) == ["serving", "package"])
        #expect(result.productMetadata?.barcode == "0012345678901")
        #expect(result.productMetadata?.packageQuantity == "250 g")
        #expect(result.productMetadata?.ingredientsText == "Oats, milk")
        #expect(result.productMetadata?.allergens == ["Milk"])
        #expect(result.productMetadata?.traces == ["Nuts"])
        #expect(result.productMetadata?.nutriScore == "A")
        #expect(result.productMetadata?.novaGroup == 2)
        #expect(result.productMetadata?.ecoScore == "B")
        #expect(result.productMetadata?.labels == ["Organic"])
        #expect(result.productMetadata?.categories == ["Cereals"])
    }

    @Test func lookupWithImageDownloadsTrustedProductPhoto() async throws {
        let expectedImage = Data([0xFF, 0xD8, 0xFF, 0xD9])
        let session = makeSession { request in
            if request.url?.host == "images.openfoodfacts.org" {
                let response = HTTPURLResponse(
                    url: request.url!,
                    statusCode: 200,
                    httpVersion: "HTTP/1.1",
                    headerFields: ["Content-Type": "image/jpeg"]
                )!
                return (response, expectedImage)
            }
            return response(
                for: request,
                statusCode: 200,
                json: #"{"status":1,"product":{"product_name":"Test","serving_quantity":100,"image_front_url":"https://images.openfoodfacts.org/test.jpg","nutriments":{"energy-kcal_100g":100,"proteins_100g":1,"carbohydrates_100g":2,"fat_100g":3}}}"#
            )
        }
        defer { finish(session) }

        let result = try await OpenFoodFactsService.lookupWithImage(
            barcode: "0012345678901",
            session: session
        )

        #expect(result.productImageData == expectedImage)
    }

    @Test func nonASCIIOrOversizedBarcodesAreRejectedBeforeTransport() async {
        let session = makeSession { _ in
            Issue.record("Invalid barcode should be rejected before making a request")
            throw URLError(.badURL)
        }
        defer { finish(session) }

        for barcode in [
            "123/456",
            "ABC123",
            "١٢٣٤٥٦٧٨",
            String(repeating: "1", count: 25),
        ] {
            await expectError(.invalidBarcode, barcode: barcode, session: session)
        }
    }

    @Test func http404MapsToProductNotFound() async {
        let session = makeSession { request in
            response(
                for: request,
                statusCode: 404,
                json: #"{"status":0,"status_verbose":"product not found"}"#
            )
        }
        defer { finish(session) }

        await expectError(.productNotFound, session: session)
    }

    @Test func statusZeroOnSuccessfulResponseMapsToProductNotFound() async {
        let session = makeSession { request in
            response(
                for: request,
                statusCode: 200,
                json: #"{"status":0,"status_verbose":"product not found"}"#
            )
        }
        defer { finish(session) }

        await expectError(.productNotFound, session: session)
    }

    @Test func http429MapsToRateLimited() async {
        let session = makeSession { request in
            response(for: request, statusCode: 429, json: #"{"status":0}"#)
        }
        defer { finish(session) }

        await expectError(.rateLimited, session: session)
    }

    @Test func serverErrorMapsToServiceUnavailable() async {
        let session = makeSession { request in
            response(for: request, statusCode: 503, json: #"{"status":0}"#)
        }
        defer { finish(session) }

        await expectError(.serviceUnavailable, session: session)
    }

    @Test func productWithoutNutrientsKeepsMissingNutritionError() async {
        let session = makeSession { request in
            response(
                for: request,
                statusCode: 200,
                json: #"{"status":1,"product":{"product_name":"Incomplete Product"}}"#
            )
        }
        defer { finish(session) }

        await expectError(.missingNutrition, session: session)
    }

    @Test func malformedSuccessfulResponseMapsToInvalidResponse() async {
        let session = makeSession { request in
            response(for: request, statusCode: 200, json: "not-json")
        }
        defer { finish(session) }

        await expectError(.invalidResponse, session: session)
    }

    @Test func offlineFailureHasSpecificGuidance() async {
        let session = makeSession { _ in
            throw URLError(.notConnectedToInternet)
        }
        defer { finish(session) }

        await expectError(.offline, session: session)
    }

    @Test func timeoutFailureHasSpecificGuidance() async {
        let session = makeSession { _ in throw URLError(.timedOut) }
        defer { finish(session) }
        await expectError(.timeout, session: session)
    }

    @Test func connectionRefusedDoesNotClaimOffline() async {
        let session = makeSession { _ in throw URLError(.cannotConnectToHost) }
        defer { finish(session) }
        await expectError(.networkError, session: session)
    }

    @Test func cancellationRemainsCancellation() async {
        let session = makeSession { _ in
            // URLSession reports a cancelled request as NSURLErrorCancelled.
            throw URLError(.cancelled)
        }
        defer { finish(session) }

        do {
            _ = try await OpenFoodFactsService.lookup(barcode: "0123456789012", session: session)
            Issue.record("Expected cancellation")
        } catch is CancellationError {
            // Expected: cancellation must not become a user-facing lookup error.
        } catch {
            Issue.record("Expected CancellationError but received \(error)")
        }
    }

    private enum ExpectedError {
        case invalidBarcode
        case productNotFound
        case missingNutrition
        case rateLimited
        case serviceUnavailable
        case invalidResponse
        case offline
        case timeout
        case networkError
    }

    private func expectError(
        _ expected: ExpectedError,
        barcode: String = "0123456789012",
        session: URLSession
    ) async {
        do {
            _ = try await OpenFoodFactsService.lookup(barcode: barcode, session: session)
            Issue.record("Expected barcode lookup to fail with \(expected)")
        } catch let error as OpenFoodFactsService.LookupError {
            #expect(matches(error, expected))
        } catch {
            Issue.record("Expected LookupError but received \(error)")
        }
    }

    private func matches(
        _ error: OpenFoodFactsService.LookupError,
        _ expected: ExpectedError
    ) -> Bool {
        switch (error, expected) {
        case (.invalidBarcode, .invalidBarcode),
             (.productNotFound, .productNotFound),
             (.missingNutrition, .missingNutrition),
             (.rateLimited, .rateLimited),
             (.serviceUnavailable, .serviceUnavailable),
             (.invalidResponse, .invalidResponse),
             (.offline, .offline),
             (.timeout, .timeout),
             (.networkError, .networkError):
            true
        default:
            false
        }
    }

    private func makeSession(
        handler: @escaping (URLRequest) throws -> (HTTPURLResponse, Data)
    ) -> URLSession {
        MockOpenFoodFactsURLProtocol.handler = handler
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [MockOpenFoodFactsURLProtocol.self]
        return URLSession(configuration: configuration)
    }

    private func finish(_ session: URLSession) {
        session.invalidateAndCancel()
        MockOpenFoodFactsURLProtocol.handler = nil
    }

    private func response(
        for request: URLRequest,
        statusCode: Int,
        json: String
    ) -> (HTTPURLResponse, Data) {
        let response = HTTPURLResponse(
            url: request.url!,
            statusCode: statusCode,
            httpVersion: "HTTP/1.1",
            headerFields: ["Content-Type": "application/json"]
        )!
        return (response, Data(json.utf8))
    }
}

private final class MockOpenFoodFactsURLProtocol: URLProtocol {
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
