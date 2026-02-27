import Foundation
import CloudKit

struct CloudData {
    var foodEntries: [FoodEntry]
    var weightEntries: [WeightEntry]
    var profile: UserProfile?
}

struct CloudKitService {
    private static let containerID = "iCloud.com.apoorvdarshan.calorietracker"
    private static var container: CKContainer { CKContainer(identifier: containerID) }
    private static var database: CKDatabase { container.privateCloudDatabase }

    // MARK: - Record Types
    private static let foodType = "FoodEntry"
    private static let weightType = "WeightEntry"
    private static let profileType = "UserProfile"
    private static let profileRecordName = "userProfile"

    // MARK: - Availability

    static func isAvailable() async -> Bool {
        do {
            let status = try await CKContainer.default().accountStatus()
            return status == .available
        } catch {
            return false
        }
    }

    // MARK: - FoodEntry ↔ CKRecord

    static func record(from entry: FoodEntry) -> CKRecord {
        let recordID = CKRecord.ID(recordName: entry.id.uuidString)
        let record = CKRecord(recordType: foodType, recordID: recordID)
        record["name"] = entry.name
        record["calories"] = entry.calories
        record["protein"] = entry.protein
        record["carbs"] = entry.carbs
        record["fat"] = entry.fat
        record["timestamp"] = entry.timestamp
        record["emoji"] = entry.emoji
        record["source"] = entry.source.rawValue
        record["mealType"] = entry.mealType.rawValue
        // imageData intentionally NOT synced (too large for CKRecord)
        if let v = entry.sugar { record["sugar"] = v }
        if let v = entry.addedSugar { record["addedSugar"] = v }
        if let v = entry.fiber { record["fiber"] = v }
        if let v = entry.saturatedFat { record["saturatedFat"] = v }
        if let v = entry.monounsaturatedFat { record["monounsaturatedFat"] = v }
        if let v = entry.polyunsaturatedFat { record["polyunsaturatedFat"] = v }
        if let v = entry.cholesterol { record["cholesterol"] = v }
        if let v = entry.sodium { record["sodium"] = v }
        if let v = entry.potassium { record["potassium"] = v }
        if let v = entry.servingSizeGrams { record["servingSizeGrams"] = v }
        return record
    }

    static func foodEntry(from record: CKRecord) -> FoodEntry? {
        guard let name = record["name"] as? String,
              let calories = record["calories"] as? Int,
              let protein = record["protein"] as? Int,
              let carbs = record["carbs"] as? Int,
              let fat = record["fat"] as? Int,
              let timestamp = record["timestamp"] as? Date,
              let sourceRaw = record["source"] as? String,
              let source = FoodSource(rawValue: sourceRaw)
        else { return nil }

        let emoji = record["emoji"] as? String
        let mealTypeRaw = record["mealType"] as? String ?? MealType.other.rawValue
        let mealType = MealType(rawValue: mealTypeRaw) ?? .other

        guard let id = UUID(uuidString: record.recordID.recordName) else { return nil }

        return FoodEntry(
            id: id,
            name: name,
            calories: calories,
            protein: protein,
            carbs: carbs,
            fat: fat,
            timestamp: timestamp,
            imageData: nil,
            emoji: emoji,
            source: source,
            mealType: mealType,
            sugar: record["sugar"] as? Double,
            addedSugar: record["addedSugar"] as? Double,
            fiber: record["fiber"] as? Double,
            saturatedFat: record["saturatedFat"] as? Double,
            monounsaturatedFat: record["monounsaturatedFat"] as? Double,
            polyunsaturatedFat: record["polyunsaturatedFat"] as? Double,
            cholesterol: record["cholesterol"] as? Double,
            sodium: record["sodium"] as? Double,
            potassium: record["potassium"] as? Double,
            servingSizeGrams: record["servingSizeGrams"] as? Double
        )
    }

    // MARK: - WeightEntry ↔ CKRecord

    static func record(from entry: WeightEntry) -> CKRecord {
        let recordID = CKRecord.ID(recordName: entry.id.uuidString)
        let record = CKRecord(recordType: weightType, recordID: recordID)
        record["date"] = entry.date
        record["weightKg"] = entry.weightKg
        return record
    }

    static func weightEntry(from record: CKRecord) -> WeightEntry? {
        guard let date = record["date"] as? Date,
              let weightKg = record["weightKg"] as? Double,
              let id = UUID(uuidString: record.recordID.recordName)
        else { return nil }

        return WeightEntry(id: id, date: date, weightKg: weightKg)
    }

    // MARK: - UserProfile ↔ CKRecord

    static func record(from profile: UserProfile) -> CKRecord {
        let recordID = CKRecord.ID(recordName: profileRecordName)
        let record = CKRecord(recordType: profileType, recordID: recordID)
        record["name"] = profile.name
        record["gender"] = profile.gender.rawValue
        record["birthday"] = profile.birthday
        record["heightCm"] = profile.heightCm
        record["weightKg"] = profile.weightKg
        record["activityLevel"] = profile.activityLevel.rawValue
        record["goal"] = profile.goal.rawValue
        if let bf = profile.bodyFatPercentage { record["bodyFatPercentage"] = bf }
        if let wc = profile.weeklyChangeKg { record["weeklyChangeKg"] = wc }
        if let gw = profile.goalWeightKg { record["goalWeightKg"] = gw }
        if let cc = profile.customCalories { record["customCalories"] = cc }
        if let cp = profile.customProtein { record["customProtein"] = cp }
        if let cf = profile.customFat { record["customFat"] = cf }
        if let ccarbs = profile.customCarbs { record["customCarbs"] = ccarbs }
        return record
    }

    static func userProfile(from record: CKRecord) -> UserProfile? {
        guard let genderRaw = record["gender"] as? String,
              let gender = Gender(rawValue: genderRaw),
              let birthday = record["birthday"] as? Date,
              let heightCm = record["heightCm"] as? Double,
              let weightKg = record["weightKg"] as? Double,
              let activityRaw = record["activityLevel"] as? String,
              let activityLevel = ActivityLevel(rawValue: activityRaw),
              let goalRaw = record["goal"] as? String,
              let goal = WeightGoal(rawValue: goalRaw)
        else { return nil }

        return UserProfile(
            name: record["name"] as? String,
            gender: gender,
            birthday: birthday,
            heightCm: heightCm,
            weightKg: weightKg,
            activityLevel: activityLevel,
            goal: goal,
            bodyFatPercentage: record["bodyFatPercentage"] as? Double,
            weeklyChangeKg: record["weeklyChangeKg"] as? Double,
            goalWeightKg: record["goalWeightKg"] as? Double,
            customCalories: record["customCalories"] as? Int,
            customProtein: record["customProtein"] as? Int,
            customFat: record["customFat"] as? Int,
            customCarbs: record["customCarbs"] as? Int
        )
    }

    // MARK: - Push

    static func saveFoodEntry(_ entry: FoodEntry) async {
        let rec = record(from: entry)
        do {
            let _ = try await database.save(rec)
        } catch {
            // Silent failure — sync catches up on next mutation
        }
    }

    static func saveWeightEntry(_ entry: WeightEntry) async {
        let rec = record(from: entry)
        do {
            let _ = try await database.save(rec)
        } catch {}
    }

    static func saveProfile(_ profile: UserProfile) async {
        let rec = record(from: profile)
        do {
            // Use modifyRecords to upsert (handles both create and update)
            let _ = try await database.modifyRecords(saving: [rec], deleting: [], savePolicy: .allKeys)
        } catch {}
    }

    static func deleteFoodEntry(id: UUID) async {
        let recordID = CKRecord.ID(recordName: id.uuidString)
        do {
            try await database.deleteRecord(withID: recordID)
        } catch {}
    }

    static func deleteWeightEntry(id: UUID) async {
        let recordID = CKRecord.ID(recordName: id.uuidString)
        do {
            try await database.deleteRecord(withID: recordID)
        } catch {}
    }

    // MARK: - Push All Data

    static func pushAllData(foodEntries: [FoodEntry], weightEntries: [WeightEntry], profile: UserProfile?) async {
        var allRecords: [CKRecord] = []

        for entry in foodEntries {
            allRecords.append(record(from: entry))
        }
        for entry in weightEntries {
            allRecords.append(record(from: entry))
        }
        if let profile {
            allRecords.append(record(from: profile))
        }

        // Batch in groups of 400 (CloudKit limit)
        let batchSize = 400
        for batchStart in stride(from: 0, to: allRecords.count, by: batchSize) {
            let end = min(batchStart + batchSize, allRecords.count)
            let batch = Array(allRecords[batchStart..<end])
            do {
                let _ = try await database.modifyRecords(saving: batch, deleting: [], savePolicy: .allKeys)
            } catch {}
        }
    }

    // MARK: - Delete All Data

    static func deleteAllData() async {
        do {
            let foods = try await fetchAllRecords(ofType: foodType)
            let weights = try await fetchAllRecords(ofType: weightType)
            let profiles = try await fetchAllRecords(ofType: profileType)

            let allIDs = (foods + weights + profiles).map { $0.recordID }

            let batchSize = 400
            for batchStart in stride(from: 0, to: allIDs.count, by: batchSize) {
                let end = min(batchStart + batchSize, allIDs.count)
                let batch = Array(allIDs[batchStart..<end])
                let _ = try await database.modifyRecords(saving: [], deleting: batch)
            }
        } catch {}
    }

    // MARK: - Pull All Data

    static func pullAllData() async throws -> CloudData {
        async let foods = fetchAllRecords(ofType: foodType)
        async let weights = fetchAllRecords(ofType: weightType)
        async let profiles = fetchAllRecords(ofType: profileType)

        let foodRecords = try await foods
        let weightRecords = try await weights
        let profileRecords = try await profiles

        let foodEntries = foodRecords.compactMap { foodEntry(from: $0) }
        let weightEntries = weightRecords.compactMap { weightEntry(from: $0) }
        let profile = profileRecords.first.flatMap { userProfile(from: $0) }

        return CloudData(foodEntries: foodEntries, weightEntries: weightEntries, profile: profile)
    }

    private static func fetchAllRecords(ofType recordType: String) async throws -> [CKRecord] {
        var allRecords: [CKRecord] = []
        let query = CKQuery(recordType: recordType, predicate: NSPredicate(value: true))

        var cursor: CKQueryOperation.Cursor?
        let (results, nextCursor) = try await database.records(matching: query, resultsLimit: CKQueryOperation.maximumResults)
        for (_, result) in results {
            if let record = try? result.get() {
                allRecords.append(record)
            }
        }
        cursor = nextCursor

        while let currentCursor = cursor {
            let (moreResults, moreCursor) = try await database.records(continuingMatchFrom: currentCursor, resultsLimit: CKQueryOperation.maximumResults)
            for (_, result) in moreResults {
                if let record = try? result.get() {
                    allRecords.append(record)
                }
            }
            cursor = moreCursor
        }

        return allRecords
    }
}
