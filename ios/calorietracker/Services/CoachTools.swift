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
    ]

    /// The provider schemas are built from this instance value, preventing any
    /// workout tool from being disclosed when the user has the diary disabled.
    var availableToolNames: [String] {
        Self.nutritionToolNames + (workoutAccessEnabled ? Self.workoutToolNames : [])
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
        "get_food_entries": "Individual logged food items (name + calories + macros) between two dates. Use when the user asks about specific meals, what they ate on a given date, or wants macro breakdowns rather than just kcal totals.",
        "get_fasting_history": "Fetch explicitly tracked fasting sessions between two dates, including start/end timestamps, duration, goal, and whether the goal was reached. Never infer fasting from missing food logs.",
        "get_workout_history": "Fetch completed workouts between two dates, including calculated calorie burn, saved exercise durations and intensity, and logged sets with weight, reps, and RPE. A timed exercise can be performed without any reps or sets. For one specific lift across many dates, prefer get_exercise_lift_history.",
        "get_workout_plans": "Fetch dated workout diary plans, set targets, saved exercise durations, and current timer state. Running or paused timer time is unsaved. Optional ISO from/to dates narrow the result; without them it returns recent and upcoming plans around today.",
        "get_workout_preferences": "Fetch workout-only preferences such as target muscles, injuries or issues, equipment, schedule, split, RPE scale, and strength numbers.",
        "get_training_summary": "Summarize workouts between two dates: calculated calorie burn plus sessions, saved exercise duration, sets, reps, volume, best load, and average RPE by exercise. Timed cardio does not require reps or sets.",
        "get_exercise_lift_history": "Fetch per-exercise lift history from the local workout diary: dated sessions with logged weight × reps sets, the most recent session, and best load in kg. Use for questions like \"what did I bench last?\" or trends for one lift. Optional catalog_id narrows matching; otherwise exercise name is used.",
    ]

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
        if Self.workoutToolNames.contains(name), !workoutAccessEnabled {
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
                "date": Self.iso(entry.timestamp),
                "name": entry.name,
                "kcal": entry.calories,
                "protein_g": entry.protein,
                "carbs_g": entry.carbs,
                "fat_g": entry.fat,
                "meal_type": entry.mealType.rawValue,
                "source": entry.source.rawValue,
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

        var dateKeys = Set(workoutPlans.map(\.dateKey))
        dateKeys.formUnion(effectiveWorkoutSessions.map(\.stableDiaryDateKey))
        let inRange = dateKeys.filter { $0 >= fromKey && $0 <= toKey }.sorted(by: >)

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

    private func coachLiftSets(itemID: String, name: String, on dateKey: String) -> [StrengthExerciseLiftSet]? {
        if let plan = workoutPlans.first(where: { $0.dateKey == dateKey }),
           let exercise = plan.exercises.first(where: {
               !$0.isCardio && StrengthExerciseLiftHistory.matches(
                   itemID: itemID,
                   name: name,
                   candidateItemID: $0.itemID,
                   candidateName: $0.name
               )
           }) {
            let sets = StrengthExerciseLiftHistory.performedSets(from: exercise.sets)
            if !sets.isEmpty { return sets }
        }

        let daySessions = workoutSessions.filter { $0.stableDiaryDateKey == dateKey }
        let preferred = daySessions.first(where: { $0.caloriesBurned != nil })
            ?? daySessions.max(by: { $0.completedAt < $1.completedAt })
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
