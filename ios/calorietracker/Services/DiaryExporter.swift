import Foundation

/// Output format for a food-diary export.
enum DiaryExportFormat: String, CaseIterable, Identifiable {
    case json, markdown, csv
    var id: String { rawValue }
    var label: String {
        switch self {
        case .json: return "JSON"
        case .markdown: return "Markdown"
        case .csv: return "CSV"
        }
    }
    var fileExtension: String {
        switch self {
        case .json: return "json"
        case .markdown: return "md"
        case .csv: return "csv"
        }
    }
}

/// Date-range presets for an export.
enum DiaryExportRange: String, CaseIterable, Identifiable {
    case today, thisWeek, thisMonth, allTime, custom
    var id: String { rawValue }
    var label: String {
        switch self {
        case .today: return "Today"
        case .thisWeek: return "This week"
        case .thisMonth: return "This month"
        case .allTime: return "All time"
        case .custom: return "Custom"
        }
    }
}

/// Builds a shareable food-diary file (JSON / Markdown / CSV) from the local log.
/// Pure logic — no UI. Everything comes from the already-loaded FoodStore + profile.
enum DiaryExporter {

    // MARK: - Public entry point

    /// Returns (filename, data) for the given inclusive day range, or nil if there is
    /// nothing logged in that range.
    static func build(
        from startDay: Date,
        to endDay: Date,
        format: DiaryExportFormat,
        foodStore: FoodStore,
        profile: UserProfile,
        waterEntries: [WaterEntry] = []
    ) -> (filename: String, data: Data)? {
        build(
            from: startDay,
            to: endDay,
            format: format,
            entries: foodStore.entries,
            profile: profile,
            waterEntries: waterEntries
        )
    }

    /// Pure entry-based overload used by the app exporter and unit tests.
    static func build(
        from startDay: Date,
        to endDay: Date,
        format: DiaryExportFormat,
        entries: [FoodEntry],
        profile: UserProfile,
        waterEntries: [WaterEntry] = []
    ) -> (filename: String, data: Data)? {
        let cal = Calendar.current
        let start = cal.startOfDay(for: min(startDay, endDay))
        let end = cal.startOfDay(for: max(startDay, endDay))

        var days: [DayBundle] = []
        var day = start
        while day <= end {
            let dayEntries = entries
                .filter { cal.isDate($0.timestamp, inSameDayAs: day) }
                .sorted { $0.timestamp > $1.timestamp }
            let water = waterEntries.filter { cal.isDate($0.date, inSameDayAs: day) }.sorted { $0.date < $1.date }
            if !dayEntries.isEmpty || !water.isEmpty {
                let groups = MealType.allCases.compactMap { meal -> FoodLogMealGroup? in
                    let mealEntries = dayEntries.filter { $0.mealType == meal }
                    guard !mealEntries.isEmpty else { return nil }
                    return FoodLogMealGroup(id: "export-\(meal.rawValue)", meal: meal, entries: mealEntries)
                }
                days.append(DayBundle(date: day, groups: groups, water: water))
            }
            guard let next = cal.date(byAdding: .day, value: 1, to: day) else { break }
            day = next
        }
        guard !days.isEmpty else { return nil }

        let targets = Targets(
            calories: profile.effectiveCalories,
            protein: Double(profile.effectiveProtein),
            carbs: Double(profile.effectiveCarbs),
            fat: Double(profile.effectiveFat)
        )

        let text: String
        switch format {
        case .json: text = json(days, start: start, end: end, targets: targets)
        case .markdown: text = markdown(days, start: start, end: end, targets: targets)
        case .csv: text = csv(days)
        }

        let name = "Fud-Food-Diary-\(dayFmt.string(from: start))_to_\(dayFmt.string(from: end)).\(format.fileExtension)"
        return (name, Data(text.utf8))
    }

    // MARK: - Range resolution

    /// Resolves a preset (or custom bounds) into an inclusive (startDay, endDay).
    static func resolve(_ range: DiaryExportRange, customStart: Date, customEnd: Date, foodStore: FoodStore, waterEntries: [WaterEntry] = []) -> (Date, Date) {
        let cal = Calendar.current
        let today = cal.startOfDay(for: .now)
        switch range {
        case .today:
            return (today, today)
        case .thisWeek:
            let start = cal.dateInterval(of: .weekOfYear, for: today)?.start ?? today
            return (start, today)
        case .thisMonth:
            let start = cal.dateInterval(of: .month, for: today)?.start ?? today
            return (start, today)
        case .allTime:
            let earliest = (foodStore.entries.map(\.timestamp) + waterEntries.map(\.date)).min().map { cal.startOfDay(for: $0) } ?? today
            return (earliest, today)
        case .custom:
            return (cal.startOfDay(for: customStart), cal.startOfDay(for: customEnd))
        }
    }

    // MARK: - Internal shapes

    private struct DayBundle { let date: Date; let groups: [FoodLogMealGroup]; let water: [WaterEntry] }
    private struct Targets { let calories: Int; let protein: Double; let carbs: Double; let fat: Double }

    private static func totals(_ groups: [FoodLogMealGroup]) -> (cal: Int, p: Double, c: Double, f: Double) {
        var cal = 0; var p = 0.0; var c = 0.0; var f = 0.0
        for g in groups { for e in g.entries { cal += e.calories; p += e.protein; c += e.carbs; f += e.fat } }
        return (cal, p, c, f)
    }

    static func sourceLabel(_ source: FoodSource) -> String {
        source == .manual ? "manually_edited" : "ai_estimated"
    }

    private nonisolated static func r1(_ x: Double) -> Double { (x * 10).rounded() / 10 }

    private nonisolated static func optionalNumber(_ x: Double?, missing: String = "") -> String {
        x.map { String(r1($0)) } ?? missing
    }

    private static let dayFmt: DateFormatter = {
        let f = DateFormatter(); f.locale = Locale(identifier: "en_US_POSIX"); f.dateFormat = "yyyy-MM-dd"; return f
    }()
    private static let timeFmt: DateFormatter = {
        let f = DateFormatter(); f.locale = Locale(identifier: "en_US_POSIX"); f.dateFormat = "HH:mm"; return f
    }()

    // MARK: - JSON

    private static func json(_ days: [DayBundle], start: Date, end: Date, targets: Targets) -> String {
        struct Macro: Encodable { let calories: Int; let protein_g: Double; let carbs_g: Double; let fat_g: Double }
        struct Ingredient: Encodable {
            let name: String; let quantity_g: Double; let calories: Int
            let protein_g: Double; let carbs_g: Double; let fat_g: Double
        }
        struct Item: Encodable {
            let entry_id: String; let name: String; let quantity_g: Double?; let calories: Int
            let protein_g: Double; let carbs_g: Double; let fat_g: Double
            let sugar_g: Double?; let added_sugar_g: Double?; let fiber_g: Double?
            let saturated_fat_g: Double?; let monounsaturated_fat_g: Double?; let polyunsaturated_fat_g: Double?
            let cholesterol_mg: Double?; let caffeine_mg: Double?; let sodium_mg: Double?; let potassium_mg: Double?
            let supplemental_nutrients_g: [String: Double]
            let trans_fat_g: Double?; let calcium_mg: Double?; let iron_mg: Double?
            let magnesium_mg: Double?; let zinc_mg: Double?
            let vitamin_a_mcg: Double?; let vitamin_c_mg: Double?; let vitamin_d_mcg: Double?
            let vitamin_b12_mcg: Double?; let vitamin_e_mg: Double?; let vitamin_k_mcg: Double?
            let folate_mcg: Double?; let omega3_g: Double?
            let time: String; let source: String; let note: String?; let ingredients: [Ingredient]
        }
        struct Meal: Encodable { let type: String; let items: [Item] }
        struct Water: Encodable { let entry_id: String; let time: String; let milliliters: Int }
        struct Day: Encodable { let date: String; let totals: Macro; let targets: Macro; let remaining: Macro; let meals: [Meal]; let water_entries: [Water]; let water_total_ml: Int }
        struct Meta: Encodable { struct Range: Encodable { let start: String; let end: String }
            let app: String; let format_version: String; let date_range: Range }
        struct Doc: Encodable { let export: Meta; let days: [Day] }

        let dayDocs: [Day] = days.map { bundle in
            let t = totals(bundle.groups)
            let meals: [Meal] = bundle.groups.map { g in
                Meal(type: g.meal.rawValue, items: g.entries.map { e in
                    Item(entry_id: e.id.uuidString, name: e.name, quantity_g: e.servingSizeGrams.map(r1), calories: e.calories,
                         protein_g: r1(e.protein), carbs_g: r1(e.carbs), fat_g: r1(e.fat),
                         sugar_g: e.sugar.map(r1), added_sugar_g: e.addedSugar.map(r1), fiber_g: e.fiber.map(r1),
                         saturated_fat_g: e.saturatedFat.map(r1),
                         monounsaturated_fat_g: e.monounsaturatedFat.map(r1),
                         polyunsaturated_fat_g: e.polyunsaturatedFat.map(r1),
                         cholesterol_mg: e.cholesterol.map(r1), caffeine_mg: e.caffeine.map(r1), sodium_mg: e.sodium.map(r1),
                         potassium_mg: e.potassium.map(r1),
                         supplemental_nutrients_g: e.supplementalNutrients.mapValues(r1),
                         trans_fat_g: e.transFat.map(r1),
                         calcium_mg: e.calcium.map(r1), iron_mg: e.iron.map(r1),
                         magnesium_mg: e.magnesium.map(r1), zinc_mg: e.zinc.map(r1),
                         vitamin_a_mcg: e.vitaminA.map(r1), vitamin_c_mg: e.vitaminC.map(r1),
                         vitamin_d_mcg: e.vitaminD.map(r1), vitamin_b12_mcg: e.vitaminB12.map(r1),
                         vitamin_e_mg: e.vitaminE.map(r1), vitamin_k_mcg: e.vitaminK.map(r1),
                         folate_mcg: e.folate.map(r1), omega3_g: e.omega3.map(r1),
                         time: timeFmt.string(from: e.timestamp), source: sourceLabel(e.source),
                         note: (e.customNote?.isEmpty == false) ? e.customNote : nil,
                         ingredients: e.ingredients.map { ingredient in
                             Ingredient(
                                name: ingredient.name, quantity_g: r1(ingredient.grams), calories: ingredient.calories,
                                protein_g: r1(ingredient.protein), carbs_g: r1(ingredient.carbs), fat_g: r1(ingredient.fat)
                             )
                         })
                })
            }
            return Day(
                date: dayFmt.string(from: bundle.date),
                totals: Macro(calories: t.cal, protein_g: r1(t.p), carbs_g: r1(t.c), fat_g: r1(t.f)),
                targets: Macro(calories: targets.calories, protein_g: targets.protein, carbs_g: targets.carbs, fat_g: targets.fat),
                remaining: Macro(calories: max(0, targets.calories - t.cal),
                                 protein_g: r1(max(0, targets.protein - t.p)),
                                 carbs_g: r1(max(0, targets.carbs - t.c)),
                                 fat_g: r1(max(0, targets.fat - t.f))),
                meals: meals,
                water_entries: bundle.water.map { Water(entry_id: $0.id.uuidString, time: timeFmt.string(from: $0.date), milliliters: $0.milliliters) },
                water_total_ml: bundle.water.reduce(0) { $0 + $1.milliliters }
            )
        }
        let doc = Doc(export: Meta(app: "Fud AI", format_version: "1.5",
                                   date_range: Meta.Range(start: dayFmt.string(from: start), end: dayFmt.string(from: end))),
                      days: dayDocs)
        let enc = JSONEncoder()
        enc.outputFormatting = [.prettyPrinted, .withoutEscapingSlashes]
        return (try? enc.encode(doc)).flatMap { String(data: $0, encoding: .utf8) } ?? "{}"
    }

    // MARK: - Markdown

    private static func markdown(_ days: [DayBundle], start: Date, end: Date, targets: Targets) -> String {
        var s = "# Food diary export\n"
        s += "Date range: \(dayFmt.string(from: start)) to \(dayFmt.string(from: end))\n"
        s += "Generated by Fud AI\n"
        for bundle in days {
            let t = totals(bundle.groups)
            s += "\n## \(dayFmt.string(from: bundle.date))\n"
            s += "Totals:\n"
            s += "- Calories: \(t.cal) / \(targets.calories) kcal\n"
            s += "- Protein: \(r1(t.p)) / \(Int(targets.protein)) g\n"
            s += "- Carbs: \(r1(t.c)) / \(Int(targets.carbs)) g\n"
            s += "- Fat: \(r1(t.f)) / \(Int(targets.fat)) g\n"
            s += "- Water: \(bundle.water.reduce(0) { $0 + $1.milliliters }) ml\n"
            if !bundle.water.isEmpty {
                s += "### Water\n| Time | Amount (ml) |\n|---|---:|\n"
                for entry in bundle.water {
                    s += "| \(timeFmt.string(from: entry.date)) | \(entry.milliliters) |\n"
                }
            }
            for g in bundle.groups {
                s += "### \(g.meal.displayName)\n"
                s += "| Time | Food | Weight | Calories | Protein (g) | Carbs (g) | Fat (g) | Sugar (g) | Added sugar (g) | Fiber (g) | Saturated fat (g) | Monounsaturated fat (g) | Polyunsaturated fat (g) | Cholesterol (mg) | Caffeine (mg) | Sodium (mg) | Potassium (mg) | Trans fat (g) | Calcium (mg) | Iron (mg) | Magnesium (mg) | Zinc (mg) | Vitamin A (mcg) | Vitamin C (mg) | Vitamin D (mcg) | Vitamin B12 (mcg) | Vitamin E (mg) | Vitamin K (mcg) | Folate (mcg) | Omega-3 (g) | Source | Ingredients |\n"
                s += "|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|---|\n"
                for e in g.entries {
                    let weight = e.servingSizeGrams.map { "\(Int($0)) g" } ?? "-"
                    let food = e.name.replacingOccurrences(of: "|", with: "/")
                    let cells: [String] = [
                        timeFmt.string(from: e.timestamp), food, weight,
                        String(e.calories), String(r1(e.protein)), String(r1(e.carbs)), String(r1(e.fat)),
                        optionalNumber(e.sugar, missing: "-"), optionalNumber(e.addedSugar, missing: "-"),
                        optionalNumber(e.fiber, missing: "-"), optionalNumber(e.saturatedFat, missing: "-"),
                        optionalNumber(e.monounsaturatedFat, missing: "-"), optionalNumber(e.polyunsaturatedFat, missing: "-"),
                        optionalNumber(e.cholesterol, missing: "-"), optionalNumber(e.caffeine, missing: "-"), optionalNumber(e.sodium, missing: "-"),
                        optionalNumber(e.potassium, missing: "-"), optionalNumber(e.transFat, missing: "-"),
                        optionalNumber(e.calcium, missing: "-"), optionalNumber(e.iron, missing: "-"),
                        optionalNumber(e.magnesium, missing: "-"), optionalNumber(e.zinc, missing: "-"),
                        optionalNumber(e.vitaminA, missing: "-"), optionalNumber(e.vitaminC, missing: "-"),
                        optionalNumber(e.vitaminD, missing: "-"), optionalNumber(e.vitaminB12, missing: "-"),
                        optionalNumber(e.vitaminE, missing: "-"), optionalNumber(e.vitaminK, missing: "-"),
                        optionalNumber(e.folate, missing: "-"), optionalNumber(e.omega3, missing: "-"),
                        sourceLabel(e.source), ingredientsText(e)
                    ]
                    s += "| " + cells.joined(separator: " | ") + " |\n"
                }
            }
        }
        return s
    }

    // MARK: - CSV

    private static func csv(_ days: [DayBundle]) -> String {
        var s = "date,meal,time,food,weight_g,calories,protein_g,carbs_g,fat_g,sugar_g,added_sugar_g,fiber_g,saturated_fat_g,monounsaturated_fat_g,polyunsaturated_fat_g,cholesterol_mg,caffeine_mg,sodium_mg,potassium_mg,trans_fat_g,calcium_mg,iron_mg,magnesium_mg,zinc_mg,vitamin_a_mcg,vitamin_c_mg,vitamin_d_mcg,vitamin_b12_mcg,vitamin_e_mg,vitamin_k_mcg,folate_mcg,omega3_g,source,note,ingredients,entry_type,water_ml,water_total_ml\n"
        for bundle in days {
            let date = dayFmt.string(from: bundle.date)
            for g in bundle.groups {
                for e in g.entries {
                    let cols: [String] = [
                        date, g.meal.rawValue, timeFmt.string(from: e.timestamp), e.name,
                        e.servingSizeGrams.map { String(Int($0)) } ?? "",
                        String(e.calories), String(r1(e.protein)), String(r1(e.carbs)), String(r1(e.fat)),
                        optionalNumber(e.sugar), optionalNumber(e.addedSugar), optionalNumber(e.fiber),
                        optionalNumber(e.saturatedFat), optionalNumber(e.monounsaturatedFat),
                        optionalNumber(e.polyunsaturatedFat), optionalNumber(e.cholesterol), optionalNumber(e.caffeine),
                        optionalNumber(e.sodium), optionalNumber(e.potassium), optionalNumber(e.transFat),
                        optionalNumber(e.calcium), optionalNumber(e.iron), optionalNumber(e.magnesium),
                        optionalNumber(e.zinc), optionalNumber(e.vitaminA), optionalNumber(e.vitaminC),
                        optionalNumber(e.vitaminD), optionalNumber(e.vitaminB12), optionalNumber(e.vitaminE),
                        optionalNumber(e.vitaminK), optionalNumber(e.folate), optionalNumber(e.omega3),
                        sourceLabel(e.source), e.customNote ?? "", ingredientsText(e), "food", "", ""
                    ]
                    s += cols.map(csvEscape).joined(separator: ",") + "\n"
                }
            }
            let total = bundle.water.reduce(0) { $0 + $1.milliliters }
            for entry in bundle.water {
                var cols = Array(repeating: "", count: 38)
                cols[0] = date; cols[2] = timeFmt.string(from: entry.date); cols[3] = "Water"
                cols[35] = "water"; cols[36] = String(entry.milliliters)
                s += cols.map(csvEscape).joined(separator: ",") + "\n"
            }
            var summary = Array(repeating: "", count: 38)
            summary[0] = date; summary[35] = "water_total"; summary[37] = String(total)
            s += summary.joined(separator: ",") + "\n"
        }
        return s
    }

    private nonisolated static func csvEscape(_ field: String) -> String {
        if field.contains(",") || field.contains("\"") || field.contains("\n") {
            return "\"" + field.replacingOccurrences(of: "\"", with: "\"\"") + "\""
        }
        return field
    }

    private nonisolated static func ingredientsText(_ entry: FoodEntry) -> String {
        entry.ingredients.map { ingredient in
            "\(ingredient.name) (\(r1(ingredient.grams))g · \(ingredient.calories) kcal · P \(r1(ingredient.protein))g · C \(r1(ingredient.carbs))g · F \(r1(ingredient.fat))g)"
        }.joined(separator: "; ").replacingOccurrences(of: "|", with: "/")
    }
}
