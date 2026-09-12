import SwiftUI

enum SavedMealsMode: String, Identifiable {
    case recent = "Recent"
    case frequent = "Frequent"
    case favorites = "Favorites"

    var id: String { rawValue }
}

struct RecentsView: View {
    let mode: SavedMealsMode
    let logDate: Date
    var onReview: ((FoodEntry) -> Void)? = nil

    @Environment(FoodStore.self) private var foodStore
    @Environment(\.dismiss) private var dismiss

    @State private var searchText: String = ""

    private var recentItems: [FoodEntry] {
        let items = foodStore.recentEntries(days: 30)
        return filterByName(items) { $0.name }
    }

    private var frequentItems: [FrequentFoodGroup] {
        let items = foodStore.frequentGroups(days: 90)
        return filterByName(items) { $0.template.name }
    }

    private var favoriteItems: [FoodEntry] {
        filterByName(foodStore.favorites) { $0.name }
    }

    /// Substring, case-insensitive, diacritic-insensitive match against the
    /// extracted name. Empty query returns the full list unchanged.
    private func filterByName<T>(_ items: [T], name: (T) -> String) -> [T] {
        let q = searchText.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !q.isEmpty else { return items }
        return items.filter { item in
            name(item).range(of: q, options: [.caseInsensitive, .diacriticInsensitive]) != nil
        }
    }

    private var isSearching: Bool {
        !searchText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    var body: some View {
        NavigationStack {
            List {
                switch mode {
                case .recent:
                    if recentItems.isEmpty {
                        emptySection(
                            icon: isSearching ? "magnifyingglass" : "clock",
                            message: isSearching ? "No matching foods" : "No foods logged yet"
                        )
                    } else {
                        Section {
                            ForEach(recentItems) { entry in
                                SavedMealRow(entry: entry, isFavorite: foodStore.isFavorite(entry))
                                    .listRowBackground(AppColors.appCard)
                                    .contentShape(Rectangle())
                                    .onTapGesture { logEntry(entry) }
                                    .swipeActions(edge: .trailing) {
                                        Button {
                                            withAnimation { foodStore.toggleFavorite(entry) }
                                        } label: {
                                            Label(foodStore.isFavorite(entry) ? "Unfavorite" : "Favorite", systemImage: foodStore.isFavorite(entry) ? "heart.slash.fill" : "heart.fill")
                                        }
                                        .tint(AppColors.calorie)
                                    }
                            }
                        }
                    }

                case .frequent:
                    if frequentItems.isEmpty {
                        emptySection(
                            icon: isSearching ? "magnifyingglass" : "repeat",
                            message: isSearching ? "No matching foods" : "No foods logged yet"
                        )
                    } else {
                        Section {
                            ForEach(frequentItems) { group in
                                SavedMealRow(entry: group.template, isFavorite: foodStore.isFavorite(group.template), subtitle: "\(group.count)× logged")
                                    .listRowBackground(AppColors.appCard)
                                    .contentShape(Rectangle())
                                    .onTapGesture { logEntry(group.template) }
                                    .swipeActions(edge: .trailing) {
                                        Button {
                                            withAnimation { foodStore.toggleFavorite(group.template) }
                                        } label: {
                                            Label(foodStore.isFavorite(group.template) ? "Unfavorite" : "Favorite", systemImage: foodStore.isFavorite(group.template) ? "heart.slash.fill" : "heart.fill")
                                        }
                                        .tint(AppColors.calorie)
                                    }
                            }
                        }
                    }

                case .favorites:
                    if favoriteItems.isEmpty {
                        emptySection(
                            icon: isSearching ? "magnifyingglass" : "heart",
                            message: isSearching ? "No matching favorites" : "No favorites yet\nSwipe left on any food to add it"
                        )
                    } else {
                        Section {
                            ForEach(favoriteItems) { entry in
                                SavedMealRow(entry: entry, isFavorite: true)
                                    .listRowBackground(AppColors.appCard)
                                    .contentShape(Rectangle())
                                    .onTapGesture { logEntry(entry) }
                                    .swipeActions(edge: .trailing) {
                                        Button(role: .destructive) {
                                            withAnimation { foodStore.toggleFavorite(entry) }
                                        } label: {
                                            Label("Remove", systemImage: "heart.slash.fill")
                                        }
                                    }
                            }
                            // Reorder is only meaningful on the unfiltered list — the
                            // ForEach indices we'd hand to moveFavorite are the
                            // filtered indices, which don't map back to favorites
                            // when a search is active.
                            .onMove(perform: isSearching ? nil : { from, to in
                                foodStore.moveFavorite(from: from, to: to)
                            })
                        }
                    }
                }
            }
            .scrollContentBackground(.hidden)
            .background(AppColors.appBackground)
            .navigationTitle(mode.rawValue)
            .navigationBarTitleDisplayMode(.inline)
            .searchable(text: $searchText, placement: .navigationBarDrawer(displayMode: .always), prompt: Text("Search saved foods"))
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Done") { dismiss() }
                }
                // Hide the EditButton while searching — drag-to-reorder writes
                // back to the unfiltered favorites list using the rendered row
                // indices, so a filtered list would reorder the wrong items.
                if mode == .favorites && !foodStore.favorites.isEmpty && !isSearching {
                    ToolbarItem(placement: .topBarTrailing) {
                        EditButton()
                    }
                }
            }
        }
    }

    private func logEntry(_ entry: FoodEntry) {
        let prepared = entry.duplicatedForLogging(at: logDate)
        dismiss()
        if let onReview {
            onReview(prepared)
        } else {
            foodStore.addEntry(prepared)
        }
    }

    private func emptySection(icon: String, message: String) -> some View {
        Section {
            VStack(spacing: 12) {
                Image(systemName: icon)
                    .font(.system(size: 32))
                    .foregroundStyle(AppColors.calorie.opacity(0.4))
                Text(message)
                    .font(.system(.subheadline, design: .rounded))
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
            }
            .frame(maxWidth: .infinity)
            .padding(.vertical, 24)
            .listRowBackground(AppColors.appCard)
        }
    }
}

// MARK: - Saved Meal Row

private struct SavedMealRow: View {
    let entry: FoodEntry
    let isFavorite: Bool
    var subtitle: String? = nil

    var body: some View {
        HStack(spacing: 12) {
            // Thumbnail
            if entry.imageFilename != nil || entry.imageData != nil {
                FoodEntryThumbnailView(
                    filename: entry.imageFilename,
                    legacyData: entry.imageData,
                    additionalCount: entry.listThumbnailAdditionalPhotoCount,
                    showsAdditionalBadge: false
                )
            } else if let emoji = entry.emoji {
                Text(emoji)
                    .font(.system(size: 28))
                    .frame(width: 56, height: 56)
                    .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
            } else {
                Image(systemName: "fork.knife")
                    .font(.title3)
                    .foregroundStyle(AppColors.calorie)
                    .frame(width: 56, height: 56)
                    .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
            }

            // Info
            VStack(alignment: .leading, spacing: 3) {
                HStack(spacing: 4) {
                    Text(entry.name)
                        .font(.system(.body, design: .rounded, weight: .medium))
                        .fixedSize(horizontal: false, vertical: true)
                    if isFavorite {
                        Image(systemName: "heart.fill")
                            .font(.caption2)
                            .foregroundStyle(AppColors.calorie)
                    }
                }

                HStack(spacing: 6) {
                    Text("\(entry.calories) kcal")
                        .font(.system(.subheadline, design: .rounded, weight: .semibold))
                        .foregroundStyle(AppColors.calorie)

                    if let subtitle {
                        Text("·")
                            .foregroundStyle(.tertiary)
                        Text(subtitle)
                            .font(.system(.caption, design: .rounded))
                            .foregroundStyle(.secondary)
                    }
                }

                HStack(spacing: 8) {
                    MacroTag(label: "P", value: entry.protein)
                    MacroTag(label: "C", value: entry.carbs)
                    MacroTag(label: "F", value: entry.fat)
                }
            }

            Spacer(minLength: 0)

            // Log button
            Image(systemName: "plus.circle.fill")
                .font(.title3)
                .foregroundStyle(AppColors.calorie)
        }
        .padding(.vertical, 4)
    }
}

// MARK: - Macro Tag

private struct MacroTag: View {
    let label: String
    let value: Double

    var body: some View {
        Text("\(label) \(MacroValueFormatter.withUnit(value))")
            .font(.system(.caption2, design: .rounded, weight: .medium))
            .foregroundStyle(.secondary)
            .padding(.horizontal, 6)
            .padding(.vertical, 2)
            .background(AppColors.calorie.opacity(0.08), in: Capsule())
    }
}
