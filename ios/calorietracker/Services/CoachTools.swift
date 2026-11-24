import Foundation

/// On-demand data accessor for Coach. Replaces the old "dump everything into
/// the system prompt" pattern: instead of stuffing the prompt with the last
/// N weights + N body fats + N days of food, we expose a small tool kit that
/// the LLM can call when it actually needs older / specific data.
///
/// Three provider formats (Gemini / Anthropic Messages / OpenAI-compatible)
/// each have a slightly different tool schema shape. CoachTools owns the
/// execute() side — turning a tool name + JSON args into a JSON result —
/// while the per-provider tool definitions live alongside each provider's
/// HTTP layer (formatted for that API in callX).
///
/// Date format on the API: ISO `yyyy-MM-dd`. Each list-returning tool caps
/// results at 365 entries to bound any one tool result's size — Coach can
/// always issue a narrower range for older history if it needs more.
struct CoachTools {
    let weights: [WeightEntry]
    let bodyFats: [BodyFatEntry]
    let foods: [FoodEntry]
    var fastingSessions: [FastingSession] = []
    var workoutSessions: [StrengthWorkoutSession] = []
    var workoutPlans: [StrengthWorkoutDayPlan] = []
    var workoutPreferences: StrengthWorkoutPreferences? = nil
    var workoutPlanWeightUnit: WeightUnit = .lbs
    var workoutAccessEnabled = false
    /// Built-in catalog plus the user's custom exercises, for the read-only library search.
    var exerciseLibrary: [ExerciseLibraryItem] = []
    /// Set only by Coach chat, which shows each proposal as a card the user must confirm.
    /// Without it the proposal tools are not offered.
    var proposals: CoachExerciseProposalCollector? = nil

    static let nutritionToolNames: [String] = [
        "get_data_summary",
        "get_weight_history",
        "get_body_fat_history",
        "get_calorie_totals",
        "get_food_entries",
        "get_fasting_history",
    ]

    static let workoutToolNames: [String] = [
        "get_workout_history",
        "get_workout_plans",
        "get_workout_preferences",
        "get_training_summary",
        "get_exercise_lift_history",
        "search_exercise_library",
    ]

    /// Workout changes Coach can only propose. Each call returns a proposal id and the chat shows
    /// a card; nothing is written until the user taps Add.
    static let proposalToolNames: [String] = [
        "propose_custom_exercise",
        "propose_add_exercise_to_day",
    ]

    /// The provider schemas are built from this instance value, preventing any
    /// workout tool from being disclosed when the user has the diary disabled.
    var availableToolNames: [String] {
        guard workoutAccessEnabled else { return Self.nutritionToolNames }
        return Self.nutritionToolNames + Self.workoutToolNames + (proposals == nil ? [] : Self.proposalToolNames)
    }

    /// Timer-era builds could store several completed sessions for one diary
    /// day. Once the user calculates a daily burn, that snapshot represents the
    /// current diary, so prefer it over older same-day snapshots and avoid
    /// double-counting their sets and reps in Coach.
    private var effectiveWorkoutSessions: [StrengthWorkoutSession] {
        Dictionary(grouping: workoutSessions, by: \.stableDiaryDateKey)
            .values
            .flatMap { sessions -> [StrengthWorkoutSession] in
                let burns = sessions.filter { $0.caloriesBurned != nil }
                guard !burns.isEmpty else { return sessions }
                let latest = burns.max {
                    let leftVersion = $0.healthSyncVersion ?? 0
                    let rightVersion = $1.healthSyncVersion ?? 0
                    if leftVersion == rightVersion { return $0.completedAt < $1.completedAt }
                    return leftVersion < rightVersion
                }
                return latest.map { [$0] } ?? []
            }
    }

    /// Per-provider tool descriptions kept in one place so all three formats
    /// see the same human-readable text.
    static let toolDescriptions: [String: String] = [
        "get_data_summary": "Get a quick summary of the user's available data: total counts and earliest/latest dates for weights, body-fat readings, and food entries. Call this first when the user asks anything about their history range or data spanning more than 14 days.",
        "get_weight_history": "Fetch weight entries between two dates (inclusive). Returns date + weight (kg + lbs). Use this when the user asks about specific past dates or weight trends older than the last 10 entries.",
        "get_body_fat_history": "Fetch body-fat readings between two dates (inclusive). Returns date + percent. Use when the user asks about body composition trends older than the last 10 readings.",
        "get_calorie_totals": "Daily calorie totals (sum of all logged foods per day) between two dates. Returns date + kcal. Use when the user asks about intake patterns older than the last 14 days.",
        "get_food_entries": "Individual logged food items (name, calories, macros and whether a photo is attached) between two dates. Use for specific meals, dates, photos, or macro breakdowns.",
        "get_fasting_history": "Fetch explicitly tracked fasting sessions between two dates, including start/end timestamps, duration, goal, and whether the goal was reached. Never infer fasting from missing food logs.",
        "get_workout_history": "Fetch completed workouts between two dates, including calculated calorie burn, saved exercise durations and intensity, and logged sets with weight, reps, and RPE. A timed exercise can be performed without any reps or sets. For one specific lift across many dates, prefer get_exercise_lift_history.",
        "get_workout_plans": "Fetch dated workout diary plans, set targets, saved exercise durations, and current timer state. Running or paused timer time is unsaved. Optional ISO from/to dates narrow the result; without them it returns recent and upcoming plans around today.",
        "get_workout_preferences": "Fetch workout-only preferences such as target muscles, injuries or issues, equipment, schedule, split, RPE scale, and strength numbers.",
        "get_training_summary": "Summarize workouts between two dates: calculated calorie burn plus sessions, saved exercise duration, sets, reps, volume, best load, and average RPE by exercise. Timed cardio does not require reps or sets.",
        "get_exercise_lift_history": "Fetch per-exercise lift history from the local workout diary: dated sessions with logged weight × reps sets, the most recent session, and best load in kg. Use for questions like \"what did I bench last?\" or trends for one lift. Optional catalog_id narrows matching; otherwise exercise name is used.",
        "search_exercise_library": "Search the exercise library (built-in catalog plus the user's own custom exercises) by name or keywords. Returns matching exercises with catalog_id, name, category, equipment, primary and secondary muscles, and is_custom. Optional muscle and equipment filters narrow the results. When the user shares a photo of a gym machine or exercise, identify it first, then search with its likely names. Read-only: it cannot add, edit, or create exercises.",
        "propose_custom_exercise": "Propose creating a custom exercise in the user's exercise library, for example from a photo of a gym machine that search_exercise_library did not find. This does NOT save anything: the user sees a card with the details and must tap Add to create it, or Dismiss. Search the library first and propose only exercises that are not already there, using the library's category, equipment and muscle names. Returns a proposal_id. Afterwards tell the user to review the card; never say the exercise was created.",
        "propose_add_exercise_to_day": "Propose adding one library exercise to the user's workout diary on a date (default today). Use a catalog_id from search_exercise_library, built-in or custom. Optional sets, reps and duration_minutes prefill the entry; reps and duration record performed work, so a future date takes sets only. This does NOT save anything: the user sees a card and must tap Add, or Dismiss. A custom exercise proposed with propose_custom_exercise can be added only after the user has added it and it shows up in search_exercise_library. Returns a proposal_id. Afterwards tell the user to review the card; never say it was added.",
    ]

    /// The bundled catalog's muscle, equipment and category names, which custom exercises pick
    /// from too. The schemas list them so the model uses the words the library uses.
    static let libraryMuscles = [
        "abdominals", "abductors", "adductors", "biceps", "calves", "chest", "forearms", "glutes", "hamstrings",
        "lats", "lower back", "middle back", "neck", "quadriceps", "shoulders", "traps", "triceps",
    ]
    static let libraryEquipment = [
        "bands", "barbell", "body only", "cable", "dumbbell", "e-z curl bar", "exercise ball", "foam roll",
        "kettlebells", "machine", "medicine ball", "other",
    ]
    static let libraryCategories = [
        "cardio", "olympic weightlifting", "plyometrics", "powerlifting", "strength", "stretching", "strongman",
    ]
    static let libraryMuscleNames = libraryMuscles.joined(separator: ", ")
    static let libraryEquipmentNames = libraryEquipment.joined(separator: ", ")
    static let libraryCategoryNames = libraryCategories.joined(separator: ", ")

    /// One schema source is translated to each provider's wrapper by
    /// ChatService, so no-argument workout tools never accidentally inherit a
    /// required date range.
    static func parameterSchema(for toolName: String) -> [String: Any] {
        if ["get_data_summary", "get_workout_preferences"].contains(toolName) {
            return ["type": "object", "properties": [:]]
        }
        if toolName == "get_workout_plans" {
            return [
                "type": "object",
                "properties": [
                    "from": ["type": "string", "description": "Optional ISO date yyyy-MM-dd, inclusive start"],
                    "to": ["type": "string", "description": "Optional ISO date yyyy-MM-dd, inclusive end"],
                    "limit": ["type": "integer", "description": "Optional max plans to return"],
                ],
            ]
        }
        if toolName == "get_exercise_lift_history" {
            return [
                "type": "object",
                "properties": [
                    "exercise": ["type": "string", "description": "Exercise name, e.g. Bench Press"],
                    "catalog_id": ["type": "string", "description": "Optional library id when known"],
                    "from": ["type": "string", "description": "Optional ISO date yyyy-MM-dd, inclusive start (defaults to one year before to)"],
                    "to": ["type": "string", "description": "Optional ISO date yyyy-MM-dd, inclusive end (defaults to today)"],
                    "limit": ["type": "integer", "description": "Optional max dated sessions to return"],
                ],
                "required": ["exercise"],
            ]
        }
        if toolName == "search_exercise_library" {
            return [
                "type": "object",
                "properties": [
                    "query": ["type": "string", "description": "Exercise name or keywords, e.g. chest press machine"],
                    "muscle": ["type": "string", "description": "Optional muscle filter using the library's muscle names: \(libraryMuscleNames)"],
                    "equipment": ["type": "string", "description": "Optional equipment filter using the library's equipment names: \(libraryEquipmentNames)"],
                    "limit": ["type": "integer", "description": "Optional max exercises to return (default 10, max 20)"],
                ],
                "required": ["query"],
            ]
        }
        if toolName == "propose_custom_exercise" {
            return [
                "type": "object",
                "properties": [
                    "name": ["type": "string", "description": "Exercise name, e.g. Hammer Strength Iso-Lateral Chest Press"],
                    "category": ["type": "string", "description": "Optional category (default strength): \(libraryCategoryNames)"],
                    "equipment": ["type": "string", "description": "Optional equipment: \(libraryEquipmentNames)"],
                    "primary_muscles": [
                        "type": "array",
                        "items": ["type": "string"],
                        "description": "One or more muscles the exercise mainly works: \(libraryMuscleNames)",
                    ],
                    "secondary_muscles": [
                        "type": "array",
                        "items": ["type": "string"],
                        "description": "Optional muscles it also works, from the same list",
                    ],
                    "instructions": ["type": "string", "description": "Optional short how-to steps, one per line"],
                ],
                "required": ["name", "primary_muscles"],
            ]
        }
        if toolName == "propose_add_exercise_to_day" {
            return [
                "type": "object",
                "properties": [
                    "catalog_id": ["type": "string", "description": "catalog_id of the exercise from search_exercise_library (built-in or custom)"],
                    "date": ["type": "string", "description": "Optional ISO date yyyy-MM-dd within a year of today (defaults to today)"],
                    "sets": ["type": "integer", "description": "Optional number of sets, 1–12"],
                    "reps": ["type": "integer", "description": "Optional reps for every set, 1–999; today or earlier only"],
                    "duration_minutes": ["type": "integer", "description": "Optional minutes of timed work such as cardio, 1–1440; today or earlier only"],
                ],
                "required": ["catalog_id"],
            ]
        }
        return [
            "type": "object",
            "properties": [
                "from": ["type": "string", "description": "ISO date yyyy-MM-dd, inclusive start"],
                "to": ["type": "string", "description": "ISO date yyyy-MM-dd, inclusive end"],
                "limit": ["type": "integer", "description": "Optional max entries to return"],
            ],
            "required": ["from", "to"],
        ]
    }

    // MARK: - Execution

    /// Turn a tool call into a JSON-encoded result string. Unknown tool names
    /// return a JSON error so the LLM can correct course rather than silently
    /// hallucinate; callers should always pass through whatever this returns.
    func execute(name: String, arguments: [String: Any]) -> String {
        if Self.workoutToolNames.contains(name) || Self.proposalToolNames.contains(name), !workoutAccessEnabled {
            return jsonError("Workout access is disabled.")
        }
        switch name {
        case "get_data_summary":
            return getDataSummary()
        case "get_weight_history":
            return getWeightHistory(arguments: arguments)
        case "get_body_fat_history":
            return getBodyFatHistory(arguments: arguments)
        case "get_calorie_totals":
            return getCalorieTotals(arguments: arguments)
        case "get_food_entries":
            return getFoodEntries(arguments: arguments)
        case "get_fasting_history":
            return getFastingHistory(arguments: arguments)
        case "get_workout_history":
            return getWorkoutHistory(arguments: arguments)
        case "get_workout_plans":
            return getWorkoutPlans(arguments: arguments)
        case "get_workout_preferences":
            return getWorkoutPreferences()
        case "get_training_summary":
            return getTrainingSummary(arguments: arguments)
        case "get_exercise_lift_history":
            return getExerciseLiftHistory(arguments: arguments)
        case "search_exercise_library":
            return searchExerciseLibrary(arguments: arguments)
        case "propose_custom_exercise", "propose_add_exercise_to_day":
            guard let proposals else { return jsonError("Proposals are only available in Coach chat.") }
            do {
                let proposal = name == "propose_custom_exercise"
                    ? try customExerciseProposal(arguments: arguments)
                    : try addToDayProposal(arguments: arguments)
                guard let stored = proposals.add(proposal) else {
                    return jsonError("This reply already has \(CoachExerciseProposalCollector.maxProposals) proposals. Ask the user to review them first.")
                }
                return proposalResult(stored)
            } catch let error as ProposalArgumentError {
                return jsonError(error.message)
            } catch {
                return jsonError("Could not read the proposal.")
            }
        default:
            return jsonError("Unknown tool: \(name). Available tools: \(availableToolNames.joined(separator: ", "))")
        }
    }

    // MARK: - Tool implementations

    private func getDataSummary() -> String {
        let weightDates = weights.map { $0.date }.sorted()
        let bodyFatDates = bodyFats.map { $0.date }.sorted()
        let foodDates = foods.map { $0.timestamp }.sorted()
        // Explicit `as Any` on the optional → NSNull coalesce so the dictionary
        // literal doesn't trigger Swift's "Any? coerced to Any" warning. Both
        // branches resolve to a concrete JSON-serializable type at runtime.
        let payload: [String: Any] = [
            "weights": [
                "count": weights.count,
                "first_date": (weightDates.first.map(Self.iso) ?? NSNull()) as Any,
                "last_date": (weightDates.last.map(Self.iso) ?? NSNull()) as Any,
            ],
            "body_fats": [
                "count": bodyFats.count,
                "first_date": (bodyFatDates.first.map(Self.iso) ?? NSNull()) as Any,
                "last_date": (bodyFatDates.last.map(Self.iso) ?? NSNull()) as Any,
            ],
            "foods": [
                "count": foods.count,
                "first_date": (foodDates.first.map(Self.iso) ?? NSNull()) as Any,
                "last_date": (foodDates.last.map(Self.iso) ?? NSNull()) as Any,
            ],
            "fasting": [
                "count": fastingSessions.count,
                "active": fastingSessions.contains(where: \.isActive),
            ],
        ]
        guard workoutAccessEnabled else { return jsonString(payload) }
        var expanded = payload
        let visibleWorkoutSessions = effectiveWorkoutSessions
        let workoutDateKeys = visibleWorkoutSessions.map(\.stableDiaryDateKey).sorted()
        expanded["workouts"] = [
            "count": visibleWorkoutSessions.count,
            "first_date": workoutDateKeys.first.map { $0 as Any } ?? NSNull(),
            "last_date": workoutDateKeys.last.map { $0 as Any } ?? NSNull(),
        ]
        expanded["workout_plans"] = ["count": workoutPlans.count]
        return jsonString(expanded)
    }

    private func getWeightHistory(arguments: [String: Any]) -> String {
        let (from, to) = parseRange(arguments)
        let limit = (arguments["limit"] as? Int).map { min(max($0, 1), 365) } ?? 365
        let filtered = weights
            .filter { $0.date >= from && $0.date <= to }
            .sorted { $0.date < $1.date }
            .prefix(limit)
        let entries = filtered.map { entry -> [String: Any] in
            [
                "date": Self.iso(entry.date),
                "kg": (entry.weightKg * 10).rounded() / 10,
                "lbs": (entry.weightKg * 2.20462 * 10).rounded() / 10,
            ]
        }
        return jsonString([
            "from": Self.iso(from),
            "to": Self.iso(to),
            "count": entries.count,
            "weights": entries,
        ])
    }

    private func getBodyFatHistory(arguments: [String: Any]) -> String {
        let (from, to) = parseRange(arguments)
        let limit = (arguments["limit"] as? Int).map { min(max($0, 1), 365) } ?? 365
        let filtered = bodyFats
            .filter { $0.date >= from && $0.date <= to }
            .sorted { $0.date < $1.date }
            .prefix(limit)
        let entries = filtered.map { entry -> [String: Any] in
            [
                "date": Self.iso(entry.date),
                "percent": Int((entry.bodyFatFraction * 100).rounded()),
            ]
        }
        return jsonString([
            "from": Self.iso(from),
            "to": Self.iso(to),
            "count": entries.count,
            "body_fats": entries,
        ])
    }

    private func getCalorieTotals(arguments: [String: Any]) -> String {
        let (from, to) = parseRange(arguments)
        let calendar = Calendar.current
        var dailyKcal: [String: Int] = [:]
        for food in foods where food.timestamp >= from && food.timestamp <= to {
            let day = Self.iso(calendar.startOfDay(for: food.timestamp))
            dailyKcal[day, default: 0] += food.calories
        }
        let totals = dailyKcal
            .sorted { $0.key < $1.key }
            .map { ["date": $0.key, "kcal": $0.value] }
        return jsonString([
            "from": Self.iso(from),
            "to": Self.iso(to),
            "days_with_data": totals.count,
            "totals": totals,
        ])
    }

    private func getFoodEntries(arguments: [String: Any]) -> String {
        let (from, to) = parseRange(arguments)
        let limit = (arguments["limit"] as? Int).map { min(max($0, 1), 365) } ?? 200
        let filtered = foods
            .filter { $0.timestamp >= from && $0.timestamp <= to }
            .sorted { $0.timestamp < $1.timestamp }
            .prefix(limit)
        let entries = filtered.map { entry -> [String: Any] in
            var payload: [String: Any] = [
                // The voice assistant opens, edits and deletes entries by this id.
                "entry_id": entry.id.uuidString,
                "date": Self.iso(entry.timestamp),
                "name": entry.name,
                "kcal": entry.calories,
                "protein_g": entry.protein,
                "carbs_g": entry.carbs,
                "fat_g": entry.fat,
                "meal_type": entry.mealType.rawValue,
                "source": entry.source.rawValue,
                "has_photo": !entry.allImageFilenames.isEmpty || entry.imageData != nil || !entry.additionalImageData.isEmpty,
            ]
            func add(_ key: String, _ value: Double?) {
                if let value {
                    payload[key] = value
                }
            }
            add("serving_size_g", entry.servingSizeGrams)
            add("sugar_g", entry.sugar)
            add("added_sugar_g", entry.addedSugar)
            add("fiber_g", entry.fiber)
            add("saturated_fat_g", entry.saturatedFat)
            add("monounsaturated_fat_g", entry.monounsaturatedFat)
            add("polyunsaturated_fat_g", entry.polyunsaturatedFat)
            add("cholesterol_mg", entry.cholesterol)
            add("caffeine_mg", entry.caffeine)
            for (key, value) in entry.supplementalNutrients {
                payload["\(key)_g"] = value
            }
            add("sodium_mg", entry.sodium)
            add("potassium_mg", entry.potassium)
            add("trans_fat_g", entry.transFat)
            add("calcium_mg", entry.calcium)
            add("iron_mg", entry.iron)
            add("magnesium_mg", entry.magnesium)
            add("zinc_mg", entry.zinc)
            add("vitamin_a_mcg", entry.vitaminA)
            add("vitamin_c_mg", entry.vitaminC)
            add("vitamin_d_mcg", entry.vitaminD)
            add("vitamin_b12_mcg", entry.vitaminB12)
            add("vitamin_e_mg", entry.vitaminE)
            add("vitamin_k_mcg", entry.vitaminK)
            add("folate_mcg", entry.folate)
            add("omega_3_g", entry.omega3)
            return payload
        }
        return jsonString([
            "from": Self.iso(from),
            "to": Self.iso(to),
            "count": entries.count,
            "foods": entries,
        ])
    }

    private func getFastingHistory(arguments: [String: Any]) -> String {
        let (from, to) = parseRange(arguments)
        let limit = (arguments["limit"] as? Int).map { min(max($0, 1), 365) } ?? 200
        let filtered = fastingSessions
            .filter { session in
                let endpoint = session.endedAt ?? .now
                return endpoint >= from && session.startedAt <= to
            }
            .sorted { $0.startedAt < $1.startedAt }
            .prefix(limit)
        let sessions = filtered.map { session -> [String: Any] in
            [
                "status": session.isActive ? "active" : "completed",
                "started_at": Self.isoTimestamp(session.startedAt),
                "ended_at": session.endedAt.map(Self.isoTimestamp) ?? NSNull(),
                "duration_minutes": Int(session.duration() / 60),
                "goal_minutes": session.goalMinutes,
                "goal_reached": session.duration() >= TimeInterval(session.goalMinutes * 60),
            ]
        }
        return jsonString([
            "from": Self.iso(from),
            "to": Self.iso(to),
            "count": sessions.count,
            "sessions": sessions,
        ])
    }

    private func getWorkoutHistory(arguments: [String: Any]) -> String {
        let (from, to) = parseRange(arguments)
        let limit = (arguments["limit"] as? Int).map { min(max($0, 1), 200) } ?? 100
        let sessions = effectiveWorkoutSessions
            .filter { $0.calendarDiaryDate >= from && $0.calendarDiaryDate <= to }
            .sorted {
                if $0.stableDiaryDateKey == $1.stableDiaryDateKey {
                    return $0.completedAt < $1.completedAt
                }
                return $0.stableDiaryDateKey < $1.stableDiaryDateKey
            }
            .prefix(limit)
            .map(workoutSessionPayload)
        return jsonString([
            "from": Self.iso(from),
            "to": Self.iso(to),
            "count": sessions.count,
            "workouts": sessions,
        ])
    }

    private func getWorkoutPlans(arguments: [String: Any]) -> String {
        let (from, to) = parsePlanRange(arguments)
        let now = Date.now
        let limit = (arguments["limit"] as? Int).map { min(max($0, 1), 120) } ?? 120
        let plans = workoutPlans
            .filter { !$0.exercises.isEmpty }
            .filter { plan in
                guard let date = StrengthWorkoutStore.date(for: plan.dateKey) else { return false }
                return date >= from && date <= to
            }
            .sorted { $0.dateKey < $1.dateKey }
            .prefix(limit)
            .map { plan -> [String: Any] in
                [
                    "date": plan.dateKey,
                    "exercises": plan.exercises.map { exercise -> [String: Any] in
                        var payload: [String: Any] = [
                            "catalog_id": exercise.itemID,
                            "name": exercise.name,
                            "target_muscles": exercise.primaryMuscles,
                            "equipment": exercise.rawEquipment,
                            "performed": (exercise.timer?.savedDurationSeconds ?? 0) > 0
                                || (!exercise.isCardio && exercise.sets.contains { (Int($0.reps) ?? 0) > 0 }),
                            "sets": exercise.sets.enumerated().map { index, set -> [String: Any] in
                                var value: [String: Any] = [
                                    "set": index + 1,
                                    "weight_unit": set.weightUnit ?? workoutPlanWeightUnit.rawValue,
                                ]
                                if !set.weight.isEmpty { value["weight"] = set.weight }
                                if !set.reps.isEmpty {
                                    value["reps"] = Int(set.reps) ?? 0
                                }
                                if !set.rpe.isEmpty {
                                    value["rpe"] = Double(set.rpe) ?? 0
                                    value["rpe_scale"] = (set.rpeScale ?? workoutPreferences?.rpeScale)?.title ?? "Unspecified"
                                }
                                return value
                            },
                        ]
                        if let timer = exercise.timer {
                            let elapsed = timer.elapsedSeconds(at: now)
                            let state = timer.isRunning ? "running" : timer.isSaved ? "saved" : elapsed > 0 ? "paused" : "idle"
                            var timerPayload: [String: Any] = [
                                "state": state,
                                "elapsed_seconds": elapsed,
                            ]
                            if let runningSince = timer.runningSince {
                                timerPayload["running_since"] = Self.isoTimestamp(runningSince)
                            }
                            payload["timer"] = timerPayload
                            payload["intensity"] = StrengthWorkoutBurnEstimator.timerIntensity(for: exercise, defaultRPEScale: workoutPreferences?.rpeScale ?? .strength).title
                            if let duration = timer.savedDurationSeconds, duration.isFinite, duration > 0, !timer.isRunning {
                                payload["duration_seconds"] = duration
                            }
                        }
                        return payload
                    },
                ]
            }
        return jsonString([
            "from": Self.iso(from),
            "to": Self.iso(to),
            "count": plans.count,
            "plans": plans,
        ])
    }

    private func getWorkoutPreferences() -> String {
        guard let preferences = workoutPreferences else {
            return jsonString(["configured": false])
        }
        func strengthValue(_ kg: Double?) -> Any {
            if let kg { return kg }
            return NSNull()
        }
        return jsonString([
            "configured": true,
            "target_muscles": preferences.targetMuscles.sorted(),
            "issues_or_injuries": preferences.issues.map(\.rawValue).sorted(),
            "additional_issues": preferences.additionalIssues,
            "frequency_days_per_week": preferences.frequencyDays,
            "duration_minutes": preferences.duration.rawValue,
            "split": preferences.split.title,
            "custom_split": preferences.customSplit,
            "equipment": preferences.equipment.sorted(),
            "rpe_scale": preferences.rpeScale.title,
            "strength_kg": [
                "bench_press": strengthValue(preferences.strength.benchPressKg),
                "squat": strengthValue(preferences.strength.squatKg),
                "deadlift": strengthValue(preferences.strength.deadliftKg),
                "overhead_press": strengthValue(preferences.strength.overheadPressKg),
            ],
        ])
    }

    private func getExerciseLiftHistory(arguments: [String: Any]) -> String {
        guard let exercise = (arguments["exercise"] as? String)?
            .trimmingCharacters(in: .whitespacesAndNewlines),
            !exercise.isEmpty
        else {
            return jsonError("exercise is required.")
        }
        let catalogID = (arguments["catalog_id"] as? String)?
            .trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        let to = (arguments["to"] as? String).flatMap(Self.parseDate) ?? Date()
        let from = (arguments["from"] as? String).flatMap(Self.parseDate)
            ?? Calendar.current.date(byAdding: .day, value: -365, to: to)
            ?? to
        let limit = (arguments["limit"] as? Int).map { min(max($0, 1), 120) } ?? 60
        let fromKey = Self.iso(Calendar.current.startOfDay(for: from))
        let toKey = Self.iso(Calendar.current.startOfDay(for: to))

        let inRange = Set(effectiveWorkoutSessions.map(\.stableDiaryDateKey))
            .filter { $0 >= fromKey && $0 <= toKey }
            .sorted(by: >)

        var sessions: [[String: Any]] = []
        var bestLoadKg: Double?
        for key in inRange {
            guard sessions.count < limit else { break }
            guard let sets = coachLiftSets(itemID: catalogID, name: exercise, on: key), !sets.isEmpty else { continue }
            let setPayloads = sets.map { set -> [String: Any] in
                var payload: [String: Any] = ["reps": Int(set.reps) ?? 0]
                if !set.weight.isEmpty {
                    payload["weight"] = set.weight
                    let unit = set.weightUnit.isEmpty ? workoutPlanWeightUnit.rawValue : set.weightUnit
                    payload["weight_unit"] = unit
                    if let kg = Self.weightKg(value: set.weight, unit: unit) {
                        payload["weight_kg"] = (kg * 10).rounded() / 10
                        bestLoadKg = max(bestLoadKg ?? kg, kg)
                    }
                }
                return payload
            }
            sessions.append(["date": key, "sets": setPayloads])
        }

        var payload: [String: Any] = [
            "exercise": exercise,
            "from": fromKey,
            "to": toKey,
            "count": sessions.count,
            "sessions": sessions,
        ]
        if !catalogID.isEmpty { payload["catalog_id"] = catalogID }
        if let bestLoadKg { payload["best_load_kg"] = (bestLoadKg * 10).rounded() / 10 }
        if let last = sessions.first { payload["last_session"] = last }
        return jsonString(payload)
    }

    private func searchExerciseLibrary(arguments: [String: Any]) -> String {
        guard let query = (arguments["query"] as? String)?
            .trimmingCharacters(in: .whitespacesAndNewlines),
            !query.isEmpty
        else {
            return jsonError("query is required.")
        }
        let muscle = (arguments["muscle"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        let equipment = (arguments["equipment"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        let limit = (arguments["limit"] as? Int).map { min(max($0, 1), 20) } ?? 10
        let queryMatches = Self.rankedLibraryMatches(exerciseLibrary, query: query)
        // Filter before choosing complete versus partial name matches, so a barbell exercise
        // matching every query word cannot hide the cable one the equipment filter asks for.
        let filtered = Self.rankedLibraryMatches(exerciseLibrary, query: query, muscle: muscle, equipment: equipment)
        let passesFilters = Self.libraryFilter(muscle: muscle, equipment: equipment)
        // A filter word the library never uses ("upper" chest, "bench" equipment) must not hide
        // every match, or Coach would report an exercise that is in the library as missing.
        let filtersIgnored = filtered.isEmpty && !queryMatches.isEmpty
        let matches = filtersIgnored ? queryMatches : filtered
        let exercises = matches.prefix(limit).map { item -> [String: Any] in
            var payload: [String: Any] = [
                "catalog_id": item.id,
                "name": item.name,
                "is_custom": UserExercise.isUserExercise(item.id) || item.id.hasPrefix("custom_activity_"),
                "primary_muscles": item.primaryMuscles,
                "secondary_muscles": item.secondaryMuscles,
            ]
            if let category = Self.libraryMetadata(item.category) { payload["category"] = category }
            if let equipment = Self.libraryMetadata(item.rawEquipment) { payload["equipment"] = equipment }
            return payload
        }
        var payload: [String: Any] = [
            "query": query,
            "count": exercises.count,
            "total_matches": matches.count,
            "exercises": exercises,
        ]
        if !muscle.isEmpty { payload["muscle"] = muscle }
        if !equipment.isEmpty { payload["equipment"] = equipment }
        if filtersIgnored {
            payload["filters_ignored"] = true
            payload["note"] = "No exercise matched both the query and the muscle/equipment filters, so these results ignore the filters."
        } else {
            let excluded = queryMatches.filter { !passesFilters($0) }.count
            if excluded > 0 { payload["excluded_by_filters"] = excluded }
        }
        return jsonString(payload)
    }

    // MARK: - Proposals

    private struct ProposalArgumentError: Error {
        let message: String
    }

    /// Validates a custom exercise against the library's own vocabulary. The new exercise's id
    /// is chosen here so confirming the card can never create it twice.
    private func customExerciseProposal(arguments: [String: Any]) throws -> CoachExerciseProposal {
        let name = Self.proposalText(arguments["name"])
        guard !name.isEmpty else { throw ProposalArgumentError(message: "name is required.") }
        guard name.count <= 80 else { throw ProposalArgumentError(message: "name must be at most 80 characters.") }
        if let existing = exerciseLibrary.first(where: {
            $0.name.trimmingCharacters(in: .whitespacesAndNewlines).caseInsensitiveCompare(name) == .orderedSame
        }) {
            throw ProposalArgumentError(
                message: "\"\(existing.name)\" is already in the exercise library (catalog_id \(existing.id)). Use propose_add_exercise_to_day with that catalog_id instead."
            )
        }
        let category = try Self.libraryChoice(
            arguments["category"], in: Self.libraryCategories, field: "category", names: Self.libraryCategoryNames
        ) ?? "Strength"
        let equipment = try Self.libraryChoice(
            arguments["equipment"], in: Self.libraryEquipment, field: "equipment", names: Self.libraryEquipmentNames
        )
        let primary = try Self.libraryMuscleList(arguments["primary_muscles"], field: "primary_muscles")
        guard !primary.isEmpty else {
            throw ProposalArgumentError(message: "primary_muscles needs at least one of: \(Self.libraryMuscleNames).")
        }
        let secondary = try Self.libraryMuscleList(arguments["secondary_muscles"], field: "secondary_muscles")
            .filter { !primary.contains($0) }
        let instructions = Self.proposalLines(arguments["instructions"])
        guard instructions.joined(separator: "\n").count <= 1_500 else {
            throw ProposalArgumentError(message: "instructions must be at most 1500 characters.")
        }
        return CoachExerciseProposal(
            id: UUID().uuidString,
            kind: .customExercise,
            name: name,
            itemID: UserExercise.newID(),
            category: category,
            equipment: equipment,
            primaryMuscles: primary,
            secondaryMuscles: secondary,
            instructions: instructions
        )
    }

    /// Validates an exercise for one diary day. The entry's id is chosen here so confirming the
    /// card can never append it twice.
    private func addToDayProposal(arguments: [String: Any]) throws -> CoachExerciseProposal {
        let catalogID = Self.proposalText(arguments["catalog_id"])
        guard !catalogID.isEmpty else { throw ProposalArgumentError(message: "catalog_id is required.") }
        guard let item = exerciseLibrary.first(where: { $0.id == catalogID }) else {
            throw ProposalArgumentError(
                message: "Unknown catalog_id \"\(catalogID)\". Call search_exercise_library and use a catalog_id from its results."
            )
        }
        let calendar = Calendar.current
        let today = calendar.startOfDay(for: Date())
        let dateText = Self.proposalText(arguments["date"])
        var day = today
        if !dateText.isEmpty {
            guard let parsed = Self.parseDate(dateText) else {
                throw ProposalArgumentError(message: "date must be an ISO date yyyy-MM-dd.")
            }
            day = calendar.startOfDay(for: parsed)
        }
        guard abs(calendar.dateComponents([.day], from: today, to: day).day ?? 0) <= 365 else {
            throw ProposalArgumentError(message: "date must be within one year of today.")
        }
        let sets = try Self.wholeNumber(arguments["sets"], field: "sets", range: 1...12)
        let reps = try Self.wholeNumber(arguments["reps"], field: "reps", range: 1...999)
        let minutes = try Self.wholeNumber(arguments["duration_minutes"], field: "duration_minutes", range: 1...1_440)
        if day > today, reps != nil || minutes != nil {
            throw ProposalArgumentError(
                message: "reps and duration_minutes record performed work, so they can only be used for today or an earlier date. For a future date, propose sets only."
            )
        }
        if item.category.caseInsensitiveCompare("cardio") == .orderedSame, sets != nil || reps != nil {
            throw ProposalArgumentError(message: "\(item.name) is cardio: use duration_minutes instead of sets or reps.")
        }
        return CoachExerciseProposal(
            id: UUID().uuidString,
            kind: .addToDay,
            name: item.name,
            itemID: item.id,
            category: Self.libraryMetadata(item.category),
            equipment: Self.libraryMetadata(item.rawEquipment),
            primaryMuscles: item.primaryMuscles,
            dateKey: CoachExerciseProposal.dateKey(for: day),
            sets: sets,
            reps: reps,
            durationMinutes: minutes,
            entryID: UUID()
        )
    }

    private func proposalResult(_ proposal: CoachExerciseProposal) -> String {
        var details: [String: Any] = [
            "type": proposal.kind.rawValue,
            "name": proposal.name,
        ]
        switch proposal.kind {
        case .customExercise:
            details["category"] = proposal.category ?? NSNull()
            details["equipment"] = proposal.equipment ?? NSNull()
            details["primary_muscles"] = proposal.primaryMuscles
            details["secondary_muscles"] = proposal.secondaryMuscles
            if !proposal.instructions.isEmpty { details["instructions"] = proposal.instructions }
        case .addToDay:
            details["catalog_id"] = proposal.itemID
            details["date"] = proposal.dateKey ?? NSNull()
            if let sets = proposal.sets { details["sets"] = sets }
            if let reps = proposal.reps { details["reps"] = reps }
            if let minutes = proposal.durationMinutes { details["duration_minutes"] = minutes }
            let diaryKey = proposal.diaryDateKey()
            let alreadyThere = workoutPlans.contains { plan in
                plan.dateKey == diaryKey && plan.exercises.contains { $0.itemID == proposal.itemID }
            }
            if alreadyThere { details["already_in_diary_that_day"] = true }
        }
        return jsonString([
            "proposal_id": proposal.id,
            "status": "pending_user_confirmation",
            "saved": false,
            "proposal": details,
            "note": "Nothing was saved. The user sees this proposal as a card under your reply with Add and Dismiss buttons, and it is saved only if they tap Add. Ask them to review the card; never say it was added or created.",
        ])
    }

    private static func proposalText(_ value: Any?) -> String {
        (value as? String)?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
    }

    /// Lines from a string (one step per line) or an array of strings.
    private static func proposalLines(_ value: Any?) -> [String] {
        let raw: [String]
        if let text = value as? String {
            raw = text.components(separatedBy: .newlines)
        } else if let values = value as? [Any] {
            raw = values.compactMap { $0 as? String }
        } else {
            raw = []
        }
        return raw.map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }.filter { !$0.isEmpty }
    }

    /// One library word ("body only", "olympic weightlifting"), case-insensitive, in the title
    /// case the library shows. Nil when the argument is absent.
    private static func libraryChoice(_ value: Any?, in values: [String], field: String, names: String) throws -> String? {
        let text = proposalText(value)
        guard !text.isEmpty else { return nil }
        guard let match = values.first(where: { $0 == text.lowercased() }) else {
            throw ProposalArgumentError(message: "\(field) must be one of: \(names).")
        }
        return libraryTitle(match)
    }

    /// Muscles from an array (or a comma-separated string), each one of the library's muscles.
    private static func libraryMuscleList(_ value: Any?, field: String) throws -> [String] {
        let raw: [String]
        if let text = value as? String {
            raw = text.components(separatedBy: ",")
        } else if let values = value as? [Any] {
            raw = values.compactMap { $0 as? String }
        } else {
            raw = []
        }
        var muscles: [String] = []
        for entry in raw {
            let key = entry.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
            guard !key.isEmpty else { continue }
            guard let match = libraryMuscles.first(where: { $0 == key }) else {
                throw ProposalArgumentError(message: "Unknown muscle \"\(entry)\" in \(field). Use: \(libraryMuscleNames).")
            }
            let title = libraryTitle(match)
            if !muscles.contains(title) { muscles.append(title) }
        }
        return muscles
    }

    /// Matches how ExerciseLibraryItem titles catalog metadata ("middle back" → "Middle Back").
    private static func libraryTitle(_ value: String) -> String {
        value.split(separator: " ")
            .map { word in
                word.split(separator: "-", omittingEmptySubsequences: false)
                    .map { $0.prefix(1).uppercased() + $0.dropFirst() }
                    .joined(separator: "-")
            }
            .joined(separator: " ")
    }

    /// A whole number within `range`, from a JSON number or numeric string. Nil when absent.
    private static func wholeNumber(_ value: Any?, field: String, range: ClosedRange<Int>) throws -> Int? {
        guard let value, !(value is NSNull) else { return nil }
        var number: Double?
        if let text = value as? String {
            let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
            if trimmed.isEmpty { return nil }
            number = Double(trimmed)
        } else if let numeric = value as? NSNumber, CFGetTypeID(numeric) != CFBooleanGetTypeID() {
            number = numeric.doubleValue
        }
        guard let number, number.isFinite, number == number.rounded(),
              number >= Double(range.lowerBound), number <= Double(range.upperBound)
        else {
            throw ProposalArgumentError(message: "\(field) must be a whole number from \(range.lowerBound) to \(range.upperBound).")
        }
        return Int(number)
    }

    /// Ranks library items for the coach search: whole-name matches first, then items whose
    /// name holds more of the query words, then muscle/equipment/category matches. Words match
    /// by prefix (with a simple plural strip) so "curls" finds "Curl" but "row" skips "Narrow".
    /// When some items match every query word, only those are returned ("incline press" does
    /// not list an incline curl); otherwise items matching any word by name are kept, so a
    /// descriptive query ("hammer strength chest press") still finds the closest exercise.
    /// Mirrors Android CoachTools.rankedLibraryMatches.
    static func rankedLibraryMatches(
        _ library: [ExerciseLibraryItem],
        query: String,
        muscle: String = "",
        equipment: String = ""
    ) -> [ExerciseLibraryItem] {
        let terms = significantWords(searchWords(query))
        guard !terms.isEmpty else { return [] }
        let passesFilters = libraryFilter(muscle: muscle, equipment: equipment)

        let scored = library.compactMap { item -> (item: ExerciseLibraryItem, score: Int, allMatched: Bool)? in
            guard passesFilters(item) else { return nil }
            let muscleWords = searchWords((item.primaryMuscles + item.secondaryMuscles).joined(separator: " "))
            let nameWords = searchWords(item.name)
            let metadataWords = muscleWords + searchWords("\(item.category) \(item.rawEquipment)")
            var nameHits = 0
            var metadataHits = 0
            for term in terms {
                if wordsMatch(term, nameWords) {
                    nameHits += 1
                } else if wordsMatch(term, metadataWords) {
                    metadataHits += 1
                }
            }
            let allMatched = nameHits + metadataHits == terms.count
            guard nameHits > 0 || allMatched else { return nil }
            var score = nameHits * 10 + metadataHits * 3
            if allMatched { score += 20 }
            if nameHits == terms.count { score += 30 }
            if significantWords(nameWords) == terms { score += 100 }
            return (item, score, allMatched)
        }
        let complete = scored.filter(\.allMatched)
        return (complete.isEmpty ? scored : complete)
            .sorted { lhs, rhs in
                if lhs.score != rhs.score { return lhs.score > rhs.score }
                return lhs.item.name.localizedCaseInsensitiveCompare(rhs.item.name) == .orderedAscending
            }
            .map(\.item)
    }

    /// Muscle and equipment filters match the library's own words ("abdominals", "body only",
    /// "e-z curl bar"), so common names the model may use are mapped onto those first. Every
    /// filter word must match; an alias matches when any of its library names does.
    static func libraryFilter(muscle: String, equipment: String) -> (ExerciseLibraryItem) -> Bool {
        let muscleGroups = filterGroups(muscle, aliases: muscleFilterAliases)
        let equipmentGroups = filterGroups(equipment, aliases: equipmentFilterAliases)
        return { item in
            groupsMatch(muscleGroups, searchWords((item.primaryMuscles + item.secondaryMuscles).joined(separator: " ")))
                && groupsMatch(equipmentGroups, searchWords(item.rawEquipment))
        }
    }

    /// Common muscle names mapped to the library's own (libraryMuscleNames). Keys are singular;
    /// plural filters ("legs", "delts") reach them through the same plural strip as search words.
    private static let muscleFilterAliases: [String: [String]] = [
        "ab": ["abdominals"],
        "abs": ["abdominals"],
        "core": ["abdominals"],
        "oblique": ["abdominals"],
        "stomach": ["abdominals"],
        "pec": ["chest"],
        "pectoral": ["chest"],
        "delt": ["shoulders"],
        "deltoid": ["shoulders"],
        "calf": ["calves"],
        "trapezius": ["traps"],
        "latissimus": ["lats"],
        "latissimus dorsi": ["lats"],
        "back": ["back", "lats", "traps"],
        "upper back": ["middle back", "lats", "traps"],
        "leg": ["quadriceps", "hamstrings", "glutes", "calves", "adductors", "abductors"],
        "thigh": ["quadriceps", "hamstrings", "adductors", "abductors"],
        "inner thigh": ["adductors"],
        "outer thigh": ["abductors"],
        "hip": ["glutes", "adductors", "abductors"],
        "arm": ["biceps", "triceps", "forearms"],
    ]

    /// Common equipment names mapped to the library's own (libraryEquipmentNames). Smith
    /// machine exercises are listed as machine, and cable stations are often called machines.
    private static let equipmentFilterAliases: [String: [String]] = [
        "bodyweight": ["body only"],
        "body weight": ["body only"],
        "no equipment": ["body only"],
        "smith": ["machine"],
        "machine": ["machine", "cable"],
        "pulley": ["cable"],
        "ez": ["e-z curl bar"],
        "ezbar": ["e-z curl bar"],
        "resistance": ["bands"],
        "stability": ["exercise ball"],
        "swiss": ["exercise ball"],
        "roller": ["foam roll"],
        "free weight": ["barbell", "dumbbell", "kettlebells"],
    ]

    /// One entry per filter word (or one for a whole aliased phrase like "body weight"), each
    /// holding the alternative phrases that satisfy it as word lists.
    nonisolated private static func filterGroups(_ text: String, aliases: [String: [String]]) -> [[[String]]] {
        let words = searchWords(text)
        guard !words.isEmpty else { return [] }
        if words.count > 1, let phrases = aliasTargets(words.joined(separator: " "), aliases) {
            return [phrases.map(searchWords)]
        }
        return words.map { word in (aliasTargets(word, aliases) ?? [word]).map(searchWords) }
    }

    nonisolated private static func groupsMatch(_ groups: [[[String]]], _ words: [String]) -> Bool {
        groups.allSatisfy { phrases in
            phrases.contains { phrase in phrase.allSatisfy { wordsMatch($0, words) } }
        }
    }

    nonisolated private static func aliasTargets(_ key: String, _ aliases: [String: [String]]) -> [String]? {
        termVariants(key).lazy.compactMap { aliases[$0] }.first
    }

    /// One- and two-letter words ("a", "on", "ez") prefix-match far too many words, so they
    /// only count when the whole query is that short.
    nonisolated private static func significantWords(_ words: [String]) -> [String] {
        let significant = words.filter { $0.count > 2 }
        return significant.isEmpty ? words : significant
    }

    nonisolated private static func searchWords(_ text: String) -> [String] {
        text.lowercased()
            .components(separatedBy: CharacterSet.alphanumerics.inverted)
            .filter { !$0.isEmpty }
    }

    nonisolated private static func wordsMatch(_ term: String, _ words: [String]) -> Bool {
        let variants = termVariants(term)
        return words.contains { word in variants.contains { word.hasPrefix($0) } }
    }

    nonisolated private static func termVariants(_ term: String) -> [String] {
        var variants = [term]
        if term.count > 3, term.hasSuffix("es") { variants.append(String(term.dropLast(2))) }
        if term.count > 3, term.hasSuffix("s") { variants.append(String(term.dropLast())) }
        return variants
    }

    nonisolated private static func libraryMetadata(_ value: String) -> String? {
        let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty || trimmed == "Unspecified" ? nil : trimmed
    }

    private func coachLiftSets(itemID: String, name: String, on dateKey: String) -> [StrengthExerciseLiftSet]? {
        let daySessions = workoutSessions.filter { $0.stableDiaryDateKey == dateKey }
        let preferred = daySessions.filter { $0.caloriesBurned != nil }.max(by: {
            let leftVersion = $0.healthSyncVersion ?? 0
            let rightVersion = $1.healthSyncVersion ?? 0
            if leftVersion == rightVersion { return $0.completedAt < $1.completedAt }
            return leftVersion < rightVersion
        }) ?? daySessions.max(by: { $0.completedAt < $1.completedAt })
        guard let session = preferred,
              let exercise = session.exercises.first(where: {
                  StrengthExerciseLiftHistory.matches(
                      itemID: itemID,
                      name: name,
                      candidateItemID: $0.itemID,
                      candidateName: $0.name
                  )
              })
        else { return nil }
        return StrengthExerciseLiftHistory.performedSets(from: exercise.sets)
    }

    private func getTrainingSummary(arguments: [String: Any]) -> String {
        let (from, to) = parseRange(arguments)
        let sessions = effectiveWorkoutSessions.filter {
            $0.calendarDiaryDate >= from && $0.calendarDiaryDate <= to
        }
        struct ExerciseAggregate {
            var sessionIDs: Set<UUID> = []
            var durationSeconds = 0.0
            var sets = 0
            var reps = 0
            var volumeKg = 0.0
            var bestLoadKg: Double?
            var rpeByScale: [String: RPEAggregate] = [:]
        }
        struct RPEAggregate {
            var total = 0.0
            var count = 0
        }
        var aggregates: [String: ExerciseAggregate] = [:]
        for session in sessions {
            for exercise in session.exercises {
                var aggregate = aggregates[exercise.name] ?? ExerciseAggregate()
                aggregate.sessionIDs.insert(session.id)
                if let duration = exercise.durationSeconds, duration.isFinite, duration > 0 {
                    aggregate.durationSeconds += duration
                }
                for set in exercise.sets where set.isPerformed {
                    aggregate.sets += 1
                    let reps = Int(set.reps) ?? 0
                    aggregate.reps += reps
                    if let kg = Self.weightKg(value: set.weight, unit: set.weightUnit) {
                        aggregate.volumeKg += kg * Double(reps)
                        aggregate.bestLoadKg = max(aggregate.bestLoadKg ?? kg, kg)
                    }
                    if let rpe = Double(set.rpe) {
                        let scale = set.rpeScale?.title ?? "Unspecified"
                        var rpeAggregate = aggregate.rpeByScale[scale] ?? RPEAggregate()
                        rpeAggregate.total += rpe
                        rpeAggregate.count += 1
                        aggregate.rpeByScale[scale] = rpeAggregate
                    }
                }
                aggregates[exercise.name] = aggregate
            }
        }
        let exercisePayloads = aggregates.sorted { $0.key < $1.key }.map { name, value -> [String: Any] in
            var payload: [String: Any] = [
                "name": name,
                "sessions": value.sessionIDs.count,
                "duration_seconds": value.durationSeconds,
                "sets": value.sets,
                "reps": value.reps,
                "external_load_volume_kg": (value.volumeKg * 10).rounded() / 10,
            ]
            if let best = value.bestLoadKg { payload["best_load_kg"] = (best * 10).rounded() / 10 }
            if !value.rpeByScale.isEmpty {
                let averages = value.rpeByScale.mapValues { aggregate in
                    (aggregate.total / Double(aggregate.count) * 10).rounded() / 10
                }
                payload["average_rpe_by_scale"] = averages
                if averages.count == 1, let only = averages.first {
                    payload["average_rpe"] = only.value
                    payload["rpe_scale"] = only.key
                }
            }
            return payload
        }
        return jsonString([
            "from": Self.iso(from),
            "to": Self.iso(to),
            "sessions": sessions.count,
            "sets": sessions.reduce(0) { $0 + $1.performedSetCount },
            "reps": sessions.reduce(0) { $0 + $1.repCount },
            "calories_burned": sessions.reduce(0) { $0 + ($1.caloriesBurned ?? 0) },
            "minutes": sessions.reduce(0) { $0 + $1.durationMinutes },
            "timed_exercise_seconds": aggregates.values.reduce(0) { $0 + $1.durationSeconds },
            "by_exercise": exercisePayloads,
        ])
    }

    private func workoutSessionPayload(_ session: StrengthWorkoutSession) -> [String: Any] {
        var payload: [String: Any] = [
            "id": session.id.uuidString,
            "date": session.stableDiaryDateKey,
            "started_at": Self.isoTimestamp(session.startedAt),
            "completed_at": Self.isoTimestamp(session.completedAt),
            "duration_seconds": session.durationSeconds,
            "exercises": session.exercises.map { exercise -> [String: Any] in
                var exercisePayload: [String: Any] = [
                    "catalog_id": exercise.itemID,
                    "name": exercise.name,
                    "target_muscles": exercise.targetMuscles,
                    "equipment": exercise.equipment,
                    "performed": (exercise.durationSeconds ?? 0) > 0 || exercise.sets.contains(where: \.isPerformed),
                    "sets": exercise.sets.map { set -> [String: Any] in
                        var payload: [String: Any] = [
                            "set": set.setNumber,
                            "performed": set.isPerformed,
                            "weight_unit": set.weightUnit,
                        ]
                        if !set.weight.isEmpty {
                            payload["weight"] = Double(set.weight) ?? 0
                        }
                        if let kg = Self.weightKg(value: set.weight, unit: set.weightUnit) { payload["weight_kg"] = kg }
                        if !set.reps.isEmpty { payload["reps"] = Int(set.reps) ?? 0 }
                        if !set.rpe.isEmpty {
                            payload["rpe"] = Double(set.rpe) ?? 0
                            payload["rpe_scale"] = set.rpeScale?.title ?? "Unspecified"
                        }
                        return payload
                    },
                ]
                if let duration = exercise.durationSeconds, duration.isFinite, duration > 0 {
                    exercisePayload["duration_seconds"] = duration
                    if let intensity = exercise.intensity { exercisePayload["intensity"] = intensity.title }
                }
                return exercisePayload
            },
        ]
        if let caloriesBurned = session.caloriesBurned {
            payload["calories_burned"] = caloriesBurned
        }
        return payload
    }

    // MARK: - Helpers

    /// Parse a `from` / `to` date range from the LLM's tool args. Defaults to
    /// last 30 days if `from` is missing, and to .now if `to` is missing —
    /// generous defaults mean a malformed call still returns useful data
    /// rather than failing the whole turn.
    private func parseRange(_ args: [String: Any]) -> (Date, Date) {
        let to = (args["to"] as? String).flatMap(Self.parseDate) ?? Date()
        let from = (args["from"] as? String).flatMap(Self.parseDate)
            ?? Calendar.current.date(byAdding: .day, value: -30, to: to)
            ?? to
        // Inclusive end-of-day so "to: 2025-04-26" includes everything that day.
        let endOfDay = Calendar.current.date(bySettingHour: 23, minute: 59, second: 59, of: to) ?? to
        let startOfDay = Calendar.current.startOfDay(for: from)
        return (startOfDay, endOfDay)
    }

    /// Plans default to a bounded window around today so a simple training
    /// question never injects years of stale plans or omits upcoming work.
    private func parsePlanRange(_ args: [String: Any]) -> (Date, Date) {
        let calendar = Calendar.current
        let today = calendar.startOfDay(for: .now)
        let from = (args["from"] as? String).flatMap(Self.parseDate)
            ?? calendar.date(byAdding: .day, value: -14, to: today)
            ?? today
        let to = (args["to"] as? String).flatMap(Self.parseDate)
            ?? calendar.date(byAdding: .day, value: 90, to: today)
            ?? today
        let startOfDay = calendar.startOfDay(for: from)
        let endOfDay = calendar.date(bySettingHour: 23, minute: 59, second: 59, of: to) ?? to
        return (startOfDay, endOfDay)
    }

    /// `nonisolated` on the helpers below so they can be called from any
    /// context without tripping Swift's main-actor isolation warnings under
    /// the project-wide SWIFT_DEFAULT_ACTOR_ISOLATION = MainActor setting.
    /// DateFormatter is Sendable as of recent SDKs, so a plain `private static
    /// let` is fine — no `nonisolated(unsafe)` needed.
    nonisolated private static let isoFormatter: DateFormatter = {
        let f = DateFormatter()
        f.dateFormat = "yyyy-MM-dd"
        f.locale = Locale(identifier: "en_US_POSIX")
        f.timeZone = TimeZone.current
        return f
    }()

    nonisolated private static func iso(_ date: Date) -> String { isoFormatter.string(from: date) }
    nonisolated private static func parseDate(_ s: String) -> Date? { isoFormatter.date(from: s) }

    nonisolated private static func isoTimestamp(_ date: Date) -> String {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return formatter.string(from: date)
    }

    nonisolated private static func weightKg(value: String, unit: String) -> Double? {
        guard let value = Double(value.replacingOccurrences(of: ",", with: ".")), value.isFinite else { return nil }
        return unit == WeightUnit.lbs.rawValue ? value / 2.20462 : value
    }

    private func jsonString(_ obj: Any) -> String {
        guard let data = try? JSONSerialization.data(withJSONObject: obj, options: [.sortedKeys]),
              let s = String(data: data, encoding: .utf8) else {
            return "{}"
        }
        return s
    }

    private func jsonError(_ message: String) -> String {
        jsonString(["error": message])
    }
}
