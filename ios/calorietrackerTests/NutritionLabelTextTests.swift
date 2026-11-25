import CoreGraphics
import Foundation
import Testing
import UIKit
@testable import calorietracker

struct NutritionLabelTextTests {
    /// A fragment placed with a top-left origin, converted to Vision's
    /// lower-left normalized coordinates.
    private func cell(
        _ text: String,
        x: CGFloat,
        top: CGFloat,
        width: CGFloat = 0.2,
        height: CGFloat = 0.03,
        slope: CGFloat? = nil
    ) -> NutritionLabelText.Fragment {
        NutritionLabelText.Fragment(
            text: text,
            box: CGRect(x: x, y: 1 - top - height, width: width, height: height),
            slope: slope
        )
    }

    private var usLabel: [NutritionLabelText.Fragment] {
        [
            cell("Nutrition Facts", x: 0.1, top: 0.05, width: 0.5, height: 0.05),
            cell("Serving size", x: 0.1, top: 0.12),
            cell("2/3 cup (55g)", x: 0.6, top: 0.121),
            cell("Calories", x: 0.1, top: 0.18, height: 0.04),
            cell("230", x: 0.75, top: 0.182, width: 0.1, height: 0.04),
            cell("  Total   Fat 8g ", x: 0.1, top: 0.25),
            cell("10%", x: 0.8, top: 0.252, width: 0.1),
            cell("Sodium", x: 0.1, top: 0.30),
            cell("160", x: 0.45, top: 0.30, width: 0.08),
            // Vision sometimes reads the unit as its own fragment.
            cell("mg", x: 0.55, top: 0.301, width: 0.05),
            cell("7%", x: 0.8, top: 0.30, width: 0.1),
            cell(" ", x: 0.5, top: 0.33),
            cell("Protein 3g", x: 0.1, top: 0.35),
        ]
    }

    @Test
    func assemblesRowsTopToBottomWithColumnsInReadingOrder() {
        // Vision's result order is not reading order.
        let lines = NutritionLabelText.lines(from: usLabel.reversed())
        #expect(lines == [
            "Nutrition Facts",
            "Serving size | 2/3 cup (55g)",
            "Calories | 230",
            "Total Fat 8g | 10%",
            "Sodium | 160 mg | 7%",
            "Protein 3g",
        ])
    }

    @Test
    func keepsPerServingAndPer100gColumnsApart() {
        let lines = NutritionLabelText.lines(from: [
            cell("Typical values", x: 0.05, top: 0.12),
            cell("per 100 g", x: 0.5, top: 0.12),
            cell("per serving (30 g)", x: 0.75, top: 0.12),
            cell("Energy", x: 0.05, top: 0.18),
            cell("1046 kJ / 250 kcal", x: 0.5, top: 0.18),
            cell("314 kJ / 75 kcal", x: 0.75, top: 0.18),
            cell("Fat", x: 0.05, top: 0.24),
            cell("9,5 g", x: 0.5, top: 0.24),
            cell("2,9 g", x: 0.75, top: 0.24),
        ])
        #expect(lines == [
            "Typical values | per 100 g | per serving (30 g)",
            "Energy | 1046 kJ / 250 kcal | 314 kJ / 75 kcal",
            "Fat | 9,5 g | 2,9 g",
        ])
    }

    @Test
    func followsASlightTiltAcrossColumnsWithoutMergingNeighbouringRows() {
        // Each column sits a little higher than the one to its left, and no
        // fragment reports a slope.
        let lines = NutritionLabelText.lines(from: [
            cell("Energy", x: 0.05, top: 0.40),
            cell("1046 kJ", x: 0.4, top: 0.388),
            cell("314 kJ", x: 0.7, top: 0.376),
            cell("Fat", x: 0.05, top: 0.445),
            cell("9,5 g", x: 0.4, top: 0.433),
            cell("2,9 g", x: 0.7, top: 0.421),
        ])
        #expect(lines == ["Energy | 1046 kJ | 314 kJ", "Fat | 9,5 g | 2,9 g"])
    }

    /// A two-column label whose right column sits `slope` higher per unit of
    /// x, so with 0.06 it is almost a whole row higher than its nutrient.
    /// Like Vision, wide lines report a slope a little under the tilt and
    /// short ones come back exactly level.
    private func tiltedLabel(
        _ rows: [(name: String, value: String?)],
        slope: CGFloat,
        reportedSlope: CGFloat?
    ) -> [NutritionLabelText.Fragment] {
        func tilted(_ text: String, x: CGFloat, rowTop: CGFloat, width: CGFloat) -> NutritionLabelText.Fragment {
            cell(
                text,
                x: x,
                top: rowTop - slope * (x + width / 2),
                width: width,
                slope: reportedSlope.map { width > 0.1 ? $0 : 0 }
            )
        }
        return rows.enumerated().flatMap { index, row in
            let rowTop = 0.1 + 0.045 * CGFloat(index)
            let name = tilted(row.name, x: 0.05, rowTop: rowTop, width: 0.3)
            guard let value = row.value else { return [name] }
            return [name, tilted(value, x: 0.8, rowTop: rowTop, width: 0.08)]
        }
    }

    @Test
    func levelsAPhotoTiltedAcrossTheWholeLabel() {
        // Without leveling "Calories" would pair with "10%".
        let rows: [(name: String, value: String?)] = [
            ("Calories", "230"), ("Total Fat 8g", "10%"), ("Sodium 160mg", "7%"), ("Total Carbohydrate 37g", "13%"),
        ]
        let fragments = tiltedLabel(rows, slope: 0.06, reportedSlope: 0.045)
        let expected = ["Calories | 230", "Total Fat 8g | 10%", "Sodium 160mg | 7%", "Total Carbohydrate 37g | 13%"]
        #expect(NutritionLabelText.lines(from: fragments) == expected)

        // Flipped upside down, the same rows tilt the other way and read in reverse.
        let mirrored = fragments.map { fragment -> NutritionLabelText.Fragment in
            var fragment = fragment
            fragment.box.origin.y = 1 - fragment.box.maxY
            fragment.slope = fragment.slope.map { -$0 }
            return fragment
        }
        #expect(NutritionLabelText.lines(from: mirrored.reversed()) == expected.reversed())
    }

    @Test
    func followsTheTextLineSlopeWhenTheRowsAloneAreAmbiguous() {
        // With gaps in the %DV column, a level label and one tilted by a row
        // line up the same number of pairs. The text lines' slope decides.
        let rows: [(name: String, value: String?)] = [
            ("Calories 230", nil), ("Total Fat 8g", "10%"), ("Saturated Fat 1g", "5%"),
            ("Trans Fat 0g", nil), ("Sodium 160mg", "7%"), ("Protein 3g", nil),
        ]
        let expected = [
            "Calories 230", "Total Fat 8g | 10%", "Saturated Fat 1g | 5%", "Trans Fat 0g", "Sodium 160mg | 7%", "Protein 3g",
        ]
        #expect(NutritionLabelText.lines(from: tiltedLabel(rows, slope: 0.07, reportedSlope: 0.05)) == expected)
        #expect(NutritionLabelText.lines(from: tiltedLabel(rows, slope: -0.07, reportedSlope: -0.05)) == expected)
        #expect(NutritionLabelText.lines(from: tiltedLabel(rows, slope: 0, reportedSlope: 0.003)) == expected)
    }

    @Test
    func keepsANoisyLevelLabelLevelWhenARowShiftLinesUpBetter() {
        // Box centers sit up to 15% of the text height off their row, and the
        // pattern lines up the values one row down better than on their own
        // rows. The lines report no tilt, so the label stays level.
        let offset: CGFloat = 0.0045
        let lines = NutritionLabelText.lines(from: [
            cell("Calories 230", x: 0.05, top: 0.10, width: 0.3, slope: 0),
            cell("Total Fat 8g", x: 0.05, top: 0.145 - offset, width: 0.3, slope: 0),
            cell("10%", x: 0.8, top: 0.145, width: 0.08, slope: 0),
            cell("Saturated Fat 1g", x: 0.05, top: 0.19 + offset, width: 0.3, slope: 0),
            cell("5%", x: 0.8, top: 0.19, width: 0.08, slope: 0),
            cell("Trans Fat 0g", x: 0.05, top: 0.235, width: 0.3, slope: 0),
            cell("Sodium 160mg", x: 0.05, top: 0.28 - offset, width: 0.3, slope: 0),
            cell("7%", x: 0.8, top: 0.28, width: 0.08, slope: 0),
            cell("Protein 3g", x: 0.05, top: 0.325, width: 0.3, slope: 0),
        ])
        #expect(lines == [
            "Calories 230", "Total Fat 8g | 10%", "Saturated Fat 1g | 5%", "Trans Fat 0g", "Sodium 160mg | 7%", "Protein 3g",
        ])
    }

    @Test
    func keepsEveryValueOnItsRowAcrossNoisyNearlyLevelPhotos() {
        // Seeded, so the same labels every run: tilt up to 0.01, every box
        // center up to 15% of the text height off its row, line slopes as
        // Vision reports them, and a value column with and without gaps.
        var generator = SeededGenerator(seed: 486)
        let layouts: [[Bool]] = [
            [true, true, true, true, true],
            [false, true, true, false, true, true, true, true, false, true, false],
        ]
        var misplaced: [[String]] = []
        for layout in layouts {
            for _ in 0..<100 {
                let tilt = CGFloat.random(in: -0.01...0.01, using: &generator)
                var fragments: [NutritionLabelText.Fragment] = []
                for (row, hasValue) in layout.enumerated() {
                    let rowCenter = 0.8 - 0.045 * CGFloat(row)
                    func fragment(_ text: String, x: CGFloat, width: CGFloat) -> NutritionLabelText.Fragment {
                        let height = 0.03 * CGFloat.random(in: 0.9...1.1, using: &generator)
                        let midY = rowCenter + tilt * (x + width / 2) + 0.03 * CGFloat.random(in: -0.15...0.15, using: &generator)
                        let slope = width > 0.1 ? tilt + CGFloat.random(in: -0.005...0.005, using: &generator) : 0
                        return NutritionLabelText.Fragment(
                            text: text,
                            box: CGRect(x: x, y: midY - height / 2, width: width, height: height),
                            slope: slope
                        )
                    }
                    fragments.append(fragment("name\(row)", x: 0.05, width: CGFloat.random(in: 0.2...0.4, using: &generator)))
                    if hasValue {
                        fragments.append(fragment("value\(row)", x: 0.75, width: CGFloat.random(in: 0.07...0.12, using: &generator)))
                    }
                }
                fragments.shuffle(using: &generator)
                let lines = NutritionLabelText.lines(from: fragments)
                let rowsMatch = lines.allSatisfy { line in
                    Set(line.components(separatedBy: NutritionLabelText.columnSeparator).map { $0.drop { $0.isLetter } }).count == 1
                }
                if !rowsMatch || lines.count != layout.count { misplaced.append(lines) }
            }
        }
        #expect(misplaced.isEmpty, "\(misplaced)")
    }

    @Test
    func neverPutsTwoFragmentsOfOneColumnOnTheSameRow() {
        // A steep photo's tall boxes bring the next name within half a box
        // height of this row.
        let lines = NutritionLabelText.lines(from: [
            cell("Dietary Fiber 4g", x: 0.05, top: 0.30, width: 0.35, height: 0.07),
            cell("4 g", x: 0.7, top: 0.30, width: 0.1, height: 0.07),
            cell("Energy 230 kcal", x: 0.05, top: 0.334, width: 0.3, height: 0.07),
        ])
        #expect(lines == ["Dietary Fiber 4g | 4 g", "Energy 230 kcal"])
    }

    @Test
    func readsTheLineSlopeFromVisionsCorners() throws {
        let slope = try #require(NutritionLabelText.lineSlope(
            topLeft: CGPoint(x: 0.1, y: 0.52),
            topRight: CGPoint(x: 0.5, y: 0.56),
            bottomLeft: CGPoint(x: 0.1, y: 0.5),
            bottomRight: CGPoint(x: 0.5, y: 0.52)
        ))
        // The top edge rises 0.1 per unit of x and the bottom edge 0.05.
        #expect(abs(slope - 0.075) < 1e-9)
        // Vertical or mirrored text has no left-to-right edge.
        #expect(NutritionLabelText.lineSlope(
            topLeft: CGPoint(x: 0.3, y: 0.6),
            topRight: CGPoint(x: 0.3, y: 0.2),
            bottomLeft: CGPoint(x: 0.25, y: 0.6),
            bottomRight: CGPoint(x: 0.25, y: 0.2)
        ) == nil)
    }

    @Test
    func readsArabicRowsRightToLeftButBilingualRowsLeftToRight() {
        let arabic = NutritionLabelText.lines(from: [
            cell("250 kcal", x: 0.1, top: 0.2),
            cell("الطاقة", x: 0.7, top: 0.2),
        ])
        #expect(arabic == ["الطاقة | 250 kcal"])

        let bilingual = NutritionLabelText.lines(from: [
            cell("الطاقة", x: 0.75, top: 0.2),
            cell("Energy", x: 0.05, top: 0.2),
            cell("250 kcal", x: 0.4, top: 0.2),
        ])
        #expect(bilingual == ["Energy | 250 kcal | الطاقة"])
    }

    @Test
    func joinsSplitUnitsToTheirNumbersInArabicRows() {
        // Vision can read a Latin unit apart from its number. Printed left to
        // right, each unit sits right of its own number, so reversing the
        // row for Arabic must not move a unit onto the next column's value.
        let lines = NutritionLabelText.lines(from: [
            cell("100", x: 0.05, top: 0.2, width: 0.08),
            cell("kcal", x: 0.14, top: 0.2, width: 0.08),
            cell("250", x: 0.35, top: 0.2, width: 0.08),
            cell("kcal", x: 0.44, top: 0.2, width: 0.08),
            cell("الطاقة", x: 0.7, top: 0.2),
            cell("3", x: 0.05, top: 0.25, width: 0.05),
            cell("g", x: 0.11, top: 0.25, width: 0.03),
            cell("7.5", x: 0.35, top: 0.25, width: 0.08),
            cell("g", x: 0.44, top: 0.25, width: 0.03),
            cell("البروتين", x: 0.7, top: 0.25),
        ].reversed())
        #expect(lines == [
            "الطاقة | 250 kcal | 100 kcal",
            "البروتين | 7.5 g | 3 g",
        ])

        // Every row of a photo reads in one direction, so a bilingual label
        // keeps its value columns in the same order on each row.
        let bilingual = NutritionLabelText.lines(from: [
            cell("Energy", x: 0.02, top: 0.2, width: 0.15),
            cell("250 kcal", x: 0.3, top: 0.2, width: 0.15),
            cell("100 kcal", x: 0.5, top: 0.2, width: 0.15),
            cell("الطاقة", x: 0.75, top: 0.2, width: 0.15),
            cell("Fat", x: 0.02, top: 0.25, width: 0.15),
            cell("9 g", x: 0.3, top: 0.25, width: 0.15),
            cell("3.6 g", x: 0.5, top: 0.25, width: 0.15),
            cell("الدهون", x: 0.75, top: 0.25, width: 0.15),
        ])
        #expect(bilingual == [
            "الطاقة | 100 kcal | 250 kcal | Energy",
            "الدهون | 3.6 g | 9 g | Fat",
        ])
    }

    @Test
    func recognizesNutritionLabelsInSeveralScripts() {
        #expect(NutritionLabelText.isLikelyNutritionLabel(NutritionLabelText.lines(from: usLabel)))
        #expect(NutritionLabelText.isLikelyNutritionLabel([
            "Nährwerte | pro 100 g", "Energie | 1046kJ/250kcal", "Fett | 9,5 g", "Eiweiß | 3 g",
        ]))
        #expect(NutritionLabelText.isLikelyNutritionLabel([
            "Пищевая ценность", "Энергетическая ценность 250 ккал", "Белки 3 г", "Жиры 8 г",
        ]))
        #expect(NutritionLabelText.isLikelyNutritionLabel([
            "营养成分表", "能量 | 1046千焦 | 12%", "蛋白质 | 3.0克 | 5%", "脂肪 | 8.0克 | 13%",
        ]))
    }

    @Test
    func ignoresPhotosWithoutANutritionPanel() {
        #expect(!NutritionLabelText.isLikelyNutritionLabel([]))
        // A kitchen scale and a receipt have numbers but no energy amount.
        #expect(!NutritionLabelText.isLikelyNutritionLabel(["Tare", "0 g", "235 g", "Batt 80%"]))
        #expect(!NutritionLabelText.isLikelyNutritionLabel(["Total 12.50", "Tax 1.00", "Cash 20.00"]))
        // "cal" inside "Calcium" is not an energy unit.
        #expect(!NutritionLabelText.isLikelyNutritionLabel(["Calcium 20mg", "Iron 2mg", "Potassium 240mg"]))
        // An energy amount alone is a front-of-pack claim, not a panel.
        #expect(!NutritionLabelText.isLikelyNutritionLabel(["Only 99 kcal", "Crunchy oat bar"]))
    }

    @Test
    func promptSectionListsEachLabelPhotoWithTheReadingInstruction() throws {
        #expect(NutritionLabelText.promptSection(for: []) == nil)
        #expect(NutritionLabelText.promptSection(for: [NutritionLabelPhotoText(photoNumber: 1, lines: [])]) == nil)

        #expect(NutritionLabelText.promptSection(for: [
            NutritionLabelPhotoText(photoNumber: 1, lines: ["Nutrition Facts", "Ingredients: oats, honey"]),
        ]) == nil)

        let section = try #require(NutritionLabelText.promptSection(for: [
            NutritionLabelPhotoText(photoNumber: 2, lines: [
                "Nutrition Facts", "Calories | 230", "Ingredients: oats, honey", "Total Fat 8g | 10%",
            ]),
        ]))
        #expect(section.hasPrefix("NUTRITION LABEL TEXT\n"))
        // Rows without a number stay out of the small on-device context.
        #expect(section.contains("Photo 2:\nCalories | 230\nTotal Fat 8g | 10%\n"))
        #expect(!section.contains("Nutrition Facts"))
        #expect(!section.contains("Ingredients"))
        #expect(section.contains("Prefer these recognized numbers over digits read from the image."))
        #expect(section.contains("which column is per serving and which is per 100 g"))
    }

    @Test
    func promptSectionLeavesTheAmountToLogToTheMainPrompt() throws {
        // A scale reading, the visible portion or the user's note still set
        // the amount, as they do for every other provider.
        let section = try #require(NutritionLabelText.promptSection(for: [
            NutritionLabelPhotoText(photoNumber: 1, lines: ["Serving size | 30 g", "Calories | 120"]),
        ]))
        #expect(section.components(separatedBy: "\n").last == """
        Prefer these recognized numbers over digits read from the image. Give each value to the nutrient named on the same line. Use the image to tell which column is per serving and which is per 100 g or 100 ml, and to correct clear recognition mistakes, such as "g" read as "9" or "O" read as "0".
        """)
        #expect(!section.localizedCaseInsensitiveContains("one serving"))
        #expect(!section.localizedCaseInsensitiveContains("note"))
    }

    @Test
    func promptSectionStaysWithinTheLineAndCharacterBudget() throws {
        let manyRows = (1...100).map { "Row \($0) | 1 g" }
        let rowSection = try #require(NutritionLabelText.promptSection(for: [
            NutritionLabelPhotoText(photoNumber: 1, lines: manyRows),
        ]))
        #expect(rowSection.contains("\nRow \(NutritionLabelText.maxLinesPerPhoto) | 1 g\n"))
        #expect(!rowSection.contains("Row \(NutritionLabelText.maxLinesPerPhoto + 1) |"))

        let longRow = String(repeating: "x", count: 99) + "1"
        let longSection = try #require(NutritionLabelText.promptSection(for: [
            NutritionLabelPhotoText(photoNumber: 1, lines: Array(repeating: longRow, count: 30)),
            NutritionLabelPhotoText(photoNumber: 2, lines: ["Calories | 230"]),
        ]))
        let keptRows = longSection.components(separatedBy: "\n").filter { $0 == longRow }
        #expect(!keptRows.isEmpty)
        #expect(keptRows.count * (longRow.count + 1) <= NutritionLabelText.maxCharacters)
        #expect(keptRows.count < 30)
        // The budget is spent, so later photos add nothing.
        #expect(!longSection.contains("Photo 2:"))
    }

    @Test @MainActor
    func onlyOnDeviceModelsGetTheLabelText() async throws {
        let base = GeminiService.multiPhotoAnalysisPrompt(progressiveMeal: false, description: "half the bag")
        let section = try #require(NutritionLabelText.promptSection(for: [
            NutritionLabelPhotoText(photoNumber: 1, lines: ["Calories | 230", "Protein 3g"]),
        ]))
        let onDevice = NutritionLabelText.prompt(base, adding: section)
        #expect(onDevice == base + "\n\n" + section)
        #expect(NutritionLabelText.prompt(base, adding: nil) == base)

        #expect(Set(AIProvider.allCases.filter(\.runsOnDevice)) == [.appleIntelligence, .gemma4Local, .gemma4E4BLocal])
        var reads = 0
        let labelText = OnDeviceLabelText {
            reads += 1
            return section
        }
        for provider in AIProvider.allCases where !provider.runsOnDevice {
            #expect(try await labelText.prompt(base, for: provider) == base, "\(provider)")
        }
        // A cloud request never waits for Vision.
        #expect(reads == 0)

        // An on-device fallback after a cloud provider fails gets the label
        // text too, and the photos are read once per request.
        #expect(try await labelText.prompt(base, for: .gemma4Local) == onDevice)
        #expect(try await labelText.prompt(base, for: .appleIntelligence) == onDevice)
        #expect(try await labelText.section() == section)
        #expect(reads == 1)

        let noLabel = OnDeviceLabelText { nil }
        #expect(try await noLabel.prompt(base, for: .gemma4Local) == base)
        #expect(try await noLabel.section() == nil)
    }

    @Test @MainActor
    func cancelledLabelReadingCancelsTheScan() async throws {
        let labelText = OnDeviceLabelText { throw CancellationError() }
        await #expect(throws: CancellationError.self) {
            try await labelText.prompt("Analyze", for: .gemma4Local)
        }
        // A cloud request does not read the photos, so it is not affected.
        #expect(try await labelText.prompt("Analyze", for: .gemini) == "Analyze")

        let photo = UIGraphicsImageRenderer(size: CGSize(width: 8, height: 8)).image { context in
            UIColor.white.setFill()
            context.fill(CGRect(x: 0, y: 0, width: 8, height: 8))
        }
        // Both run on the main actor, so the scan is cancelled before it starts.
        let scan = Task { @MainActor in
            try await NutritionLabelTextRecognizer.labelText(in: [photo, photo])
        }
        scan.cancel()
        await #expect(throws: CancellationError.self) {
            try await scan.value
        }
    }
}

/// SplitMix64, so seeded tests build the same labels on every run.
private struct SeededGenerator: RandomNumberGenerator {
    private var state: UInt64

    init(seed: UInt64) {
        state = seed
    }

    mutating func next() -> UInt64 {
        state &+= 0x9E37_79B9_7F4A_7C15
        var value = state
        value = (value ^ (value >> 30)) &* 0xBF58_476D_1CE4_E5B9
        value = (value ^ (value >> 27)) &* 0x94D0_49BB_1331_11EB
        return value ^ (value >> 31)
    }
}
