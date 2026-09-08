import Foundation

/// A stable reference to a photo while an existing food log is being edited.
struct FoodEntryPhoto: Identifiable {
    enum ID: Hashable {
        case filename(String)
        case primaryData
        case additionalData(Int)
    }

    let id: ID
    let data: Data?
}

extension FoodEntry {
    var editablePhotos: [FoodEntryPhoto] {
        var photos: [FoodEntryPhoto] = []
        var seen = Set<FoodEntryPhoto.ID>()

        func append(_ id: FoodEntryPhoto.ID, data: Data?) {
            guard seen.insert(id).inserted else { return }
            photos.append(FoodEntryPhoto(id: id, data: data))
        }

        if let imageFilename {
            append(.filename(imageFilename), data: FoodImageStore.shared.load(filename: imageFilename) ?? imageData)
        } else if let imageData {
            append(.primaryData, data: imageData)
        }
        for index in 0..<max(additionalImageData.count, additionalImageFilenames.count) {
            let filename = additionalImageFilenames.indices.contains(index) ? additionalImageFilenames[index] : nil
            append(filename.map { .filename($0) } ?? .additionalData(index), data: additionalPhotoData(at: index))
        }
        for filename in ingredients.flatMap(\.allImageFilenames) {
            append(.filename(filename), data: FoodImageStore.shared.load(filename: filename))
        }
        return photos
    }

    /// Makes an editable copy without touching disk or changing meal/nutrition fields.
    /// IDs come from the original entry, so removing one photo cannot shift the
    /// identity of the remaining in-memory photos during the edit session.
    func removingPhotos(withIDs removedIDs: Set<FoodEntryPhoto.ID>) -> FoodEntry {
        guard !removedIDs.isEmpty else { return self }
        var result = self
        let primaryID = imageFilename.map { FoodEntryPhoto.ID.filename($0) } ?? .primaryData
        if removedIDs.contains(primaryID) {
            result.imageFilename = nil
            result.imageData = nil
        }

        result.additionalImageFilenames = []
        result.additionalImageData = []
        var canAppendCachedData = true
        for index in 0..<max(additionalImageData.count, additionalImageFilenames.count) {
            let filename = additionalImageFilenames.indices.contains(index) ? additionalImageFilenames[index] : nil
            let id = filename.map { FoodEntryPhoto.ID.filename($0) } ?? .additionalData(index)
            guard !removedIDs.contains(id) else { continue }
            if let filename { result.additionalImageFilenames.append(filename) }
            // Cache only an aligned prefix. compactMap would assign a later
            // photo's bytes to an earlier missing file on the next edit.
            if let data = additionalPhotoData(at: index), canAppendCachedData {
                result.additionalImageData.append(data)
            } else {
                canAppendCachedData = false
            }
        }

        result.ingredients = ingredients.map { $0.removingPhotos(withIDs: removedIDs) }

        // Promote the next photo together with its bytes. Leaving the removed
        // primary's cached bytes behind would recreate that photo on the next save.
        if result.imageFilename == nil, result.imageData == nil {
            if !result.additionalImageFilenames.isEmpty || !result.additionalImageData.isEmpty {
                result.imageFilename = result.additionalImageFilenames.first
                result.imageData = result.additionalImageData.first
                    ?? result.imageFilename.flatMap { FoodImageStore.shared.load(filename: $0) }
                if !result.additionalImageFilenames.isEmpty { result.additionalImageFilenames.removeFirst() }
                if !result.additionalImageData.isEmpty { result.additionalImageData.removeFirst() }
            } else if let filename = result.ingredients.flatMap(\.allImageFilenames).first {
                result.imageFilename = filename
                result.imageData = FoodImageStore.shared.load(filename: filename)
            }
        }
        return result
    }

    private func additionalPhotoData(at index: Int) -> Data? {
        if additionalImageFilenames.indices.contains(index) {
            if let data = FoodImageStore.shared.load(filename: additionalImageFilenames[index]) { return data }
            // Decoding older entries compactMaps missing files out of this
            // cache, so partial caches cannot identify a photo by index.
            guard additionalImageData.count >= additionalImageFilenames.count else { return nil }
        }
        return additionalImageData.indices.contains(index) ? additionalImageData[index] : nil
    }
}

extension MealIngredient {
    func removingPhotos(withIDs removedIDs: Set<FoodEntryPhoto.ID>) -> MealIngredient {
        var result = self
        let filenames = allImageFilenames
        let retained = filenames.filter { !removedIDs.contains(.filename($0)) }
        if retained != filenames {
            result.imageFilename = retained.first
            result.additionalImageFilenames = Array(retained.dropFirst())
        }
        return result
    }
}
