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
    @State private var showPhotoModeChoice = false
    @State private var selectedPhotoItem: PhotosPickerItem?
    @State private var showPhotoPicker = false
    @State private var showError = false
    @State private var errorMessage = ""

    enum ActiveSheet: String, Identifiable {
        case analyzing, foodResult
        var id: String { rawValue }
    }
    @State private var activeSheet: ActiveSheet?

    @State private var currentFoodResult: GeminiService.FoodAnalysis?
    @State private var currentImage: UIImage?

    var body: some View {
        NavigationStack {
            List {
                Section {
                    WeekSelectorView()
                }

                Section("Calories") {
                    CalorieRow(eaten: foodStore.todayCalories, goal: 2500)
                }

                Section("Log Food") {
                    HStack(spacing: 12) {
                        Button(action: {
                            cameraMode = .snapFood
                            showCamera = true
                        }) {
                            VStack(spacing: 8) {
                                Image(systemName: "camera.fill")
                                    .font(.title2)
                                Text("Snap Food")
                                    .font(.caption)
                                    .fontWeight(.medium)
                            }
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 16)
                        }
                        .buttonStyle(.bordered)
                        .tint(.accentColor)

                        Button(action: {
                            cameraMode = .nutritionLabel
                            showCamera = true
                        }) {
                            VStack(spacing: 8) {
                                Image(systemName: "text.viewfinder")
                                    .font(.title2)
                                Text("Nutrition Label")
                                    .font(.caption)
                                    .fontWeight(.medium)
                            }
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 16)
                        }
                        .buttonStyle(.bordered)
                        .tint(.accentColor)

                        Button(action: { showPhotoModeChoice = true }) {
                            VStack(spacing: 8) {
                                Image(systemName: "photo.on.rectangle")
                                    .font(.title2)
                                Text("From Photos")
                                    .font(.caption)
                                    .fontWeight(.medium)
                            }
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 16)
                        }
                        .buttonStyle(.bordered)
                        .tint(.accentColor)
                    }
                    .listRowInsets(EdgeInsets(top: 8, leading: 16, bottom: 8, trailing: 16))
                }

                Section("Macros") {
                    MacroRow(name: "Protein", current: foodStore.todayProtein, goal: 150, unit: "g", color: .orange)
                    MacroRow(name: "Carbs", current: foodStore.todayCarbs, goal: 275, unit: "g", color: .yellow)
                    MacroRow(name: "Fat", current: foodStore.todayFat, goal: 70, unit: "g", color: .blue)
                }

                if foodStore.todayEntriesByMeal.isEmpty {
                    Section("Today's Food") {
                        Text("No foods logged today")
                            .foregroundStyle(.secondary)
                    }
                } else {
                    ForEach(foodStore.todayEntriesByMeal, id: \.meal) { group in
                        Section {
                            ForEach(group.entries) { entry in
                                FoodRow(entry: entry)
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
            .navigationTitle("Cal AI")
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    HStack(spacing: 4) {
                        Image(systemName: "flame.fill")
                            .foregroundColor(.orange)
                        Text("15")
                            .fontWeight(.semibold)
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
            .confirmationDialog("What are you uploading?", isPresented: $showPhotoModeChoice) {
                Button("Food Photo") {
                    cameraMode = .snapFood
                    showPhotoPicker = true
                }
                Button("Nutrition Label") {
                    cameraMode = .nutritionLabel
                    showPhotoPicker = true
                }
            }
            .photosPicker(isPresented: $showPhotoPicker, selection: $selectedPhotoItem, matching: .images)
            .onChange(of: selectedPhotoItem) { oldValue, newValue in
                guard let item = newValue else { return }
                selectedPhotoItem = nil
                Task {
                    if let data = try? await item.loadTransferable(type: Data.self),
                       let image = UIImage(data: data) {
                        currentImage = image
                        startAnalysis(image: image, mode: cameraMode)
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

// MARK: - Week Selector View
struct WeekSelectorView: View {
    let days = ["Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"]
    let dates = [10, 11, 12, 13, 14, 15, 16]
    @State private var selectedIndex = 3

    var body: some View {
        HStack {
            ForEach(0..<7, id: \.self) { index in
                VStack(spacing: 4) {
                    Text(days[index])
                        .font(.caption2)
                        .foregroundStyle(.secondary)

                    Text("\(dates[index])")
                        .font(.subheadline)
                        .fontWeight(.semibold)
                        .foregroundStyle(index == selectedIndex ? .white : .primary)
                        .frame(width: 28, height: 28)
                        .background(
                            Circle()
                                .fill(index == selectedIndex ? Color.accentColor : Color.clear)
                        )
                }
                .frame(maxWidth: .infinity)
                .onTapGesture {
                    selectedIndex = index
                }
            }
        }
    }
}

// MARK: - Calorie Row
struct CalorieRow: View {
    let eaten: Int
    let goal: Int

    var body: some View {
        HStack {
            VStack(alignment: .leading) {
                Text("\(eaten) / \(goal)")
                    .font(.title)
                    .fontWeight(.bold)
                Text("Calories eaten")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            }

            Spacer()

            Gauge(value: Double(eaten), in: 0...Double(goal)) {
                Image(systemName: "flame.fill")
            }
            .gaugeStyle(.accessoryCircularCapacity)
            .tint(.primary)
        }
    }
}

// MARK: - Macro Row
struct MacroRow: View {
    let name: String
    let current: Int
    let goal: Int
    let unit: String
    let color: Color

    var body: some View {
        HStack {
            Label(name, systemImage: "circle.fill")
                .foregroundStyle(color)

            Spacer()

            Text("\(current)/\(goal)\(unit)")
                .foregroundStyle(.secondary)

            Gauge(value: Double(current), in: 0...Double(goal)) {
                EmptyView()
            }
            .gaugeStyle(.accessoryLinearCapacity)
            .tint(color)
            .frame(width: 60)
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
                    MacroPill(label: "P", value: entry.protein, color: .orange)
                    MacroPill(label: "C", value: entry.carbs, color: .yellow)
                    MacroPill(label: "F", value: entry.fat, color: .blue)
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

                Section("Statistics") {
                    LabeledContent("Daily Average", value: "2861 cal")
                    LabeledContent("Weekly Average", value: "2750 cal")
                }
            }
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
            }
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

                Section("Settings") {
                    Label("Notifications", systemImage: "bell")
                    Label("Goals", systemImage: "target")
                    Label("Units", systemImage: "scalemass")
                }

                Section {
                    Label("Sign Out", systemImage: "rectangle.portrait.and.arrow.right")
                        .foregroundStyle(.red)
                }
            }
            .navigationTitle("Profile")
        }
    }
}

#Preview {
    ContentView()
        .environment(FoodStore())
}
