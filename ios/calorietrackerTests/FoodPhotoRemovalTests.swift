import Foundation
import Testing
@testable import calorietracker

@MainActor
struct FoodPhotoRemovalTests {
    @Test func removingAMiddlePhotoKeepsOtherCachedPhotosInOrderAfterReload() throws {
        try withStore { store, defaults in
            var meal = makeMeal()
            meal.imageData = Data("first".utf8)
            meal.additionalImageData = [Data("middle".utf8), Data("last".utf8)]
            store.replaceAllEntries([meal])
            let saved = try #require(store.entries.first)
            defer { saved.allImageFilenames.forEach { FoodImageStore.shared.delete(filename: $0) } }
            let middle = saved.additionalImageFilenames[0]

            let edited = saved.removingPhotos(withIDs: [.filename(middle)])
            #expect(edited.allImageData == [Data("first".utf8), Data("last".utf8)])
            // Staging a removal leaves the saved row and file untouched (Cancel).
            #expect(store.entries.first?.allImageFilenames == saved.allImageFilenames)
            #expect(FoodImageStore.shared.load(filename: middle) == Data("middle".utf8))

            store.updateEntry(edited)
            #expect(FoodImageStore.shared.load(filename: middle) == nil)
            let reloaded = FoodStore(observesExternalChanges: false, defaults: defaults)
            #expect(reloaded.entries.first?.allImageData == [Data("first".utf8), Data("last".utf8)])
            #expect(reloaded.entries.first?.allImageFilenames == [saved.imageFilename!, saved.additionalImageFilenames[1]])
        }
    }

    @Test func removingPrimaryPromotesNextPhotoWithoutResurrectingCachedBytes() throws {
        try withStore { store, defaults in
            var meal = makeMeal()
            meal.imageData = Data("primary".utf8)
            meal.additionalImageData = [Data("second".utf8)]
            store.replaceAllEntries([meal])
            let saved = try #require(store.entries.first)
            defer { saved.allImageFilenames.forEach { FoodImageStore.shared.delete(filename: $0) } }
            let primary = try #require(saved.imageFilename)
            let second = try #require(saved.additionalImageFilenames.first)

            let edited = saved.removingPhotos(withIDs: [.filename(primary)])
            #expect(edited.imageFilename == second)
            #expect(edited.imageData == Data("second".utf8))
            #expect(edited.additionalImageData.isEmpty)
            store.updateEntry(edited)

            let reloaded = FoodStore(observesExternalChanges: false, defaults: defaults)
            #expect(FoodImageStore.shared.load(filename: primary) == nil)
            #expect(reloaded.entries.first?.allImageFilenames == [second])
            #expect(reloaded.entries.first?.imageData == Data("second".utf8))
        }
    }

    @Test func removingLastPhotoClearsEveryIngredientReferenceAndKeepsNutrition() throws {
        try withStore { store, defaults in
            var meal = makeMeal()
            meal.imageData = Data("shared ingredient photo".utf8)
            store.replaceAllEntries([meal])
            var saved = try #require(store.entries.first)
            let filename = try #require(saved.imageFilename)
            defer { FoodImageStore.shared.delete(filename: filename) }
            saved.ingredients = [
                MealIngredient(name: "Rice", grams: 100, calories: 120, protein: 3, carbs: 25, fat: 1,
                               imageFilename: filename, additionalImageFilenames: [filename]),
                MealIngredient(name: "Beans", grams: 100, calories: 150, protein: 9, carbs: 27, fat: 1,
                               imageFilename: filename)
            ]
            store.updateEntry(saved)
            #expect(saved.editablePhotos.count == 1)

            let edited = saved.removingPhotos(withIDs: [.filename(filename)])
            #expect(edited.imageData == nil)
            #expect(edited.imageFilename == nil)
            #expect(edited.additionalImageData.isEmpty)
            #expect(edited.allImageFilenames.isEmpty)
            #expect(edited.ingredients.map(\.allImageFilenames) == [[], []])
            #expect(edited.ingredients.map(\.id) == saved.ingredients.map(\.id))
            #expect(edited.ingredients.ingredientTotals == saved.ingredients.ingredientTotals)
            #expect(edited.name == saved.name)
            #expect(edited.timestamp == saved.timestamp)
            #expect(edited.calories == saved.calories)
            #expect(edited.protein == saved.protein)
            #expect(edited.carbs == saved.carbs)
            #expect(edited.fat == saved.fat)
            #expect(edited.fiber == saved.fiber)
            #expect(edited.servingSizeGrams == saved.servingSizeGrams)
            #expect(edited.customNote == saved.customNote)
            #expect(edited.mealType == saved.mealType)

            store.updateEntry(edited)
            let reloaded = FoodStore(observesExternalChanges: false, defaults: defaults)
            #expect(reloaded.entries.count == 1)
            #expect(reloaded.entries.first?.allImageData.isEmpty == true)
            #expect(reloaded.entries.first?.allImageFilenames.isEmpty == true)
            #expect(FoodImageStore.shared.load(filename: filename) == nil)
        }
    }

    @Test func removingIngredientPhotoPromotesItsRemainingReferenceWithoutChangingPortion() {
        var meal = makeMeal()
        meal.ingredients = [MealIngredient(
            name: "Rice", grams: 120, calories: 150, protein: 4, carbs: 30, fat: 1,
            imageFilename: "rice.jpg", additionalImageFilenames: ["label.jpg"]
        )]
        #expect(meal.editablePhotos.map(\.id) == [.filename("rice.jpg"), .filename("label.jpg")])
        let edited = meal.removingPhotos(withIDs: [.filename("rice.jpg")])
        #expect(edited.allImageFilenames == ["label.jpg"])
        #expect(edited.ingredients.first?.imageFilename == "label.jpg")
        #expect(edited.ingredients.first?.additionalImageFilenames == [])
        #expect(edited.ingredients.ingredientTotals == meal.ingredients.ingredientTotals)
    }

    @Test func inMemoryPhotoIDsStayStableAcrossMultipleStagedRemovals() {
        var meal = makeMeal()
        meal.imageData = Data("first".utf8)
        meal.additionalImageData = [Data("second".utf8), Data("third".utf8)]
        let photos = meal.editablePhotos
        #expect(photos.map(\.id) == [.primaryData, .additionalData(0), .additionalData(1)])

        let removed: Set<FoodEntryPhoto.ID> = [photos[0].id, photos[1].id]
        let edited = meal.removingPhotos(withIDs: removed)
        #expect(edited.allImageData == [Data("third".utf8)])
        #expect(meal.allImageData.count == 3)
        let cleared = meal.removingPhotos(withIDs: Set(photos.map(\.id)))
        #expect(cleared.allImageData.isEmpty)
        #expect(cleared.imageData == nil)
        #expect(cleared.additionalImageData.isEmpty)
    }

    @Test func missingFileDoesNotGiveTheNextPhotoItsRemovalID() throws {
        try withStore { store, defaults in
            var meal = makeMeal()
            meal.imageData = Data("primary".utf8)
            meal.additionalImageData = [Data("missing".utf8), Data("surviving".utf8)]
            store.replaceAllEntries([meal])
            let saved = try #require(store.entries.first)
            defer { saved.allImageFilenames.forEach { FoodImageStore.shared.delete(filename: $0) } }
            let missing = saved.additionalImageFilenames[0]
            let surviving = saved.additionalImageFilenames[1]
            FoodImageStore.shared.delete(filename: missing)

            let reloadedStore = FoodStore(observesExternalChanges: false, defaults: defaults)
            let reloaded = try #require(reloadedStore.entries.first)
            // Legacy decoding compactMaps the missing file out of the byte cache.
            #expect(reloaded.additionalImageData == [Data("surviving".utf8)])
            let photos = reloaded.editablePhotos
            #expect(photos.first { $0.id == .filename(missing) }?.data == nil)
            #expect(photos.first { $0.id == .filename(surviving) }?.data == Data("surviving".utf8))

            let edited = reloaded.removingPhotos(withIDs: [.filename(missing)])
            #expect(edited.additionalImageFilenames == [surviving])
            #expect(edited.additionalImageData == [Data("surviving".utf8)])
            reloadedStore.updateEntry(edited)
            #expect(reloadedStore.entries.first?.allImageData == [Data("primary".utf8), Data("surviving".utf8)])

            let removeVisiblePhoto = reloaded.removingPhotos(withIDs: [.filename(surviving)])
            #expect(removeVisiblePhoto.additionalImageFilenames == [missing])
            #expect(removeVisiblePhoto.additionalImageData.isEmpty)
        }
    }

    @Test func promotingUnfiledPhotoDoesNotOverwriteTheFavoritesOriginal() throws {
        try withStore { store, defaults in
            var meal = makeMeal()
            meal.imageData = Data("favorite original".utf8)
            store.replaceAllEntries([meal])
            var edited = try #require(store.entries.first)
            let originalFilename = try #require(edited.imageFilename)
            store.toggleFavorite(edited)
            defer {
                let filenames = Set(store.entries.flatMap(\.allImageFilenames) + [originalFilename])
                filenames.forEach { FoodImageStore.shared.delete(filename: $0) }
            }
            edited.additionalImageData = [Data("unfiled replacement".utf8)]
            edited = edited.removingPhotos(withIDs: [.filename(originalFilename)])
            #expect(edited.imageFilename == nil)
            #expect(edited.imageData == Data("unfiled replacement".utf8))

            store.updateEntry(edited)
            let reloaded = FoodStore(observesExternalChanges: false, defaults: defaults)
            #expect(reloaded.entries.first?.imageFilename != originalFilename)
            #expect(reloaded.entries.first?.allImageData == [Data("unfiled replacement".utf8)])
            #expect(reloaded.favorites.first?.allImageData == [Data("favorite original".utf8)])
        }
    }

    @Test func failedPersistenceKeepsRemovedFileForThePreviouslySavedLog() throws {
        try withStore { store, defaults in
            var meal = makeMeal()
            meal.imageData = Data("saved photo".utf8)
            store.replaceAllEntries([meal])
            let saved = try #require(store.entries.first)
            let filename = try #require(saved.imageFilename)
            defer { FoodImageStore.shared.delete(filename: filename) }
            var edited = saved.removingPhotos(withIDs: [.filename(filename)])
            edited.protein = .nan // JSONEncoder rejects this update.
            store.updateEntry(edited)

            let reloaded = FoodStore(observesExternalChanges: false, defaults: defaults)
            #expect(reloaded.entries.first?.imageFilename == filename)
            #expect(reloaded.entries.first?.allImageData == [Data("saved photo".utf8)])
        }
    }

    @Test(arguments: [false, true])
    func savedRemovalKeepsFilesSharedWithAnotherLogOrFavorite(sharedWithFavorite: Bool) throws {
        try withStore { store, defaults in
            var meal = makeMeal()
            meal.imageData = Data("shared".utf8)
            store.replaceAllEntries([meal])
            let saved = try #require(store.entries.first)
            let filename = try #require(saved.imageFilename)
            defer { FoodImageStore.shared.delete(filename: filename) }

            if sharedWithFavorite {
                store.toggleFavorite(saved)
            } else {
                let other = FoodEntry(
                    name: "Other meal", calories: 100, protein: 5, carbs: 15, fat: 3,
                    source: .manual,
                    ingredients: [MealIngredient(name: "Rice", grams: 100, calories: 100, protein: 5, carbs: 15, fat: 3,
                                                 imageFilename: filename)]
                )
                store.replaceAllEntries([saved, other])
            }

            store.updateEntry(saved.removingPhotos(withIDs: [.filename(filename)]))
            #expect(FoodImageStore.shared.load(filename: filename) == Data("shared".utf8))
            let reloaded = FoodStore(observesExternalChanges: false, defaults: defaults)
            #expect(reloaded.entries.first { $0.id == saved.id }?.allImageData.isEmpty == true)
            if sharedWithFavorite {
                #expect(reloaded.favorites.first?.allImageData == [Data("shared".utf8)])
            } else {
                #expect(reloaded.entries.first { $0.id != saved.id }?.allImageData == [Data("shared".utf8)])
            }
        }
    }

    private func makeMeal() -> FoodEntry {
        FoodEntry(
            name: "Rice and beans", calories: 270, protein: 12, carbs: 52, fat: 2,
            timestamp: Date(timeIntervalSince1970: 1_800_000_000),
            source: .snapFood, mealType: .lunch, fiber: 8,
            servingSizeGrams: 200, customNote: "Homemade"
        )
    }

    private func withStore(_ body: (FoodStore, UserDefaults) throws -> Void) throws {
        let suite = "FoodPhotoRemovalTests.\(UUID().uuidString)"
        let defaults = try #require(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        try body(FoodStore(observesExternalChanges: false, defaults: defaults), defaults)
    }
}
