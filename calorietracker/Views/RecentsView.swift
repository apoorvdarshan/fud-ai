import SwiftUI
import UIKit

struct RecentsView: View {
    let logDate: Date

    @Environment(FoodStore.self) private var foodStore
    @Environment(\.dismiss) private var dismiss

    @State private var segment: RecentsSegment = .recents

    private enum RecentsSegment: String, CaseIterable {
        case recents = "Recents"
        case frequent = "Frequent"
        case favorites = "Favorites"
    }

    private var recentItems: [FoodEntry] {
        foodStore.recentEntries(limit: 50)
    }

    private var frequentItems: [FrequentFoodGroup] {
        foodStore.frequentGroups()
    }

    var body: some View {
        NavigationStack {
            List {
                Section {
                    Picker("View", selection: $segment) {
                        ForEach(RecentsSegment.allCases, id: \.self) { mode in
                            Text(mode.rawValue).tag(mode)
                        }
                    }
                    .pickerStyle(.segmented)
                    .listRowBackground(Color.clear)
                }

                switch segment {
                case .recents:
                    if recentItems.isEmpty {
                        Section {
                            emptyState("No foods logged yet")
                        }
                    } else {
                        Section {
                            ForEach(recentItems) { entry in
                                FoodRow(entry: entry)
                                    .listRowBackground(AppColors.appCard)
                                    .contentShape(Rectangle())
                                    .onTapGesture { logEntry(entry) }
                                    .swipeActions(edge: .trailing) {
                                        Button {
                                            foodStore.toggleFavorite(entry)
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
                        Section {
                            emptyState("No foods logged yet")
                        }
                    } else {
                        Section {
                            ForEach(frequentItems) { group in
                                FrequentFoodRow(group: group)
                                    .listRowBackground(AppColors.appCard)
                                    .contentShape(Rectangle())
                                    .onTapGesture { logEntry(group.template) }
                                    .swipeActions(edge: .trailing) {
                                        Button {
                                            foodStore.toggleFavorite(group.template)
                                        } label: {
                                            Label(foodStore.isFavorite(group.template) ? "Unfavorite" : "Favorite", systemImage: foodStore.isFavorite(group.template) ? "heart.slash.fill" : "heart.fill")
                                        }
                                        .tint(AppColors.calorie)
                                    }
                            }
                        }
                    }

                case .favorites:
                    if foodStore.favorites.isEmpty {
                        Section {
                            emptyState("No favorites yet. Swipe left on any food to add it.")
                        }
                    } else {
                        Section {
                            ForEach(foodStore.favorites) { entry in
                                FoodRow(entry: entry)
                                    .listRowBackground(AppColors.appCard)
                                    .contentShape(Rectangle())
                                    .onTapGesture { logEntry(entry) }
                                    .swipeActions(edge: .trailing) {
                                        Button(role: .destructive) {
                                            foodStore.toggleFavorite(entry)
                                        } label: {
                                            Label("Remove", systemImage: "heart.slash.fill")
                                        }
                                    }
                            }
                        }
                    }
                }
            }
            .scrollContentBackground(.hidden)
            .background(AppColors.appBackground)
            .navigationTitle("Log Again")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Done") { dismiss() }
                }
            }
        }
    }

    private func logEntry(_ entry: FoodEntry) {
        foodStore.addEntry(entry.duplicatedForLogging(at: logDate))
        dismiss()
    }

    private func emptyState(_ message: String) -> some View {
        Text(message)
            .foregroundStyle(.secondary)
            .frame(maxWidth: .infinity)
            .listRowBackground(AppColors.appCard)
    }
}

private struct FrequentFoodRow: View {
    let group: FrequentFoodGroup

    var body: some View {
        HStack(spacing: 12) {
            if let imageData = group.template.imageData, let uiImage = UIImage(data: imageData) {
                Image(uiImage: uiImage)
                    .resizable()
                    .scaledToFill()
                    .frame(width: 64, height: 64)
                    .clipShape(RoundedRectangle(cornerRadius: 10))
            } else if let emoji = group.template.emoji {
                Text(emoji)
                    .font(.system(size: 36))
                    .frame(width: 64, height: 64)
                    .background(.quaternary)
                    .clipShape(RoundedRectangle(cornerRadius: 10))
            } else {
                Image(systemName: "photo")
                    .font(.title)
                    .frame(width: 64, height: 64)
                    .background(.quaternary)
                    .clipShape(RoundedRectangle(cornerRadius: 10))
            }

            VStack(alignment: .leading, spacing: 4) {
                Text(group.name)
                    .font(.body.weight(.medium))

                HStack(spacing: 8) {
                    Text("\(group.calories) kcal")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                    Text("·")
                        .foregroundStyle(.tertiary)
                    Text("Logged \(group.count)×")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                }
            }

            Spacer(minLength: 0)
        }
        .padding(.vertical, 4)
    }
}
