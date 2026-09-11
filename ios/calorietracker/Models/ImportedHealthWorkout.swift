import Foundation
import HealthKit

/// A read-only workout session imported from Apple Health (Apple Watch, iPhone,
/// or third-party apps). Kept separate from Fud AI's strength diary and calculated
/// burn estimates so Energy Burn / goal math is never double-counted.
struct ImportedHealthWorkout: Identifiable, Codable, Equatable, Hashable {
    /// Stable HealthKit sample UUID used for deduplication.
    var id: UUID
    var activityTypeRaw: UInt
    var activityTitle: String
    var startedAt: Date
    var endedAt: Date
    var durationSeconds: Int
    var totalEnergyBurned: Int?
    var sourceName: String?
    var deviceName: String?
    var diaryDateKey: String

    var durationMinutes: Int { max(1, Int(ceil(Double(durationSeconds) / 60))) }

    var calendarDiaryDate: Date {
        StrengthWorkoutDate.date(for: diaryDateKey) ?? Calendar.current.startOfDay(for: startedAt)
    }

    var sourceSummary: String? {
        switch (sourceName, deviceName) {
        case let (source?, device?) where source != device:
            return "\(source) · \(device)"
        case let (source?, nil):
            return source
        case let (nil, device?):
            return device
        default:
            return nil
        }
    }

    static func from(_ workout: HKWorkout, calendar: Calendar = .current) -> ImportedHealthWorkout? {
        let duration = Int(workout.duration.rounded())
        guard duration > 0 else { return nil }

        let startedAt = workout.startDate
        let endedAt = workout.endDate
        let day = calendar.startOfDay(for: startedAt)
        let energy: Int?
        if let quantity = workout.totalEnergyBurned {
            let value = quantity.doubleValue(for: .kilocalorie())
            energy = value.isFinite && value > 0 ? Int(value.rounded()) : nil
        } else {
            energy = nil
        }

        return ImportedHealthWorkout(
            id: workout.uuid,
            activityTypeRaw: workout.workoutActivityType.rawValue,
            activityTitle: ImportedHealthWorkoutFormatting.activityTitle(for: workout.workoutActivityType),
            startedAt: startedAt,
            endedAt: endedAt,
            durationSeconds: duration,
            totalEnergyBurned: energy,
            sourceName: sanitizedSource(workout.sourceRevision.source.name),
            deviceName: sanitizedSource(workout.device?.name),
            diaryDateKey: StrengthWorkoutDate.key(for: day, calendar: calendar)
        )
    }

    private static func sanitizedSource(_ value: String?) -> String? {
        guard let value else { return nil }
        let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? nil : trimmed
    }
}

enum ImportedHealthWorkoutFormatting {
    static func activityTitle(for activityType: HKWorkoutActivityType) -> String {
        switch activityType {
        case .americanFootball: return "American Football"
        case .archery: return "Archery"
        case .australianFootball: return "Australian Football"
        case .badminton: return "Badminton"
        case .baseball: return "Baseball"
        case .basketball: return "Basketball"
        case .bowling: return "Bowling"
        case .boxing: return "Boxing"
        case .climbing: return "Climbing"
        case .coreTraining: return "Core Training"
        case .cricket: return "Cricket"
        case .crossTraining: return "Cross Training"
        case .curling: return "Curling"
        case .cycling: return "Cycling"
        case .dance: return "Dance"
        case .elliptical: return "Elliptical"
        case .equestrianSports: return "Equestrian Sports"
        case .fencing: return "Fencing"
        case .fishing: return "Fishing"
        case .functionalStrengthTraining: return "Functional Strength"
        case .golf: return "Golf"
        case .gymnastics: return "Gymnastics"
        case .handball: return "Handball"
        case .hiking: return "Hiking"
        case .hockey: return "Hockey"
        case .hunting: return "Hunting"
        case .lacrosse: return "Lacrosse"
        case .martialArts: return "Martial Arts"
        case .mindAndBody: return "Mind & Body"
        case .mixedMetabolicCardioTraining: return "Mixed Cardio"
        case .paddleSports: return "Paddle Sports"
        case .pickleball: return "Pickleball"
        case .pilates: return "Pilates"
        case .play: return "Play"
        case .preparationAndRecovery: return "Prep & Recovery"
        case .racquetball: return "Racquetball"
        case .rowing: return "Rowing"
        case .rugby: return "Rugby"
        case .running: return "Running"
        case .sailing: return "Sailing"
        case .skatingSports: return "Skating"
        case .snowSports: return "Snow Sports"
        case .soccer: return "Soccer"
        case .softball: return "Softball"
        case .squash: return "Squash"
        case .stairClimbing: return "Stair Climbing"
        case .surfingSports: return "Surfing"
        case .swimming: return "Swimming"
        case .tableTennis: return "Table Tennis"
        case .tennis: return "Tennis"
        case .trackAndField: return "Track & Field"
        case .traditionalStrengthTraining: return "Strength Training"
        case .volleyball: return "Volleyball"
        case .walking: return "Walking"
        case .waterFitness: return "Water Fitness"
        case .waterPolo: return "Water Polo"
        case .waterSports: return "Water Sports"
        case .wrestling: return "Wrestling"
        case .yoga: return "Yoga"
        case .barre: return "Barre"
        case .cooldown: return "Cooldown"
        case .crossCountrySkiing: return "Cross-Country Skiing"
        case .downhillSkiing: return "Downhill Skiing"
        case .flexibility: return "Flexibility"
        case .highIntensityIntervalTraining: return "HIIT"
        case .jumpRope: return "Jump Rope"
        case .kickboxing: return "Kickboxing"
        case .stairs: return "Stairs"
        case .stepTraining: return "Step Training"
        case .wheelchairWalkPace: return "Wheelchair Walk"
        case .wheelchairRunPace: return "Wheelchair Run"
        case .other: return "Workout"
        default: return "Workout"
        }
    }

    static func durationText(seconds: Int) -> String {
        let minutes = max(1, Int(ceil(Double(seconds) / 60)))
        return "\(minutes) min"
    }
}
