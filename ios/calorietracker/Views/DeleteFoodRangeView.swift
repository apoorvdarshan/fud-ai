import SwiftUI

struct DeleteFoodRangeView: View {
    @Environment(FoodStore.self) private var food
    @Environment(HealthKitManager.self) private var health
    @Environment(\.dismiss) private var dismiss
    @State private var start = Date.now
    @State private var end = Date.now
    @State private var preview: [FoodEntry]?
    @State private var alsoHealth = false
    @State private var confirming = false
    @State private var failed = false

    var body: some View {
        NavigationStack {
            Form {
                Section("Range") {
                    DatePicker("From", selection: $start, displayedComponents: .date)
                    DatePicker("To", selection: $end, displayedComponents: .date)
                    Button("Preview") {
                        alsoHealth = false
                        preview = food.entries.filter { entry in
                            let day = Calendar.current.startOfDay(for: entry.timestamp)
                            return day >= Calendar.current.startOfDay(for: start) && day <= Calendar.current.startOfDay(for: end)
                        }
                    }
                    .disabled(Calendar.current.startOfDay(for: start) > Calendar.current.startOfDay(for: end))
                }
                if let preview {
                    Section {
                        LabeledContent("Food entries", value: preview.count.formatted())
                        if !preview.isEmpty {
                            Toggle("Also remove matching Apple Health nutrition", isOn: $alsoHealth)
                            Button("Delete", role: .destructive) { confirming = true }
                        }
                    } footer: {
                        Text("Only food entries in the preview are deleted. Favorites, water, and other logs are kept.")
                    }
                }
            }
            .navigationTitle("Delete Food by Date")
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } } }
            .onChange(of: start) { _, _ in preview = nil }
            .onChange(of: end) { _, _ in preview = nil }
            .confirmationDialog("Delete the previewed food entries?", isPresented: $confirming, titleVisibility: .visible) {
                Button("Delete", role: .destructive) {
                    guard let preview, let ids = food.deletePreviewedEntries(preview) else {
                        failed = true
                        return
                    }
                    if alsoHealth { for id in ids { health.deleteNutrition(entryID: id) } }
                    dismiss()
                }
            } message: {
                Text(alsoHealth ? "This also requests deletion of matching nutrition written by Fud AI in Apple Health." :
                     "This deletes entries from Fud AI only. Copies in Apple Health are kept.")
            }
            .alert("Could Not Delete Food Entries", isPresented: $failed) {
                Button("OK", role: .cancel) {}
            }
        }
    }
}
