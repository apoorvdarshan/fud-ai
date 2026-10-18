import Foundation
import Testing
@testable import calorietracker

struct ProductInfoSettingsTests {
    @Test
    func emptyStorageHidesNothing() {
        #expect(ProductInfoSettings.hiddenFields(from: "").isEmpty)
    }

    @Test
    func hiddenFieldsRoundTripInDisplayOrderAndDropUnknownValues() {
        let stored = ProductInfoSettings.storageValue(for: [.ingredientLabel, .barcode, .traces])
        #expect(stored == "barcode,traces,ingredientLabel")
        #expect(ProductInfoSettings.hiddenFields(from: stored) == [.barcode, .traces, .ingredientLabel])
        #expect(ProductInfoSettings.hiddenFields(from: "labels, retiredField,,nutriScore") == [.labels, .nutriScore])
    }

    @Test
    func storageKeysMatchAndroid() {
        #expect(ProductInfoField.allCases.map(\.rawValue) == [
            "barcode", "source", "package", "nutriScore", "novaGroup", "ecoScore",
            "allergens", "traces", "labels", "categories", "ingredientLabel"
        ])
        #expect(ProductInfoSettings.isShownKey == "productInfoShown")
        #expect(ProductInfoSettings.hiddenFieldsKey == "productInfoHiddenFields")
    }

    @Test
    func defaultsShowEveryFieldWithData() {
        let fields = ProductInfoSettings.visibleFields(for: fullMetadata, isShown: true, hiddenFields: [])
        #expect(fields == ProductInfoField.allCases)
    }

    @Test
    func fieldsWithoutDataAreSkipped() {
        let metadata = FoodProductMetadata(
            barcode: "123", packageQuantity: nil, ingredientsText: "Oats, salt",
            allergens: [], traces: [], nutriScore: nil, novaGroup: nil, ecoScore: nil,
            labels: [], categories: [], imageURL: nil
        )
        let fields = ProductInfoSettings.visibleFields(for: metadata, isShown: true, hiddenFields: [])
        #expect(fields == [.barcode, .ingredientLabel])
    }

    @Test
    func hiddenFieldsAreDroppedAndOrderIsKept() {
        let fields = ProductInfoSettings.visibleFields(
            for: fullMetadata,
            isShown: true,
            hiddenFields: [.barcode, .categories, .ecoScore]
        )
        #expect(fields == [.source, .package, .nutriScore, .novaGroup, .allergens, .traces, .labels, .ingredientLabel])
    }

    @Test
    func sourceShowsOnlyForUSDAProducts() {
        var metadata = fullMetadata
        metadata.dataSource = nil
        #expect(!ProductInfoSettings.visibleFields(for: metadata, isShown: true, hiddenFields: []).contains(.source))
        metadata.dataSource = "someNewSource"
        #expect(!ProductInfoSettings.visibleFields(for: metadata, isShown: true, hiddenFields: []).contains(.source))
        metadata.dataSource = FoodDataCentralService.dataSourceID
        #expect(ProductInfoSettings.visibleFields(for: metadata, isShown: true, hiddenFields: []).contains(.source))
        #expect(!ProductInfoSettings.visibleFields(for: metadata, isShown: true, hiddenFields: [.source]).contains(.source))
    }

    @Test
    func turningTheBlockOffOrHidingEveryPresentFieldLeavesNothingToShow() {
        #expect(ProductInfoSettings.visibleFields(for: fullMetadata, isShown: false, hiddenFields: []).isEmpty)

        let barcodeOnly = FoodProductMetadata(
            barcode: "123", packageQuantity: nil, ingredientsText: nil,
            allergens: [], traces: [], nutriScore: nil, novaGroup: nil, ecoScore: nil,
            labels: [], categories: [], imageURL: nil
        )
        #expect(ProductInfoSettings.visibleFields(for: barcodeOnly, isShown: true, hiddenFields: [.barcode]).isEmpty)
    }

    private var fullMetadata: FoodProductMetadata {
        FoodProductMetadata(
            barcode: "4006381333931",
            packageQuantity: "400 g",
            ingredientsText: "Sugar, _hazelnuts_ (13%), skimmed _milk_ powder",
            allergens: ["Milk", "Nuts"],
            traces: ["Gluten"],
            nutriScore: "E",
            novaGroup: 4,
            ecoScore: "D",
            labels: ["Vegetarian"],
            categories: ["Spreads"],
            imageURL: nil,
            dataSource: FoodDataCentralService.dataSourceID
        )
    }
}
