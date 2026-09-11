import Foundation
import Testing
@testable import calorietracker

@MainActor
struct CoachWorkoutToolsTests {
    @Test func savedCardioCountsAsPerformedAndKeepsDurationWithoutSetsOrReps() throws {
        let date = WorkoutTestFixture.date(2026, 7, 20)
        var cardio = StrengthPlannedExercise(item: WorkoutTestFixture.exercise(id: "Running_Treadmill", name: "Running, Treadmill"))
        cardio.category = "cardio"
        cardio.sets = []
        cardio.timer = StrengthExerciseTimer(accumulatedSeconds: 600.5, savedDurationSeconds: 600.5, intensity: .vigorous)
        let completed = StrengthCompletedExercise(
            itemID: cardio.itemID, name: cardio.name, targetMuscles: cardio.primaryMuscles,
            equipment: cardio.rawEquipment, sets: [], durationSeconds: 600.5, intensity: .vigorous
        )
        let session = StrengthWorkoutSession(
            diaryDate: date, startedAt: date, completedAt: date,
            durationSeconds: 0, exercises: [completed], caloriesBurned: 120
        )
        let tools = CoachTools(
            weights: [], bodyFats: [], foods: [], workoutSessions: [session],
            workoutPlans: [StrengthWorkoutDayPlan(dateKey: "2026-07-20", exercises: [cardio])],
            workoutAccessEnabled: true
        )
        let range: [String: Any] = ["from": "2026-07-20", "to": "2026-07-20"]
        let plans = try WorkoutCoachFixture.jsonObject(tools.execute(name: "get_workout_plans", arguments: range))
        let planPayloads = try #require(plans["plans"] as? [[String: Any]])
        let planned = try #require(planPayloads[0]["exercises"] as? [[String: Any]])
        #expect(planned[0]["performed"] as? Bool == true)
        #expect(planned[0]["duration_seconds"] as? Double == 600.5)
        let timer = try #require(planned[0]["timer"] as? [String: Any])
        #expect(timer["state"] as? String == "saved")
        let history = try WorkoutCoachFixture.jsonObject(tools.execute(name: "get_workout_history", arguments: range))
        let workouts = try #require(history["workouts"] as? [[String: Any]])
        let exercises = try #require(workouts[0]["exercises"] as? [[String: Any]])
        #expect(exercises[0]["performed"] as? Bool == true)
        #expect(exercises[0]["duration_seconds"] as? Double == 600.5)
        #expect(exercises[0]["intensity"] as? String == "Vigorous")
        let sets = try #require(exercises[0]["sets"] as? [[String: Any]])
        #expect(sets.isEmpty)
        let summary = try WorkoutCoachFixture.jsonObject(tools.execute(name: "get_training_summary", arguments: range))
        #expect(summary["sets"] as? Int == 0)
        #expect(summary["reps"] as? Int == 0)
        #expect(summary["timed_exercise_seconds"] as? Double == 600.5)
        let byExercise = try #require(summary["by_exercise"] as? [[String: Any]])
        #expect(byExercise[0]["duration_seconds"] as? Double == 600.5)
    }

    @Test func runningAndPausedPlanTimersRemainUnsaved() throws {
        let now = Date.now
        var running = StrengthPlannedExercise(item: WorkoutTestFixture.exercise(id: "run", name: "Running"))
        running.sets = []
        running.timer = StrengthExerciseTimer(accumulatedSeconds: 30, runningSince: now.addingTimeInterval(-90))
        var paused = running
        paused.timer = StrengthExerciseTimer(accumulatedSeconds: 45)
        let tools = CoachTools(
            weights: [], bodyFats: [], foods: [],
            workoutPlans: [StrengthWorkoutDayPlan(dateKey: "2026-07-20", exercises: [running, paused])],
            workoutAccessEnabled: true
        )
        let plans = try WorkoutCoachFixture.jsonObject(tools.execute(
            name: "get_workout_plans", arguments: ["from": "2026-07-20", "to": "2026-07-20"]
        ))
        let planPayloads = try #require(plans["plans"] as? [[String: Any]])
        let exercises = try #require(planPayloads[0]["exercises"] as? [[String: Any]])
        let runningTimer = try #require(exercises[0]["timer"] as? [String: Any])
        let pausedTimer = try #require(exercises[1]["timer"] as? [String: Any])
        #expect(runningTimer["state"] as? String == "running")
        #expect((runningTimer["elapsed_seconds"] as? Double ?? 0) >= 120)
        #expect(runningTimer["running_since"] as? String != nil)
        #expect(pausedTimer["state"] as? String == "paused")
        #expect(pausedTimer["elapsed_seconds"] as? Double == 45)
        for exercise in exercises {
            #expect(exercise["duration_seconds"] == nil)
            #expect(exercise["performed"] as? Bool == false)
        }
    }

    @Test func disabledWorkoutAccessDoesNotAdvertiseOrReturnWorkoutData() throws {
        let session = WorkoutCoachFixture.session(
            date: WorkoutTestFixture.date(2026, 7, 10),
            exercise: "Private Bench Session",
            sets: [WorkoutCoachFixture.set(number: 1, weight: "100", reps: "5", rpe: "8")]
        )
        let tools = CoachTools(
            weights: [],
            bodyFats: [],
            foods: [],
            workoutSessions: [session],
            workoutPlans: [],
            workoutPreferences: StrengthWorkoutPreferences(),
            workoutAccessEnabled: false
        )

        #expect(tools.availableToolNames == CoachTools.nutritionToolNames)
        #expect(!tools.availableToolNames.contains("get_workout_history"))

        let summary = try WorkoutCoachFixture.jsonObject(tools.execute(name: "get_data_summary", arguments: [:]))
        #expect(summary["workouts"] == nil)
        #expect(summary["workout_plans"] == nil)

        let blocked = try WorkoutCoachFixture.jsonObject(
            tools.execute(
                name: "get_workout_history",
                arguments: ["from": "2026-07-01", "to": "2026-07-31"]
            )
        )
        let error = try #require(blocked["error"] as? String)
        #expect(error == "Workout access is disabled.")
        #expect(!tools.execute(name: "get_workout_history", arguments: [:]).contains("Private Bench Session"))
    }

    @Test func enabledSummaryAndHistoryExposeOnlyRequestedWorkoutRange() throws {
        let firstDate = WorkoutTestFixture.date(2026, 7, 4)
        let secondDate = WorkoutTestFixture.date(2026, 7, 18)
        let first = WorkoutCoachFixture.session(
            date: firstDate,
            exercise: "Bench Press",
            sets: [WorkoutCoachFixture.set(number: 1, weight: "100", reps: "5", rpe: "8")]
        )
        let second = WorkoutCoachFixture.session(
            date: secondDate,
            caloriesBurned: 345,
            exercise: "Back Squat",
            sets: [WorkoutCoachFixture.set(number: 1, weight: "120", reps: "3", rpe: "9")]
        )
        let plan = StrengthWorkoutDayPlan(
            dateKey: "2026-07-20",
            exercises: [StrengthPlannedExercise(item: WorkoutTestFixture.exercise(id: "row", name: "Barbell Row"))]
        )
        let tools = CoachTools(
            weights: [],
            bodyFats: [],
            foods: [],
            workoutSessions: [second, first],
            workoutPlans: [plan],
            workoutAccessEnabled: true
        )

        #expect(Set(CoachTools.workoutToolNames).isSubset(of: Set(tools.availableToolNames)))
        let summary = try WorkoutCoachFixture.jsonObject(tools.execute(name: "get_data_summary", arguments: [:]))
        let workoutSummary = try #require(summary["workouts"] as? [String: Any])
        let planSummary = try #require(summary["workout_plans"] as? [String: Any])
        #expect(workoutSummary["count"] as? Int == 2)
        #expect(workoutSummary["first_date"] as? String == "2026-07-04")
        #expect(workoutSummary["last_date"] as? String == "2026-07-18")
        #expect(planSummary["count"] as? Int == 1)

        let history = try WorkoutCoachFixture.jsonObject(
            tools.execute(
                name: "get_workout_history",
                arguments: ["from": "2026-07-18", "to": "2026-07-18"]
            )
        )
        let workouts = try #require(history["workouts"] as? [[String: Any]])
        #expect(history["count"] as? Int == 1)
        #expect(workouts.count == 1)
        #expect(workouts[0]["date"] as? String == "2026-07-18")
        #expect(workouts[0]["calories_burned"] as? Int == 345)
        let exercises = try #require(workouts[0]["exercises"] as? [[String: Any]])
        #expect(exercises[0]["name"] as? String == "Back Squat")
        let sets = try #require(exercises[0]["sets"] as? [[String: Any]])
        #expect(sets[0]["performed"] as? Bool == true)
        #expect(sets[0]["weight"] as? Double == 120)
        #expect(sets[0]["weight_kg"] as? Double == 120)
        #expect(sets[0]["reps"] as? Int == 3)
        #expect(sets[0]["rpe"] as? Double == 9)
    }

    @Test func planAndPreferencePayloadsPreserveWorkoutConfiguration() throws {
        let item = WorkoutTestFixture.exercise(
            id: "incline-bench",
            name: "Incline Bench Press",
            equipment: "dumbbell",
            muscles: ["upper chest"]
        )
        var plannedExercise = StrengthPlannedExercise(item: item)
        plannedExercise.sets = [
            StrengthPlannedSet(
                weight: "32.5",
                weightUnit: WeightUnit.kg.rawValue,
                reps: "10",
                rpe: "8.5",
                rpeScale: .cr10
            ),
            StrengthPlannedSet(),
        ]
        let plan = StrengthWorkoutDayPlan(dateKey: "2026-07-20", exercises: [plannedExercise])
        var preferences = StrengthWorkoutPreferences()
        preferences.targetMuscles = ["Chest", "Shoulders"]
        preferences.issues = [.shoulder, .wrist]
        preferences.additionalIssues = "No overhead lockout"
        preferences.frequencyDays = 4
        preferences.duration = .seventyFive
        preferences.split = .upperLower
        preferences.equipment = ["Bench", "Dumbbells"]
        preferences.rpeScale = .cr10
        preferences.strength = StrengthWorkoutNumbers(
            benchPressKg: 110,
            squatKg: 150,
            deadliftKg: nil,
            overheadPressKg: 65
        )
        let tools = CoachTools(
            weights: [],
            bodyFats: [],
            foods: [],
            workoutPlans: [StrengthWorkoutDayPlan(dateKey: "2026-07-19"), plan],
            workoutPreferences: preferences,
            workoutAccessEnabled: true
        )

        let plans = try WorkoutCoachFixture.jsonObject(
            tools.execute(
                name: "get_workout_plans",
                arguments: ["from": "2026-07-20", "to": "2026-07-20"]
            )
        )
        let planPayloads = try #require(plans["plans"] as? [[String: Any]])
        #expect(plans["count"] as? Int == 1)
        #expect(planPayloads[0]["date"] as? String == "2026-07-20")
        let exercises = try #require(planPayloads[0]["exercises"] as? [[String: Any]])
        #expect(exercises[0]["catalog_id"] as? String == item.id)
        #expect(exercises[0]["name"] as? String == item.name)
        #expect(exercises[0]["target_muscles"] as? [String] == item.primaryMuscles)
        let sets = try #require(exercises[0]["sets"] as? [[String: Any]])
        #expect(sets.count == 2)
        #expect(sets[0]["weight"] as? String == "32.5")
        #expect(sets[0]["weight_unit"] as? String == WeightUnit.kg.rawValue)
        #expect(sets[0]["reps"] as? Int == 10)
        #expect(sets[0]["rpe"] as? Double == 8.5)
        #expect(sets[0]["rpe_scale"] as? String == StrengthWorkoutRPEScale.cr10.title)
        #expect(sets[1]["weight"] == nil)

        let preferencesPayload = try WorkoutCoachFixture.jsonObject(
            tools.execute(name: "get_workout_preferences", arguments: [:])
        )
        #expect(preferencesPayload["configured"] as? Bool == true)
        #expect(preferencesPayload["target_muscles"] as? [String] == ["Chest", "Shoulders"])
        #expect(preferencesPayload["issues_or_injuries"] as? [String] == ["Shoulder", "Wrist"])
        #expect(preferencesPayload["frequency_days_per_week"] as? Int == 4)
        #expect(preferencesPayload["duration_minutes"] as? Int == 75)
        #expect(preferencesPayload["split"] as? String == StrengthWorkoutSplit.upperLower.title)
        #expect(preferencesPayload["rpe_scale"] as? String == StrengthWorkoutRPEScale.cr10.title)
        let strength = try #require(preferencesPayload["strength_kg"] as? [String: Any])
        #expect(strength["bench_press"] as? Double == 110)
        #expect(strength["squat"] as? Double == 150)
        #expect(strength["deadlift"] is NSNull)
        #expect(strength["overhead_press"] as? Double == 65)
    }

    @Test func trainingSummaryCalculatesPerformedSetsRepsVolumeBestLoadAndAverageRPE() throws {
        let first = WorkoutCoachFixture.session(
            date: WorkoutTestFixture.date(2026, 7, 8),
            durationSeconds: 60,
            caloriesBurned: 120,
            exercise: "Bench Press",
            sets: [
                WorkoutCoachFixture.set(number: 1, weight: "100", unit: .kg, reps: "5", rpe: "8"),
                WorkoutCoachFixture.set(number: 2, weight: "200", unit: .kg, reps: "", rpe: "10"),
            ]
        )
        let second = WorkoutCoachFixture.session(
            date: WorkoutTestFixture.date(2026, 7, 9),
            durationSeconds: 61,
            caloriesBurned: 80,
            exercise: "Bench Press",
            sets: [WorkoutCoachFixture.set(number: 1, weight: "220.462", unit: .lbs, reps: "3", rpe: "9")]
        )
        let outsideRange = WorkoutCoachFixture.session(
            date: WorkoutTestFixture.date(2026, 6, 1),
            durationSeconds: 900,
            exercise: "Bench Press",
            sets: [WorkoutCoachFixture.set(number: 1, weight: "300", unit: .kg, reps: "10", rpe: "10")]
        )
        let tools = CoachTools(
            weights: [],
            bodyFats: [],
            foods: [],
            workoutSessions: [outsideRange, first, second],
            workoutAccessEnabled: true
        )

        let summary = try WorkoutCoachFixture.jsonObject(
            tools.execute(
                name: "get_training_summary",
                arguments: ["from": "2026-07-08", "to": "2026-07-09"]
            )
        )
        #expect(summary["sessions"] as? Int == 2)
        #expect(summary["sets"] as? Int == 2)
        #expect(summary["reps"] as? Int == 8)
        #expect(summary["calories_burned"] as? Int == 200)
        #expect(summary["minutes"] as? Int == 3)

        let exercises = try #require(summary["by_exercise"] as? [[String: Any]])
        #expect(exercises.count == 1)
        let bench = exercises[0]
        #expect(bench["name"] as? String == "Bench Press")
        #expect(bench["sessions"] as? Int == 2)
        #expect(bench["sets"] as? Int == 2)
        #expect(bench["reps"] as? Int == 8)
        #expect(bench["external_load_volume_kg"] as? Double == 800)
        #expect(bench["best_load_kg"] as? Double == 100)
        #expect(bench["average_rpe"] as? Double == 8.5)
        #expect(bench["rpe_scale"] as? String == StrengthWorkoutRPEScale.strength.title)
    }

    @Test func trainingSummaryKeepsDifferentRPEScalesSeparate() throws {
        let date = WorkoutTestFixture.date(2026, 7, 9)
        let strength = WorkoutCoachFixture.session(
            date: date,
            exercise: "Back Squat",
            sets: [WorkoutCoachFixture.set(number: 1, weight: "100", reps: "5", rpe: "8", rpeScale: .strength)]
        )
        let borg = WorkoutCoachFixture.session(
            date: date,
            exercise: "Back Squat",
            sets: [WorkoutCoachFixture.set(number: 1, weight: "90", reps: "8", rpe: "16", rpeScale: .borg)]
        )
        let tools = CoachTools(
            weights: [],
            bodyFats: [],
            foods: [],
            workoutSessions: [strength, borg],
            workoutAccessEnabled: true
        )

        let summary = try WorkoutCoachFixture.jsonObject(
            tools.execute(
                name: "get_training_summary",
                arguments: ["from": "2026-07-09", "to": "2026-07-09"]
            )
        )
        let exercises = try #require(summary["by_exercise"] as? [[String: Any]])
        let squat = try #require(exercises.first)
        let averages = try #require(squat["average_rpe_by_scale"] as? [String: Any])
        #expect(averages[StrengthWorkoutRPEScale.strength.title] as? Double == 8)
        #expect(averages[StrengthWorkoutRPEScale.borg.title] as? Double == 16)
        #expect(squat["average_rpe"] == nil)
    }

    @Test func calculatedDailyBurnSupersedesTimerEraSnapshotForCoach() throws {
        let date = WorkoutTestFixture.date(2026, 7, 12)
        let legacy = WorkoutCoachFixture.session(
            date: date,
            exercise: "Bench Press",
            sets: [WorkoutCoachFixture.set(number: 1, weight: "80", reps: "5", rpe: "7")]
        )
        let calculated = WorkoutCoachFixture.session(
            date: date.addingTimeInterval(60),
            caloriesBurned: 210,
            exercise: "Bench Press",
            sets: [WorkoutCoachFixture.set(number: 1, weight: "85", reps: "8", rpe: "8")]
        )
        let tools = CoachTools(
            weights: [],
            bodyFats: [],
            foods: [],
            workoutSessions: [legacy, calculated],
            workoutAccessEnabled: true
        )

        let summary = try WorkoutCoachFixture.jsonObject(
            tools.execute(
                name: "get_training_summary",
                arguments: ["from": "2026-07-12", "to": "2026-07-12"]
            )
        )
        #expect(summary["sessions"] as? Int == 1)
        #expect(summary["sets"] as? Int == 1)
        #expect(summary["reps"] as? Int == 8)
        #expect(summary["calories_burned"] as? Int == 210)
    }

    @Test func workoutToolSchemasRequireDatesOnlyForRangeQueries() throws {
        let noArgumentSchemaNames = ["get_data_summary", "get_workout_plans", "get_workout_preferences"]
        for name in noArgumentSchemaNames {
            let schema = CoachTools.parameterSchema(for: name)
            #expect(schema["required"] == nil)
        }

        let planProperties = try #require(
            CoachTools.parameterSchema(for: "get_workout_plans")["properties"] as? [String: Any]
        )
        #expect(planProperties["from"] != nil)
        #expect(planProperties["to"] != nil)

        for name in ["get_workout_history", "get_training_summary"] {
            let schema = CoachTools.parameterSchema(for: name)
            #expect(schema["required"] as? [String] == ["from", "to"])
        }

        let liftSchema = CoachTools.parameterSchema(for: "get_exercise_lift_history")
        #expect(liftSchema["required"] as? [String] == ["exercise"])
    }

    @Test func exerciseLiftHistoryReturnsRecentSetsForOneLift() throws {
        let bench = WorkoutTestFixture.exercise(id: "bench", name: "Bench Press")
        let yesterday = WorkoutTestFixture.date(2026, 7, 19)
        let today = WorkoutTestFixture.date(2026, 7, 20)
        let store = WorkoutTestFixture().makeStore()
        store.toggleExercise(bench, on: yesterday)
        var planned = try #require(store.exercises(for: yesterday).first)
        var firstSet = try #require(planned.sets.first)
        store.updateSet(
            exerciseID: planned.id,
            setID: firstSet.id,
            on: yesterday,
            weight: "60",
            weightUnit: .kg,
            reps: "8"
        )
        store.toggleExercise(bench, on: today)

        let tools = CoachTools(
            weights: [],
            bodyFats: [],
            foods: [],
            workoutSessions: store.completedSessions,
            workoutPlans: Array(store.dayPlans.values),
            workoutPreferences: store.preferences,
            workoutPlanWeightUnit: .kg,
            workoutAccessEnabled: true
        )
        let payload = try WorkoutCoachFixture.jsonObject(
            tools.execute(
                name: "get_exercise_lift_history",
                arguments: ["exercise": "Bench Press", "to": "2026-07-20"]
            )
        )
        let sessions = try #require(payload["sessions"] as? [[String: Any]])
        #expect(payload["count"] as? Int == 1)
        #expect(sessions.first?["date"] as? String == "2026-07-19")
        let sets = try #require(sessions.first?["sets"] as? [[String: Any]])
        #expect(sets.first?["weight"] as? String == "60")
        #expect(sets.first?["reps"] as? Int == 8)
        let last = try #require(payload["last_session"] as? [String: Any])
        #expect(last["date"] as? String == "2026-07-19")
    }
}

@MainActor
private enum WorkoutCoachFixture {
    static func set(
        number: Int,
        weight: String,
        unit: WeightUnit = .kg,
        reps: String,
        rpe: String,
        rpeScale: StrengthWorkoutRPEScale = .strength
    ) -> StrengthCompletedSet {
        StrengthCompletedSet(
            setNumber: number,
            weight: weight,
            weightUnit: unit.rawValue,
            reps: reps,
            rpe: rpe,
            rpeScale: rpeScale
        )
    }

    static func session(
        date: Date,
        durationSeconds: Int = 600,
        caloriesBurned: Int? = nil,
        exercise: String,
        sets: [StrengthCompletedSet]
    ) -> StrengthWorkoutSession {
        StrengthWorkoutSession(
            diaryDate: Calendar.current.startOfDay(for: date),
            startedAt: date.addingTimeInterval(-Double(durationSeconds)),
            completedAt: date,
            durationSeconds: durationSeconds,
            exercises: [
                StrengthCompletedExercise(
                    itemID: exercise.lowercased().replacingOccurrences(of: " ", with: "-"),
                    name: exercise,
                    targetMuscles: ["Chest"],
                    equipment: "Barbell",
                    sets: sets
                )
            ],
            caloriesBurned: caloriesBurned
        )
    }

    static func jsonObject(_ value: String) throws -> [String: Any] {
        let data = try #require(value.data(using: .utf8))
        return try #require(JSONSerialization.jsonObject(with: data) as? [String: Any])
    }
}
