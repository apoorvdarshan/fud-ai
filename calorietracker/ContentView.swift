import SwiftUI
import PhotosUI
import UIKit
import AuthenticationServices
import HealthKit
import StoreKit

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
    @Environment(StoreManager.self) private var storeManager
    @State private var showCamera = false
    @State private var capturedImage: UIImage?
    @State private var cameraMode: CameraMode = .snapFood
    @State private var selectedPhotoItem: PhotosPickerItem?
    @State private var showPhotoPicker = false
    @State private var showError = false
    @State private var errorMessage = ""
    @State private var selectedDate: Date = .now
    @State private var showScanLimitAlert = false

    enum ActiveSheet: String, Identifiable {
        case analyzing, foodResult, textInput, analyzingText, editFood, voice
        var id: String { rawValue }
    }
    @State private var activeSheet: ActiveSheet?
    @State private var editingEntry: FoodEntry?

    @State private var currentFoodResult: GeminiService.FoodAnalysis?
    @State private var currentImage: UIImage?
    @State private var currentEmoji: String?
    @State private var showNutritionDetail = false

    private var userProfile: UserProfile { UserProfile.load() ?? .default }
    private var calorieGoal: Int { userProfile.effectiveCalories }
    private var proteinGoal: Int { userProfile.effectiveProtein }
    private var carbsGoal: Int { userProfile.effectiveCarbs }
    private var fatGoal: Int { userProfile.effectiveFat }
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
                    .listRowInsets(EdgeInsets(top: 0, leading: 16, bottom: 0, trailing: 16))
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

                        Text("\(caloriesRemaining) left")
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
                        MacroCard(label: "Protein", current: foodStore.protein(for: selectedDate), goal: proteinGoal, gradientColors: AppColors.proteinGradient)
                        MacroCard(label: "Carbs", current: foodStore.carbs(for: selectedDate), goal: carbsGoal, gradientColors: AppColors.carbsGradient)
                        MacroCard(label: "Fat", current: foodStore.fat(for: selectedDate), goal: fatGoal, gradientColors: AppColors.fatGradient)
                    }
                    .padding(.vertical, 8)
                    .listRowBackground(Color.clear)
                    .listRowSeparator(.hidden)

                    Button {
                        showNutritionDetail = true
                    } label: {
                        HStack {
                            Spacer()
                            Text("View More")
                                .font(.system(.subheadline, design: .rounded, weight: .medium))
                            Image(systemName: "chevron.right")
                                .font(.caption2)
                            Spacer()
                        }
                        .foregroundStyle(AppColors.calorie.opacity(0.6))
                    }
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
                                    .contentShape(Rectangle())
                                    .onTapGesture {
                                        editingEntry = entry
                                        activeSheet = .editFood
                                    }
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
            .navigationTitle("")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Menu {
                            Button(action: {
                                guard checkScanAvailable() else { return }
                                cameraMode = .snapFood
                                showCamera = true
                            }) {
                                Label("Camera", systemImage: "camera.fill")
                            }
                            Button(action: {
                                guard checkScanAvailable() else { return }
                                cameraMode = .nutritionLabel
                                showCamera = true
                            }) {
                                Label("Nutrition Label", systemImage: "text.viewfinder")
                            }
                            Button(action: {
                                guard checkScanAvailable() else { return }
                                showPhotoPicker = true
                            }) {
                                Label("From Photos", systemImage: "photo.on.rectangle")
                            }
                            Button(action: {
                                guard checkScanAvailable() else { return }
                                activeSheet = .textInput
                            }) {
                                Label("Text Input", systemImage: "character.cursor.ibeam")
                            }
                            Button(action: {
                                guard checkScanAvailable() else { return }
                                activeSheet = .voice
                            }) {
                                Label("Voice", systemImage: "mic.fill")
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
                currentEmoji = nil
                startAnalysis(image: image, mode: cameraMode)
            }
            .sheet(item: $activeSheet) { sheet in
                switch sheet {
                case .analyzing:
                    AnalyzingView(image: currentImage)
                case .analyzingText:
                    AnalyzingView(image: nil, message: "Looking up nutrition...")
                case .foodResult:
                    if let result = currentFoodResult {
                        FoodResultView(
                            image: currentImage,
                            emoji: currentEmoji,
                            source: currentImage == nil ? .textInput : (cameraMode == .snapFood ? .snapFood : .nutritionLabel),
                            name: result.name,
                            calories: result.calories,
                            protein: result.protein,
                            carbs: result.carbs,
                            fat: result.fat,
                            servingSizeGrams: result.servingSizeGrams,
                            sugar: result.sugar,
                            addedSugar: result.addedSugar,
                            fiber: result.fiber,
                            saturatedFat: result.saturatedFat,
                            monounsaturatedFat: result.monounsaturatedFat,
                            polyunsaturatedFat: result.polyunsaturatedFat,
                            cholesterol: result.cholesterol,
                            sodium: result.sodium,
                            potassium: result.potassium,
                            logDate: selectedDate,
                            onLog: { entry in
                                foodStore.addEntry(entry)
                            }
                        )
                    }
                case .editFood:
                    if let editingEntry {
                        EditFoodEntryView(entry: editingEntry)
                    }
                case .textInput:
                    TextFoodInputView { description in
                        currentImage = nil
                        currentEmoji = nil
                        activeSheet = .analyzingText
                        Task {
                            do {
                                let result = try await GeminiService.analyzeTextInput(description: description)
                                storeManager.recordScan()
                                currentFoodResult = result
                                currentEmoji = result.emoji
                                activeSheet = .foodResult
                            } catch {
                                activeSheet = nil
                                errorMessage = error.localizedDescription
                                showError = true
                            }
                        }
                    }
                case .voice:
                    VoiceInputView { description in
                        currentImage = nil
                        currentEmoji = nil
                        activeSheet = .analyzingText
                        Task {
                            do {
                                let result = try await GeminiService.analyzeTextInput(description: description)
                                storeManager.recordScan()
                                currentFoodResult = result
                                currentEmoji = result.emoji
                                activeSheet = .foodResult
                            } catch {
                                activeSheet = nil
                                errorMessage = error.localizedDescription
                                showError = true
                            }
                        }
                    }
                }
            }
            .interactiveDismissDisabled(activeSheet == .analyzing || activeSheet == .analyzingText)
            .photosPicker(isPresented: $showPhotoPicker, selection: $selectedPhotoItem, matching: .images)
            .onChange(of: selectedPhotoItem) { oldValue, newValue in
                guard let item = newValue else { return }
                selectedPhotoItem = nil
                Task {
                    if let data = try? await item.loadTransferable(type: Data.self),
                       let image = UIImage(data: data) {
                        currentImage = image
                        currentEmoji = nil
                        activeSheet = .analyzing
                        do {
                            let result = try await GeminiService.autoAnalyze(image: image)
                            storeManager.recordScan()
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
            .alert("Daily Limit Reached", isPresented: $showScanLimitAlert) {
                Button("OK") { }
            } message: {
                Text("You've used all 25 scans for today. Try again tomorrow!")
            }
            .sheet(isPresented: $showNutritionDetail) {
                NutritionDetailView(date: selectedDate)
            }
        }
    }

    private func checkScanAvailable() -> Bool {
        if !storeManager.canScan {
            showScanLimitAlert = true
            return false
        }
        return true
    }

    private func startAnalysis(image: UIImage, mode: CameraMode) {
        activeSheet = .analyzing

        Task {
            do {
                switch mode {
                case .snapFood:
                    let result = try await GeminiService.analyzeFood(image: image)
                    storeManager.recordScan()
                    currentFoodResult = result
                    activeSheet = .foodResult

                case .nutritionLabel:
                    let label = try await GeminiService.analyzeNutritionLabel(image: image)
                    storeManager.recordScan()
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


// MARK: - Nutrition Detail View
struct NutritionDetailView: View {
    let date: Date
    @Environment(FoodStore.self) private var foodStore
    @Environment(\.dismiss) private var dismiss

    private var userProfile: UserProfile { UserProfile.load() ?? .default }

    var body: some View {
        NavigationStack {
            List {
                Section("Macros") {
                    NutritionDetailRow(label: "Calories", value: "\(foodStore.calories(for: date))", unit: "kcal", goal: "\(userProfile.effectiveCalories)")
                    NutritionDetailRow(label: "Protein", value: "\(foodStore.protein(for: date))", unit: "g", goal: "\(userProfile.effectiveProtein)")
                     NutritionDetailRow(label: "Carbs", value: "\(foodStore.carbs(for: date))", unit: "g", goal: "\(userProfile.effectiveCarbs)")
                    NutritionDetailRow(label: "Fat", value: "\(foodStore.fat(for: date))", unit: "g", goal: "\(userProfile.effectiveFat)")
                }

                Section("Detailed Nutrition") {
                    NutritionDetailRow(label: "Sugar", value: formatMicro(foodStore.sugar(for: date)), unit: "g")
                    NutritionDetailRow(label: "Added Sugar", value: formatMicro(foodStore.addedSugar(for: date)), unit: "g")
                    NutritionDetailRow(label: "Fiber", value: formatMicro(foodStore.fiber(for: date)), unit: "g")
                    NutritionDetailRow(label: "Saturated Fat", value: formatMicro(foodStore.saturatedFat(for: date)), unit: "g")
                    NutritionDetailRow(label: "Mono Unsat. Fat", value: formatMicro(foodStore.monounsaturatedFat(for: date)), unit: "g")
                    NutritionDetailRow(label: "Poly Unsat. Fat", value: formatMicro(foodStore.polyunsaturatedFat(for: date)), unit: "g")
                    NutritionDetailRow(label: "Cholesterol", value: formatMicro(foodStore.cholesterol(for: date)), unit: "mg")
                    NutritionDetailRow(label: "Sodium", value: formatMicro(foodStore.sodium(for: date)), unit: "mg")
                    NutritionDetailRow(label: "Potassium", value: formatMicro(foodStore.potassium(for: date)), unit: "mg")
                }
            }
            .scrollContentBackground(.hidden)
            .background(AppColors.appBackground)
            .navigationTitle("Nutrition Details")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") { dismiss() }
                }
            }
        }
    }

    private func formatMicro(_ value: Double) -> String {
        value == 0 ? "—" : String(format: "%.1f", value)
    }
}

struct NutritionDetailRow: View {
    let label: String
    let value: String
    let unit: String
    var goal: String? = nil

    var body: some View {
        HStack {
            Text(label)
            Spacer()
            Text(value)
                .fontWeight(.medium)
            Text(unit)
                .foregroundStyle(.secondary)
                .frame(width: 36, alignment: .leading)
            if let goal {
                Text("/ \(goal)")
                    .font(.caption)
                    .foregroundStyle(.tertiary)
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
        picker.showsCameraControls = false

        // Custom overlay with shutter + cancel buttons
        let overlay = UIView(frame: UIScreen.main.bounds)
        overlay.isUserInteractionEnabled = true
        overlay.backgroundColor = .clear

        let bottomBar = UIView()
        bottomBar.backgroundColor = UIColor.black.withAlphaComponent(0.6)
        bottomBar.translatesAutoresizingMaskIntoConstraints = false
        overlay.addSubview(bottomBar)

        let shutterOuter = UIView()
        shutterOuter.backgroundColor = .white
        shutterOuter.layer.cornerRadius = 37
        shutterOuter.translatesAutoresizingMaskIntoConstraints = false
        bottomBar.addSubview(shutterOuter)

        let shutterInner = UIView()
        shutterInner.backgroundColor = .white
        shutterInner.layer.cornerRadius = 32
        shutterInner.layer.borderWidth = 2
        shutterInner.layer.borderColor = UIColor.black.withAlphaComponent(0.15).cgColor
        shutterInner.translatesAutoresizingMaskIntoConstraints = false
        shutterOuter.addSubview(shutterInner)

        let shutterButton = UIButton(type: .system)
        shutterButton.translatesAutoresizingMaskIntoConstraints = false
        shutterButton.addTarget(context.coordinator, action: #selector(Coordinator.capture), for: .touchUpInside)
        shutterOuter.addSubview(shutterButton)

        let cancelButton = UIButton(type: .system)
        cancelButton.setTitle("Cancel", for: .normal)
        cancelButton.setTitleColor(.white, for: .normal)
        cancelButton.titleLabel?.font = .systemFont(ofSize: 17)
        cancelButton.translatesAutoresizingMaskIntoConstraints = false
        cancelButton.addTarget(context.coordinator, action: #selector(Coordinator.cancel), for: .touchUpInside)
        bottomBar.addSubview(cancelButton)

        NSLayoutConstraint.activate([
            bottomBar.leadingAnchor.constraint(equalTo: overlay.leadingAnchor),
            bottomBar.trailingAnchor.constraint(equalTo: overlay.trailingAnchor),
            bottomBar.bottomAnchor.constraint(equalTo: overlay.bottomAnchor),
            bottomBar.heightAnchor.constraint(equalToConstant: 140),

            shutterOuter.centerXAnchor.constraint(equalTo: bottomBar.centerXAnchor),
            shutterOuter.centerYAnchor.constraint(equalTo: bottomBar.topAnchor, constant: 50),
            shutterOuter.widthAnchor.constraint(equalToConstant: 74),
            shutterOuter.heightAnchor.constraint(equalToConstant: 74),

            shutterInner.centerXAnchor.constraint(equalTo: shutterOuter.centerXAnchor),
            shutterInner.centerYAnchor.constraint(equalTo: shutterOuter.centerYAnchor),
            shutterInner.widthAnchor.constraint(equalToConstant: 64),
            shutterInner.heightAnchor.constraint(equalToConstant: 64),

            shutterButton.leadingAnchor.constraint(equalTo: shutterOuter.leadingAnchor),
            shutterButton.trailingAnchor.constraint(equalTo: shutterOuter.trailingAnchor),
            shutterButton.topAnchor.constraint(equalTo: shutterOuter.topAnchor),
            shutterButton.bottomAnchor.constraint(equalTo: shutterOuter.bottomAnchor),

            cancelButton.leadingAnchor.constraint(equalTo: bottomBar.leadingAnchor, constant: 20),
            cancelButton.centerYAnchor.constraint(equalTo: shutterOuter.centerYAnchor),
        ])

        picker.cameraOverlayView = overlay
        context.coordinator.picker = picker

        return picker
    }

    func updateUIViewController(_ uiViewController: UIImagePickerController, context: Context) {}

    func makeCoordinator() -> Coordinator {
        Coordinator(self)
    }

    class Coordinator: NSObject, UIImagePickerControllerDelegate, UINavigationControllerDelegate {
        let parent: CameraView
        weak var picker: UIImagePickerController?

        init(_ parent: CameraView) {
            self.parent = parent
        }

        @objc func capture() {
            picker?.takePicture()
        }

        @objc func cancel() {
            parent.dismiss()
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

    private var servingCalorieText: String {
        if let grams = entry.servingSizeGrams {
            let formatted = grams == grams.rounded() ? "\(Int(grams))" : String(format: "%.1f", grams)
            return "\(formatted)g · \(entry.calories) cal"
        }
        return "\(entry.calories) cal"
    }

    var body: some View {
        HStack(spacing: 12) {
            if let imageData = entry.imageData, let uiImage = UIImage(data: imageData) {
                Image(uiImage: uiImage)
                    .resizable()
                    .scaledToFill()
                    .frame(width: 64, height: 64)
                    .clipShape(RoundedRectangle(cornerRadius: 10))
            } else if let emoji = entry.emoji {
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
                HStack {
                    Text(entry.name)
                        .fontWeight(.medium)
                    Spacer()
                    Text(entry.timeString)
                        .font(.caption)
                        .foregroundStyle(.tertiary)
                }

                Text(servingCalorieText)
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

// MARK: - Progress Tab
struct ProgressTabView: View {
    @Environment(FoodStore.self) private var foodStore
    @Environment(WeightStore.self) private var weightStore
    @State private var timeRange: TimeRange = .week
    @State private var showLogWeight = false
    @State private var showGoalReached = false

    private var userProfile: UserProfile { UserProfile.load() ?? .default }

    private var dateRange: ClosedRange<Date> { timeRange.dateRange() }

    private var filteredWeightEntries: [WeightEntry] {
        weightStore.entries(in: dateRange)
    }

    private var dailyCalories: [(date: Date, calories: Int)] {
        let calendar = Calendar.current
        let days = timeRange.days
        let today = calendar.startOfDay(for: .now)
        return (0..<days).compactMap { offset in
            guard let date = calendar.date(byAdding: .day, value: -offset, to: today) else { return nil }
            let cals = foodStore.calories(for: date)
            if cals == 0 { return nil }
            return (date: date, calories: cals)
        }.reversed()
    }

    private var macroAverages: (protein: Int, carbs: Int, fat: Int) {
        let calendar = Calendar.current
        let days = timeRange.days
        let today = calendar.startOfDay(for: .now)
        var totalP = 0, totalC = 0, totalF = 0, count = 0
        for offset in 0..<days {
            guard let date = calendar.date(byAdding: .day, value: -offset, to: today) else { continue }
            let dayEntries = foodStore.entries(for: date)
            if dayEntries.isEmpty { continue }
            totalP += dayEntries.reduce(0) { $0 + $1.protein }
            totalC += dayEntries.reduce(0) { $0 + $1.carbs }
            totalF += dayEntries.reduce(0) { $0 + $1.fat }
            count += 1
        }
        guard count > 0 else { return (0, 0, 0) }
        return (totalP / count, totalC / count, totalF / count)
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 16) {
                    // Segmented Picker
                    Picker("Time Range", selection: $timeRange) {
                        ForEach(TimeRange.allCases, id: \.self) { range in
                            Text(range.rawValue).tag(range)
                        }
                    }
                    .pickerStyle(.segmented)
                    .padding(.horizontal)

                    // Weight Trend
                    WeightChartSection(
                        weightEntries: filteredWeightEntries,
                        goalWeightKg: userProfile.goalWeightKg,
                        currentWeightKg: weightStore.latestEntry?.weightKg,
                        onLogWeight: { showLogWeight = true }
                    )
                    .padding(.horizontal)

                    // Calorie Trend
                    CalorieChartSection(
                        dailyCalories: dailyCalories,
                        calorieGoal: userProfile.effectiveCalories
                    )
                    .padding(.horizontal)

                    // Macro Averages
                    MacroAveragesSection(
                        avgProtein: macroAverages.protein,
                        avgCarbs: macroAverages.carbs,
                        avgFat: macroAverages.fat,
                        proteinGoal: userProfile.effectiveProtein,
                        carbsGoal: userProfile.effectiveCarbs,
                        fatGoal: userProfile.effectiveFat
                    )
                    .padding(.horizontal)


                }
                .padding(.vertical)
            }
            .background(AppColors.appBackground)
            .navigationBarHidden(true)
            .sheet(isPresented: $showLogWeight) {
                LogWeightSheet(
                    currentWeightKg: weightStore.latestEntry?.weightKg ?? userProfile.weightKg
                ) { weightKg in
                    let entry = WeightEntry(weightKg: weightKg)
                    weightStore.addEntry(entry)
                    // Check if goal weight reached
                    if let goalKg = userProfile.goalWeightKg {
                        let reached: Bool
                        switch userProfile.goal {
                        case .lose: reached = weightKg <= goalKg
                        case .gain: reached = weightKg >= goalKg
                        case .maintain: reached = false
                        }
                        if reached { showGoalReached = true }
                    }
                }
            }
            .alert("Congratulations!", isPresented: $showGoalReached) {
                Button("Switch to Maintain") {
                    var profile = userProfile
                    profile.goal = .maintain
                    profile.weeklyChangeKg = nil
                    profile.goalWeightKg = nil
                    profile.customCalories = nil
                    profile.customProtein = nil
                    profile.customCarbs = nil
                    profile.customFat = nil
                    profile.save()
                }
                Button("Keep Going", role: .cancel) { }
            } message: {
                Text("You've reached your goal weight! Would you like to switch to maintenance?")
            }
        }
    }
}


struct ProfileView: View {
    @Environment(WeightStore.self) private var weightStore
    @Environment(FoodStore.self) private var foodStore
    @Environment(AuthManager.self) private var authManager
    @Environment(NotificationManager.self) private var notificationManager
    @Environment(HealthKitManager.self) private var healthKitManager
    @Environment(StoreManager.self) private var storeManager
    @State private var profile: UserProfile = UserProfile.load() ?? .default
    @AppStorage("appearanceMode") private var appearanceMode = "system"
    @AppStorage("useMetric") private var useMetric = false
    @AppStorage("hasCompletedOnboarding") private var hasCompletedOnboarding = true
    @AppStorage("healthKitEnabled") private var healthKitEnabled = false

    enum ActiveSheet: String, Identifiable {
        case editName, editBirthday, editHeight, editWeight, editBodyFat, editGoalWeight, editCalories, editProtein, editCarbs, editFat, paywall
        var id: String { rawValue }
    }
    @State private var activeSheet: ActiveSheet?
    @State private var showDeleteConfirmation = false
    @State private var editingName: String = ""
    @State private var isSyncing = false
    @State private var isSigningOut = false
    @State private var signInError: String?

    // Height formatting
    private var heightDisplay: String {
        if useMetric {
            return "\(Int(profile.heightCm)) cm"
        }
        let totalInches = profile.heightCm / 2.54
        let feet = Int(totalInches) / 12
        let inches = Int(totalInches) % 12
        return "\(feet)'\(inches)\""
    }

    // Weight formatting
    private var weightDisplay: String {
        if useMetric {
            return String(format: "%.1f kg", profile.weightKg)
        }
        return String(format: "%.1f lbs", profile.weightKg * 2.20462)
    }

    // Birthday formatting
    private var birthdayDisplay: String {
        let formatter = DateFormatter()
        formatter.dateStyle = .medium
        return "\(formatter.string(from: profile.birthday)) (age \(profile.age))"
    }

    // Goal weight display
    private var goalWeightDisplay: String {
        guard let gw = profile.goalWeightKg else { return "Not set" }
        if useMetric {
            return String(format: "%.1f kg", gw)
        }
        return String(format: "%.1f lbs", gw * 2.20462)
    }

    // Weekly change display
    private var weeklyChangeDisplay: String {
        let rate = profile.weeklyChangeKg ?? 0.5
        if useMetric {
            return String(format: "%.2f kg/week", rate)
        }
        return String(format: "%.1f lbs/week", rate * 2.20462)
    }

    var body: some View {
        NavigationStack {
            List {
                // Section 1: Personal Info
                Section("Personal Info") {
                    ProfileInfoRow(icon: "person", label: "Name", value: profile.displayName) {
                        editingName = profile.name ?? ""
                        activeSheet = .editName
                    }

                    Picker(selection: $profile.gender) {
                        Text("Male").tag(Gender.male)
                        Text("Female").tag(Gender.female)
                        Text("Other").tag(Gender.other)
                    } label: {
                        Label {
                            Text("Gender")
                        } icon: {
                            Image(systemName: profile.gender.icon)
                                .foregroundStyle(AppColors.calorie)
                        }
                    }
                    .pickerStyle(.menu)
                    .tint(.secondary)
                    .onChange(of: profile.gender) { _, _ in resetNutritionAndSave() }

                    ProfileInfoRow(icon: "birthday.cake", label: "Birthday", value: birthdayDisplay) {
                        activeSheet = .editBirthday
                    }

                    ProfileInfoRow(icon: "ruler", label: "Height", value: heightDisplay) {
                        activeSheet = .editHeight
                    }

                    ProfileInfoRow(icon: "scalemass", label: "Weight", value: weightDisplay) {
                        activeSheet = .editWeight
                    }

                    ProfileInfoRow(
                        icon: "percent",
                        label: "Body Fat",
                        value: profile.bodyFatPercentage != nil ? "\(Int(profile.bodyFatPercentage! * 100))%" : "Not set"
                    ) {
                        activeSheet = .editBodyFat
                    }
                }
                .listRowBackground(AppColors.appCard)

                // Section 2: Goals & Nutrition
                Section("Goals & Nutrition") {
                    Picker(selection: $profile.goal) {
                        ForEach(WeightGoal.allCases, id: \.self) { goal in
                            Text(goal.displayName).tag(goal)
                        }
                    } label: {
                        Label {
                            Text("Weight Goal")
                        } icon: {
                            Image(systemName: profile.goal.icon)
                                .foregroundStyle(AppColors.calorie)
                        }
                    }
                    .pickerStyle(.menu)
                    .tint(.secondary)
                    .onChange(of: profile.goal) { _, newValue in
                        if newValue == .maintain {
                            profile.weeklyChangeKg = nil
                            profile.goalWeightKg = nil
                        } else if profile.weeklyChangeKg == nil {
                            profile.weeklyChangeKg = 0.5
                        }
                        resetNutritionAndSave()
                    }

                    Picker(selection: $profile.activityLevel) {
                        ForEach(ActivityLevel.allCases, id: \.self) { level in
                            Text(level.displayName).tag(level)
                        }
                    } label: {
                        Label {
                            Text("Activity Level")
                        } icon: {
                            Image(systemName: profile.activityLevel.icon)
                                .foregroundStyle(AppColors.calorie)
                        }
                    }
                    .pickerStyle(.menu)
                    .tint(.secondary)
                    .onChange(of: profile.activityLevel) { _, _ in resetNutritionAndSave() }

                    if profile.goal != .maintain {
                        Picker(selection: Binding(
                            get: { profile.weeklyChangeKg ?? 0.5 },
                            set: { profile.weeklyChangeKg = $0; resetNutritionAndSave() }
                        )) {
                            Text("Slow (0.25 kg/wk)").tag(0.25)
                            Text("Moderate (0.5 kg/wk)").tag(0.5)
                            Text("Fast (1.0 kg/wk)").tag(1.0)
                        } label: {
                            Label {
                                Text("Weekly Change")
                            } icon: {
                                Image(systemName: "gauge.with.dots.needle.33percent")
                                    .foregroundStyle(AppColors.calorie)
                            }
                        }
                        .pickerStyle(.menu)
                        .tint(.secondary)

                        ProfileInfoRow(
                            icon: "flag.checkered",
                            label: "Goal Weight",
                            value: goalWeightDisplay
                        ) {
                            activeSheet = .editGoalWeight
                        }
                    }

                    ProfileInfoRow(icon: "flame", label: "Calories", value: "\(profile.effectiveCalories) kcal") {
                        activeSheet = .editCalories
                    }

                    ProfileInfoRow(icon: "p.circle", label: "Protein", value: "\(profile.effectiveProtein)g") {
                        activeSheet = .editProtein
                    }

                    ProfileInfoRow(icon: "c.circle", label: "Carbs", value: "\(profile.effectiveCarbs)g") {
                        activeSheet = .editCarbs
                    }

                    ProfileInfoRow(icon: "f.circle", label: "Fat", value: "\(profile.effectiveFat)g") {
                        activeSheet = .editFat
                    }
                }
                .listRowBackground(AppColors.appCard)

                // Section 3: App Settings
                Section("App Settings") {
                    Picker(selection: $appearanceMode) {
                        Text("System").tag("system")
                        Text("Light").tag("light")
                        Text("Dark").tag("dark")
                    } label: {
                        Label {
                            Text("Appearance")
                        } icon: {
                            Image(systemName: "circle.lefthalf.filled")
                                .foregroundStyle(AppColors.calorie)
                        }
                    }
                    .pickerStyle(.menu)
                    .tint(.secondary)

                    Toggle(isOn: $useMetric) {
                        Label {
                            Text("Metric Units")
                        } icon: {
                            Image(systemName: "ruler")
                                .foregroundStyle(AppColors.calorie)
                        }
                    }
                    .tint(AppColors.calorie)

                    NavigationLink {
                        NotificationSettingsView()
                    } label: {
                        Label {
                            Text("Notifications")
                        } icon: {
                            Image(systemName: "bell")
                                .foregroundStyle(AppColors.calorie)
                        }
                    }
                }
                .listRowBackground(AppColors.appCard)

                // Section 4: Subscription
                Section("Subscription") {
                    if storeManager.isSubscribed {
                        HStack {
                            Label {
                                Text(storeManager.currentPlanName)
                            } icon: {
                                Image(systemName: "star.fill")
                                    .foregroundStyle(AppColors.calorie)
                            }
                            Spacer()
                            Text("Active")
                                .font(.system(.caption, design: .rounded, weight: .medium))
                                .foregroundStyle(.green)
                        }

                        HStack {
                            Label {
                                Text("Scans Today")
                            } icon: {
                                Image(systemName: "camera.fill")
                                    .foregroundStyle(AppColors.calorie)
                            }
                            Spacer()
                            Text("\(storeManager.remainingScans) of 25 remaining")
                                .font(.system(.caption, design: .rounded))
                                .foregroundStyle(.secondary)
                        }

                        Button {
                            Task {
                                if let windowScene = UIApplication.shared.connectedScenes
                                    .compactMap({ $0 as? UIWindowScene }).first {
                                    try? await AppStore.showManageSubscriptions(in: windowScene)
                                }
                            }
                        } label: {
                            Label {
                                Text("Manage Subscription")
                            } icon: {
                                Image(systemName: "gear")
                                    .foregroundStyle(AppColors.calorie)
                            }
                        }
                        .buttonStyle(.plain)
                    } else {
                        HStack {
                            Label {
                                Text("Free Plan")
                            } icon: {
                                Image(systemName: "star")
                                    .foregroundStyle(.secondary)
                            }
                            Spacer()
                            Text("\(storeManager.remainingScans) of 3 scans left")
                                .font(.system(.caption, design: .rounded))
                                .foregroundStyle(.secondary)
                        }

                        Button {
                            activeSheet = .paywall
                        } label: {
                            Label {
                                Text("Upgrade to Premium")
                            } icon: {
                                Image(systemName: "star.fill")
                                    .foregroundStyle(AppColors.calorie)
                            }
                        }
                    }

                    Button {
                        Task { await storeManager.restorePurchases() }
                    } label: {
                        Label {
                            Text("Restore Purchases")
                        } icon: {
                            Image(systemName: "arrow.clockwise")
                                .foregroundStyle(AppColors.calorie)
                        }
                    }
                    .buttonStyle(.plain)
                }
                .listRowBackground(AppColors.appCard)

                // Section 5: Account
                Section("Account") {
                    if authManager.isSignedIn {
                        // Apple ID info
                        HStack(spacing: 12) {
                            Image(systemName: "apple.logo")
                                .font(.system(size: 20))
                                .foregroundStyle(AppColors.calorie)
                                .frame(width: 28)
                            VStack(alignment: .leading, spacing: 2) {
                                Text(authManager.userDisplayName ?? "Apple ID")
                                    .font(.system(.body, design: .rounded, weight: .medium))
                                if let email = authManager.userEmail {
                                    Text(email)
                                        .font(.system(.caption, design: .rounded))
                                        .foregroundStyle(.secondary)
                                }
                            }
                            Spacer()
                            Image(systemName: "checkmark.circle.fill")
                                .foregroundStyle(.green)
                                .font(.system(size: 20))
                        }

                        // Sync to iCloud
                        Button {
                            isSyncing = true
                            Task {
                                await CloudKitService.pushAllData(
                                    foodEntries: foodStore.entries,
                                    weightEntries: weightStore.entries,
                                    profile: UserProfile.load()
                                )
                                isSyncing = false
                            }
                        } label: {
                            HStack {
                                Label {
                                    Text("Sync to iCloud")
                                } icon: {
                                    Image(systemName: "icloud.and.arrow.up")
                                        .foregroundStyle(AppColors.calorie)
                                }
                                Spacer()
                                if isSyncing || isSigningOut {
                                    ProgressView()
                                        .tint(AppColors.calorie)
                                }
                            }
                        }
                        .buttonStyle(.plain)
                        .disabled(isSyncing || isSigningOut)

                        // Apple Health
                        HStack {
                            Label {
                                Text("Apple Health")
                            } icon: {
                                Image(systemName: "heart.fill")
                                    .foregroundStyle(.pink)
                            }
                            Spacer()
                            Toggle("", isOn: $healthKitEnabled)
                                .labelsHidden()
                                .onChange(of: healthKitEnabled) { _, enabled in
                                    handleHealthKitToggle(enabled)
                                }
                        }

                        // Contact
                        Link(destination: URL(string: "mailto:ad13dtu@gmail.com")!) {
                            Label {
                                Text("Contact Us")
                            } icon: {
                                Image(systemName: "envelope.fill")
                                    .foregroundStyle(AppColors.calorie)
                            }
                        }
                        .buttonStyle(.plain)

                        // Privacy Policy
                        Link(destination: URL(string: "https://fud-ai.vercel.app/privacy.html")!) {
                            Label {
                                Text("Privacy Policy")
                            } icon: {
                                Image(systemName: "lock.shield.fill")
                                    .foregroundStyle(AppColors.calorie)
                            }
                        }
                        .buttonStyle(.plain)

                        // Terms of Service
                        Link(destination: URL(string: "https://fud-ai.vercel.app/terms.html")!) {
                            Label {
                                Text("Terms of Service")
                            } icon: {
                                Image(systemName: "doc.text.fill")
                                    .foregroundStyle(AppColors.calorie)
                            }
                        }
                        .buttonStyle(.plain)

                        // Sign Out
                        Button {
                            isSigningOut = true
                            Task {
                                await CloudKitService.pushAllData(
                                    foodEntries: foodStore.entries,
                                    weightEntries: weightStore.entries,
                                    profile: UserProfile.load()
                                )
                                isSigningOut = false
                                authManager.signOut()
                                hasCompletedOnboarding = false
                            }
                        } label: {
                            HStack {
                                Label {
                                    Text("Sign Out")
                                } icon: {
                                    Image(systemName: "rectangle.portrait.and.arrow.right")
                                        .foregroundStyle(AppColors.calorie)
                                }
                                Spacer()
                                if isSigningOut {
                                    ProgressView()
                                        .tint(AppColors.calorie)
                                }
                            }
                        }
                        .buttonStyle(.plain)
                        .disabled(isSyncing || isSigningOut)
                    } else {
                        // Apple Health
                        HStack {
                            Label {
                                Text("Apple Health")
                            } icon: {
                                Image(systemName: "heart.fill")
                                    .foregroundStyle(.pink)
                            }
                            Spacer()
                            Toggle("", isOn: $healthKitEnabled)
                                .labelsHidden()
                                .onChange(of: healthKitEnabled) { _, enabled in
                                    handleHealthKitToggle(enabled)
                                }
                        }
                    }

                    // Delete All Data — always visible
                    Button(role: .destructive) {
                        showDeleteConfirmation = true
                    } label: {
                        Label {
                            Text("Delete All Data")
                        } icon: {
                            Image(systemName: "trash")
                        }
                        .foregroundStyle(.red)
                    }
                    .buttonStyle(.plain)
                }
                .listRowBackground(AppColors.appCard)
            }
            .scrollContentBackground(.hidden)
            .background(AppColors.appBackground)
            .navigationBarHidden(true)
            .onAppear {
                profile = UserProfile.load() ?? .default
                // Auto-populate name from Apple Sign-In if not yet set
                if (profile.name == nil || profile.name?.isEmpty == true),
                   let appleName = authManager.userDisplayName, !appleName.isEmpty {
                    profile.name = appleName
                    saveProfile()
                }
            }
            .sheet(item: $activeSheet) { sheet in
                switch sheet {
                case .editName:
                    NavigationStack {
                        Form {
                            TextField("Your name", text: $editingName)
                                .textContentType(.name)
                                .autocorrectionDisabled()
                        }
                        .navigationTitle("Edit Name")
                        .navigationBarTitleDisplayMode(.inline)
                        .toolbar {
                            ToolbarItem(placement: .cancellationAction) {
                                Button("Cancel") { activeSheet = nil }
                            }
                            ToolbarItem(placement: .confirmationAction) {
                                Button("Save") {
                                    profile.name = editingName.isEmpty ? nil : editingName
                                    saveProfile()
                                    activeSheet = nil
                                }
                            }
                        }
                    }
                    .presentationDetents([.medium])

                case .editBirthday:
                    NavigationStack {
                        VStack(spacing: 20) {
                            Text("Birthday")
                                .font(.system(.title2, design: .rounded, weight: .bold))

                            DatePicker(
                                "Birthday",
                                selection: $profile.birthday,
                                in: ...Date.now,
                                displayedComponents: .date
                            )
                            .datePickerStyle(.wheel)
                            .labelsHidden()

                            Button {
                                resetNutritionAndSave()
                                activeSheet = nil
                            } label: {
                                Text("Save")
                                    .font(.system(.headline, design: .rounded, weight: .semibold))
                                    .frame(maxWidth: .infinity)
                                    .padding(.vertical, 14)
                                    .background(
                                        LinearGradient(colors: AppColors.calorieGradient, startPoint: .leading, endPoint: .trailing)
                                    )
                                    .foregroundStyle(.white)
                                    .clipShape(RoundedRectangle(cornerRadius: 14))
                            }
                            .padding(.horizontal, 24)

                            Spacer()
                        }
                        .padding(.top, 24)
                        .toolbar {
                            ToolbarItem(placement: .cancellationAction) {
                                Button("Cancel") { activeSheet = nil }
                            }
                        }
                    }
                    .presentationDetents([.medium])

                case .editHeight:
                    HeightPickerSheet(
                        useMetric: useMetric,
                        currentHeightCm: profile.heightCm
                    ) { newHeight in
                        profile.heightCm = newHeight
                        resetNutritionAndSave()
                    }

                case .editWeight:
                    WeightPickerSheet(
                        useMetric: useMetric,
                        currentWeightKg: profile.weightKg
                    ) { newWeight in
                        profile.weightKg = newWeight
                        resetNutritionAndSave()
                        weightStore.addEntry(WeightEntry(weightKg: newWeight))
                    }

                case .editBodyFat:
                    BodyFatPickerSheet(
                        currentPercentage: profile.bodyFatPercentage
                    ) { newValue in
                        profile.bodyFatPercentage = newValue
                        resetNutritionAndSave()
                    }

                case .editGoalWeight:
                    WeightPickerSheet(
                        useMetric: useMetric,
                        currentWeightKg: profile.goalWeightKg ?? profile.weightKg
                    ) { newGoalWeight in
                        profile.goalWeightKg = newGoalWeight
                        saveProfile()
                    }

                case .editCalories:
                    NutritionPickerSheet(label: "Calories", unit: "kcal", currentValue: profile.effectiveCalories, range: 800...6000, step: 50) { value in
                        profile.customCalories = value
                        // Auto-adjust carbs: carbs = (cal - protein*4 - fat*9) / 4
                        let newCarbs = max(0, (value - profile.effectiveProtein * 4 - profile.effectiveFat * 9) / 4)
                        profile.customCarbs = newCarbs
                        saveProfile()
                    }

                case .editProtein:
                    NutritionPickerSheet(label: "Protein", unit: "g", currentValue: profile.effectiveProtein, range: 10...500, step: 5) { value in
                        profile.customProtein = value
                        // Auto-adjust carbs
                        let newCarbs = max(0, (profile.effectiveCalories - value * 4 - profile.effectiveFat * 9) / 4)
                        profile.customCarbs = newCarbs
                        saveProfile()
                    }

                case .editCarbs:
                    NutritionPickerSheet(label: "Carbs", unit: "g", currentValue: profile.effectiveCarbs, range: 0...800, step: 5) { value in
                        profile.customCarbs = value
                        // Auto-adjust calories: cal = protein*4 + carbs*4 + fat*9
                        let newCal = profile.effectiveProtein * 4 + value * 4 + profile.effectiveFat * 9
                        profile.customCalories = newCal
                        saveProfile()
                    }

                case .editFat:
                    NutritionPickerSheet(label: "Fat", unit: "g", currentValue: profile.effectiveFat, range: 10...300, step: 5) { value in
                        profile.customFat = value
                        // Auto-adjust carbs
                        let newCarbs = max(0, (profile.effectiveCalories - profile.effectiveProtein * 4 - value * 9) / 4)
                        profile.customCarbs = newCarbs
                        saveProfile()
                    }

                case .paywall:
                    PaywallView()
                        .environment(storeManager)
                }
            }
            .alert("Delete All Data", isPresented: $showDeleteConfirmation) {
                Button("Cancel", role: .cancel) { }
                Button("Delete Everything", role: .destructive) {
                    // Clear in-memory stores
                    foodStore.replaceAllEntries([])
                    weightStore.replaceAllEntries([])
                    // Cancel all notifications
                    notificationManager.cancelAllNotifications()
                    // Delete from iCloud
                    Task { await CloudKitService.deleteAllData() }
                    // Wipe all auth credentials
                    authManager.deleteAllCredentials()
                    // Wipe all persisted data
                    let domain = Bundle.main.bundleIdentifier ?? ""
                    UserDefaults.standard.removePersistentDomain(forName: domain)
                    // Re-check subscription — Apple manages this, not UserDefaults
                    Task { await storeManager.checkEntitlements() }
                    hasCompletedOnboarding = false
                }
            } message: {
                Text("This will permanently delete all your data including food logs, weight entries, and profile. This action cannot be undone.")
            }
        }
    }

    private func saveProfile() {
        profile.save()
    }

    private func handleHealthKitToggle(_ enabled: Bool) {
        if enabled {
            Task {
                let authorized = await healthKitManager.requestAuthorization()
                if authorized {
                    healthKitManager.writeWeight(kg: profile.weightKg, date: .now)
                    healthKitManager.writeHeight(cm: profile.heightCm)
                    if let bf = profile.bodyFatPercentage {
                        healthKitManager.writeBodyFat(fraction: bf)
                    }
                    let measurements = await healthKitManager.fetchLatestBodyMeasurements()
                    if let kg = measurements.weight, abs(profile.weightKg - kg) > 0.01 {
                        profile.weightKg = kg
                    }
                    if let cm = measurements.height, abs(profile.heightCm - cm) > 0.1 {
                        profile.heightCm = cm
                    }
                    if let bf = measurements.bodyFat {
                        profile.bodyFatPercentage = bf
                    }
                    if let dob = measurements.dob {
                        profile.birthday = dob
                    }
                    if let sex = measurements.sex {
                        switch sex {
                        case .male: profile.gender = .male
                        case .female: profile.gender = .female
                        default: break
                        }
                    }
                    saveProfile()
                    healthKitManager.startBodyMeasurementObserver()
                } else {
                    healthKitEnabled = false
                }
            }
        } else {
            healthKitManager.stopObserver()
        }
    }

    /// Clear custom nutrition overrides so computed values recalculate from formulas
    private func resetNutritionAndSave() {
        profile.customCalories = nil
        profile.customProtein = nil
        profile.customCarbs = nil
        profile.customFat = nil
        profile.save()
    }
}

#Preview {
    ContentView()
        .environment(FoodStore())
        .environment(WeightStore())
        .environment(AuthManager())
}
