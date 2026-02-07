import SwiftUI
import PhotosUI
import UIKit

// MARK: - Camera Mode
enum CameraMode {
    case snapFood
    case nutritionLabel
}

// MARK: - Main Content View
struct ContentView: View {
    var body: some View {
        TabView {
            HomeView()
                .tabItem {
                    Image(systemName: "house.fill")
                    Text("Home")
                }

            ProgressTabView()
                .tabItem {
                    Image(systemName: "chart.bar.fill")
                    Text("Progress")
                }

            GroupsView()
                .tabItem {
                    Image(systemName: "person.3.fill")
                    Text("Groups")
                }

            ProfileView()
                .tabItem {
                    Image(systemName: "person.circle.fill")
                    Text("Profile")
                }
        }
    }
}

// MARK: - Home View (Main Dashboard)
struct HomeView: View {
    @Environment(FoodStore.self) private var foodStore
    @State private var showCamera = false
    @State private var capturedImage: UIImage?
    @State private var cameraMode: CameraMode = .snapFood
    @State private var selectedPhotoItem: PhotosPickerItem?
    @State private var showPhotoPicker = false
    @State private var showError = false
    @State private var errorMessage = ""
    @State private var selectedDate: Date = .now

    enum ActiveSheet: String, Identifiable {
        case analyzing, foodResult
        var id: String { rawValue }
    }
    @State private var activeSheet: ActiveSheet?

    @State private var currentFoodResult: GeminiService.FoodAnalysis?
    @State private var currentImage: UIImage?

    private var calorieGoal: Int { 2500 }
    private var selectedCalories: Int { foodStore.calories(for: selectedDate) }
    private var caloriesRemaining: Int { max(calorieGoal - selectedCalories, 0) }
    private var isToday: Bool { Calendar.current.isDateInToday(selectedDate) }

    private var navigationTitle: String {
        if isToday { return "Today" }
        return selectedDate.formatted(.dateTime.weekday(.wide).month(.abbreviated).day())
    }

    var body: some View {
        NavigationStack {
            List {
                // Week energy strip
                Section {
                    WeekEnergyStrip(
                        selectedDate: $selectedDate,
                        caloriesForDate: { foodStore.calories(for: $0) },
                        calorieGoal: calorieGoal
                    )
                    .listRowBackground(Color.clear)
                    .listRowSeparator(.hidden)
                }

                // Calorie hero
                Section {
                    VStack(spacing: 20) {
                        VStack(spacing: 4) {
                            Text("\(selectedCalories)")
                                .font(.system(size: 72, weight: .bold, design: .rounded))
                                .foregroundStyle(
                                    LinearGradient(colors: AppColors.calorieGradient, startPoint: .topLeading, endPoint: .bottomTrailing)
                                )
                                .contentTransition(.numericText())
                                .animation(.snappy, value: selectedCalories)

                            Text("of \(calorieGoal) kcal")
                                .font(.system(.callout, design: .rounded, weight: .medium))
                                .foregroundStyle(.tertiary)
                        }

                        GeometryReader { geo in
                            ZStack(alignment: .leading) {
                                Capsule()
                                    .fill(AppColors.calorie.opacity(0.10))
                                    .frame(height: 10)

                                Capsule()
                                    .fill(LinearGradient(colors: AppColors.calorieGradient, startPoint: .leading, endPoint: .trailing))
                                    .frame(width: max(10, geo.size.width * min(Double(selectedCalories) / Double(calorieGoal), 1.0)), height: 10)
                                    .shadow(color: AppColors.calorie.opacity(0.35), radius: 8, y: 3)
                                    .animation(.spring(response: 0.8, dampingFraction: 0.75), value: selectedCalories)
                            }
                        }
                        .frame(height: 10)
                        .padding(.horizontal, 24)

                        Text("\(caloriesRemaining) remaining")
                            .font(.system(.footnote, design: .rounded, weight: .medium))
                            .foregroundStyle(.secondary)
                    }
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 20)
                    .listRowBackground(Color.clear)
                    .listRowSeparator(.hidden)
                }

                // Macro trio
                Section {
                    HStack(spacing: 20) {
                        MacroCard(label: "Protein", current: foodStore.protein(for: selectedDate), goal: 150, gradientColors: AppColors.proteinGradient)
                        MacroCard(label: "Carbs", current: foodStore.carbs(for: selectedDate), goal: 275, gradientColors: AppColors.carbsGradient)
                        MacroCard(label: "Fat", current: foodStore.fat(for: selectedDate), goal: 70, gradientColors: AppColors.fatGradient)
                    }
                    .padding(.vertical, 8)
                    .listRowBackground(Color.clear)
                    .listRowSeparator(.hidden)
                }

                // Food list
                let mealGroups = foodStore.entriesByMeal(for: selectedDate)
                if mealGroups.isEmpty {
                    Section(isToday ? "Today's Food" : "Food Log") {
                        Text("No foods logged")
                            .foregroundStyle(.secondary)
                            .listRowBackground(AppColors.appCard)
                    }
                } else {
                    ForEach(mealGroups, id: \.meal) { group in
                        Section {
                            ForEach(group.entries) { entry in
                                FoodRow(entry: entry)
                                    .listRowBackground(AppColors.appCard)
                            }
                            .onDelete { offsets in
                                for index in offsets {
                                    foodStore.deleteEntry(group.entries[index])
                                }
                            }
                        } header: {
                            Label(group.meal.displayName, systemImage: group.meal.icon)
                        }
                    }
                }
            }
            .scrollContentBackground(.hidden)
            .background(AppColors.appBackground)
            .animation(.snappy, value: selectedDate)
            .navigationTitle(navigationTitle)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Menu {
                        Button(action: {
                            cameraMode = .snapFood
                            showCamera = true
                        }) {
                            Label("Camera", systemImage: "camera.fill")
                        }
                        Button(action: {
                            cameraMode = .nutritionLabel
                            showCamera = true
                        }) {
                            Label("Nutrition Label", systemImage: "text.viewfinder")
                        }
                        Button(action: { showPhotoPicker = true }) {
                            Label("From Photos", systemImage: "photo.on.rectangle")
                        }
                    } label: {
                        Image(systemName: "plus")
                    }
                }
            }
            .fullScreenCover(isPresented: $showCamera) {
                CameraView(image: $capturedImage)
                    .ignoresSafeArea()
            }
            .onChange(of: capturedImage) { oldValue, newValue in
                guard let image = newValue else { return }
                capturedImage = nil
                currentImage = image
                startAnalysis(image: image, mode: cameraMode)
            }
            .sheet(item: $activeSheet) { sheet in
                switch sheet {
                case .analyzing:
                    if let image = currentImage {
                        AnalyzingView(image: image)
                    }
                case .foodResult:
                    if let image = currentImage, let result = currentFoodResult {
                        FoodResultView(
                            image: image,
                            source: cameraMode == .snapFood ? .snapFood : .nutritionLabel,
                            name: result.name,
                            calories: result.calories,
                            protein: result.protein,
                            carbs: result.carbs,
                            fat: result.fat,
                            onLog: { entry in
                                foodStore.addEntry(entry)
                            }
                        )
                    }
                }
            }
            .interactiveDismissDisabled(activeSheet == .analyzing)
            .photosPicker(isPresented: $showPhotoPicker, selection: $selectedPhotoItem, matching: .images)
            .onChange(of: selectedPhotoItem) { oldValue, newValue in
                guard let item = newValue else { return }
                selectedPhotoItem = nil
                Task {
                    if let data = try? await item.loadTransferable(type: Data.self),
                       let image = UIImage(data: data) {
                        currentImage = image
                        activeSheet = .analyzing
                        do {
                            let result = try await GeminiService.autoAnalyze(image: image)
                            currentFoodResult = result
                            activeSheet = .foodResult
                        } catch {
                            activeSheet = nil
                            errorMessage = error.localizedDescription
                            showError = true
                        }
                    }
                }
            }
            .alert("Error", isPresented: $showError) {
                Button("OK") { }
            } message: {
                Text(errorMessage)
            }
        }
    }

    private func startAnalysis(image: UIImage, mode: CameraMode) {
        activeSheet = .analyzing

        Task {
            do {
                switch mode {
                case .snapFood:
                    let result = try await GeminiService.analyzeFood(image: image)
                    currentFoodResult = result
                    activeSheet = .foodResult

                case .nutritionLabel:
                    let label = try await GeminiService.analyzeNutritionLabel(image: image)
                    let servingGrams = label.servingSizeGrams ?? 100
                    currentFoodResult = label.scaled(to: servingGrams)
                    activeSheet = .foodResult
                }
            } catch {
                activeSheet = nil
                errorMessage = error.localizedDescription
                showError = true
            }
        }
    }

}


// MARK: - Camera View (UIKit wrapper)
struct CameraView: UIViewControllerRepresentable {
    @Binding var image: UIImage?
    @Environment(\.dismiss) private var dismiss

    func makeUIViewController(context: Context) -> UIImagePickerController {
        let picker = UIImagePickerController()
        picker.sourceType = .camera
        picker.delegate = context.coordinator
        picker.modalPresentationStyle = .fullScreen
        picker.edgesForExtendedLayout = .all
        return picker
    }

    func updateUIViewController(_ uiViewController: UIImagePickerController, context: Context) {}

    func makeCoordinator() -> Coordinator {
        Coordinator(self)
    }

    class Coordinator: NSObject, UIImagePickerControllerDelegate, UINavigationControllerDelegate {
        let parent: CameraView

        init(_ parent: CameraView) {
            self.parent = parent
        }

        func imagePickerController(_ picker: UIImagePickerController, didFinishPickingMediaWithInfo info: [UIImagePickerController.InfoKey: Any]) {
            if let image = info[.originalImage] as? UIImage {
                parent.image = image
            }
            parent.dismiss()
        }

        func imagePickerControllerDidCancel(_ picker: UIImagePickerController) {
            parent.dismiss()
        }
    }
}

// MARK: - Food Row
struct FoodRow: View {
    let entry: FoodEntry

    var body: some View {
        HStack(spacing: 12) {
            if let imageData = entry.imageData, let uiImage = UIImage(data: imageData) {
                Image(uiImage: uiImage)
                    .resizable()
                    .scaledToFill()
                    .frame(width: 64, height: 64)
                    .clipShape(RoundedRectangle(cornerRadius: 10))
            } else {
                Image(systemName: "photo")
                    .font(.title)
                    .frame(width: 64, height: 64)
                    .background(.quaternary)
                    .clipShape(RoundedRectangle(cornerRadius: 10))
            }

            VStack(alignment: .leading, spacing: 4) {
                HStack {
                    Text(entry.name)
                        .fontWeight(.medium)
                    Spacer()
                    Text(entry.timeString)
                        .font(.caption)
                        .foregroundStyle(.tertiary)
                }

                Text("\(entry.calories) cal")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)

                HStack(spacing: 6) {
                    MacroPill(label: "P", value: entry.protein, color: AppColors.protein)
                    MacroPill(label: "C", value: entry.carbs, color: AppColors.carbs)
                    MacroPill(label: "F", value: entry.fat, color: AppColors.fat)
                }
            }
        }
        .padding(.vertical, 4)
    }
}

struct MacroPill: View {
    let label: String
    let value: Int
    let color: Color

    var body: some View {
        Text("\(label): \(value)g")
            .font(.caption2)
            .fontWeight(.medium)
            .padding(.horizontal, 6)
            .padding(.vertical, 2)
            .background(color.opacity(0.15))
            .foregroundStyle(color)
            .clipShape(Capsule())
    }
}

// MARK: - Placeholder Views for Other Tabs
struct ProgressTabView: View {
    var body: some View {
        NavigationStack {
            List {
                Section("Weight") {
                    LabeledContent("Current", value: "132.1 lbs")
                    LabeledContent("Goal", value: "140 lbs")
                }
                .listRowBackground(AppColors.appCard)

                Section("Statistics") {
                    LabeledContent("Daily Average", value: "2861 cal")
                    LabeledContent("Weekly Average", value: "2750 cal")
                }
                .listRowBackground(AppColors.appCard)
            }
            .scrollContentBackground(.hidden)
            .background(AppColors.appBackground)
            .navigationTitle("Progress")
        }
    }
}

struct GroupsView: View {
    var body: some View {
        NavigationStack {
            List {
                Text("No groups yet")
                    .foregroundStyle(.secondary)
                    .listRowBackground(AppColors.appCard)
            }
            .scrollContentBackground(.hidden)
            .background(AppColors.appBackground)
            .navigationTitle("Groups")
        }
    }
}

struct ProfileView: View {
    var body: some View {
        NavigationStack {
            List {
                Section {
                    HStack {
                        Image(systemName: "person.circle.fill")
                            .font(.system(size: 60))
                            .foregroundStyle(.secondary)
                        VStack(alignment: .leading) {
                            Text("User")
                                .font(.title2)
                                .fontWeight(.semibold)
                            Text("user@email.com")
                                .foregroundStyle(.secondary)
                        }
                    }
                }
                .listRowBackground(AppColors.appCard)

                Section("Settings") {
                    Label("Notifications", systemImage: "bell")
                    Label("Goals", systemImage: "target")
                    Label("Units", systemImage: "scalemass")
                }
                .listRowBackground(AppColors.appCard)

                Section {
                    Label("Sign Out", systemImage: "rectangle.portrait.and.arrow.right")
                        .foregroundStyle(.red)
                }
                .listRowBackground(AppColors.appCard)
            }
            .scrollContentBackground(.hidden)
            .background(AppColors.appBackground)
            .navigationTitle("Profile")
        }
    }
}

#Preview {
    ContentView()
        .environment(FoodStore())
}
