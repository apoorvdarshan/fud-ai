import SwiftUI
import UniformTypeIdentifiers

struct ImportDiaryView: View {
    @Environment(FoodStore.self) private var foodStore
    @Environment(WaterStore.self) private var waterStore
    @Environment(\.dismiss) private var dismiss

    @State private var isPickingFile = false
    @State private var preview: DiaryImportPreview?
    @State private var errorMessage: String?
    @State private var importedCount: Int?

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Button {
                        isPickingFile = true
                    } label: {
                        Label("Choose JSON File", systemImage: "doc.badge.plus")
                            .frame(maxWidth: .infinity)
                            .font(.system(.body, design: .rounded, weight: .semibold))
                    }
                    .buttonStyle(.borderedProminent)
                    .tint(AppColors.calorie)
                    .listRowInsets(EdgeInsets())
                    .listRowBackground(Color.clear)
                } footer: {
                    Text("Choose a JSON file previously exported by Fud AI. The file is validated before any diary entry is changed.")
                }

                if let preview {
                    Section("Import Preview") {
                        LabeledContent("Food entries", value: "\(preview.entryCount)")
                        LabeledContent("Water entries", value: "\(preview.waterEntries.count)")
                        LabeledContent("Date range", value: rangeText(preview))
                    }

                    Section {
                        Button {
                            apply(preview, mode: .replaceDateRange)
                        } label: {
                            Label("Replace This Date Range", systemImage: "arrow.triangle.2.circlepath")
                                .frame(maxWidth: .infinity)
                                .font(.system(.body, design: .rounded, weight: .semibold))
                        }
                        .buttonStyle(.borderedProminent)
                        .tint(AppColors.calorie)
                        .listRowInsets(EdgeInsets())
                        .listRowBackground(Color.clear)

                        Button {
                            apply(preview, mode: .addAsNew)
                        } label: {
                            Label("Add as New Entries", systemImage: "plus.circle")
                                .frame(maxWidth: .infinity)
                                .font(.system(.body, design: .rounded, weight: .semibold))
                        }
                        .buttonStyle(.bordered)
                        .tint(AppColors.calorie)
                        .listRowInsets(EdgeInsets(top: 8, leading: 0, bottom: 0, trailing: 0))
                        .listRowBackground(Color.clear)
                    } footer: {
                        Text("Replace removes existing food logs only within the exported date range, then recreates that range from this file. Water logs in that range are also replaced when the file includes water data. Older food-only files leave water logs unchanged. Matching entries keep their photos. Add keeps the current diary and creates duplicates as new entries.")
                    }
                }
            }
            .scrollContentBackground(.hidden)
            .background(AppColors.appBackground)
            .navigationTitle("Import Food Diary")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Done") { dismiss() }
                }
            }
            .fileImporter(
                isPresented: $isPickingFile,
                allowedContentTypes: [.json],
                allowsMultipleSelection: false,
                onCompletion: loadFile
            )
            .alert("Unable to Import", isPresented: Binding(
                get: { errorMessage != nil },
                set: { if !$0 { errorMessage = nil } }
            )) {
                Button("OK", role: .cancel) { errorMessage = nil }
            } message: {
                Text(errorMessage ?? "The selected file could not be imported.")
            }
            .alert("Import Complete", isPresented: Binding(
                get: { importedCount != nil },
                set: { if !$0 { importedCount = nil } }
            )) {
                Button("Done") { dismiss() }
            } message: {
                Text("Imported \(importedCount ?? 0) diary entries.")
            }
        }
    }

    private func loadFile(_ result: Result<[URL], Error>) {
        do {
            guard let url = try result.get().first else { return }
            let accessing = url.startAccessingSecurityScopedResource()
            defer { if accessing { url.stopAccessingSecurityScopedResource() } }
            let values = try url.resourceValues(forKeys: [.fileSizeKey])
            if let size = values.fileSize, size > DiaryImporter.maximumFileSize {
                throw DiaryImportError.fileTooLarge
            }
            preview = try DiaryImporter.parse(Data(contentsOf: url, options: [.mappedIfSafe]))
            errorMessage = nil
        } catch {
            preview = nil
            errorMessage = (error as? LocalizedError)?.errorDescription ?? error.localizedDescription
        }
    }

    private func apply(_ preview: DiaryImportPreview, mode: DiaryImportMode) {
        let updated = DiaryImporter.applying(preview, to: foodStore.entries, mode: mode)
        foodStore.replaceEntriesFromImport(updated)
        if preview.includesWater {
            waterStore.replaceEntriesFromImport(DiaryImporter.applyingWater(preview, to: waterStore.entries, mode: mode))
        }
        importedCount = preview.entryCount + preview.waterEntries.count
        self.preview = nil
    }

    private func rangeText(_ preview: DiaryImportPreview) -> String {
        let formatter = DateFormatter()
        formatter.dateStyle = .medium
        formatter.timeStyle = .none
        return "\(formatter.string(from: preview.startDate)) – \(formatter.string(from: preview.endDate))"
    }
}
