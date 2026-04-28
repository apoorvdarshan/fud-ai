import SwiftUI
import PhotosUI
import UIKit
import HealthKit
import StoreKit
import WidgetKit

// MARK: - Camera Mode
enum CameraMode {
    case snapFood
    case snapFoodWithContext
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

            ChatView()
                .tabItem {
                    Image(systemName: "bubble.left.and.bubble.right.fill")
                    Text("Coach")
                }

            ProfileView()
                .tabItem {
                    Image(systemName: "gearshape.fill")
                    Text("Settings")
                }

            AboutView()
                .tabItem {
                    Image(systemName: "info.circle.fill")
                    Text("About")
                }
        }
        .tint(AppColors.calorie)
    }
}

// MARK: - About View
struct AboutView: View {
    @State private var showShareSheet = false

    private var shareMessage: String {
        String(localized: "I've been tracking my meals with Fud AI — snap a photo, speak it, or type it, and the AI logs the calories. It's free, open source, and your data stays on your device.\n\nDownload: https://fud-ai.app")
    }
    private let appStoreURL = URL(string: "https://apps.apple.com/us/app/fud-ai-calorie-tracker/id6758935726")!

    var body: some View {
        NavigationStack {
            List {
                Section {
                    // Rate the App
                    Button {
                        requestNativeReview()
                    } label: {
                        Label {
                            Text("Rate the App")
                        } icon: {
                            Image(systemName: "star.fill")
                                .foregroundStyle(AppColors.calorie)
                        }
                    }
                    .tint(.primary)

                    // Share the App — uses UIActivityViewController so both
                    // the personalized message AND the App Store URL get
                    // forwarded to every share target (SwiftUI ShareLink
                    // drops the message arg for most targets).
                    Button {
                        showShareSheet = true
                    } label: {
                        Label {
                            Text("Share the App")
                        } icon: {
                            Image(systemName: "square.and.arrow.up.fill")
                                .foregroundStyle(AppColors.calorie)
                        }
                    }
                    .tint(.primary)

                    // Open Source
                    Link(destination: URL(string: "https://github.com/apoorvdarshan/fud-ai")!) {
                        Label {
                            Text("Open Source (MIT)")
                        } icon: {
                            Image(systemName: "chevron.left.forwardslash.chevron.right")
                                .foregroundStyle(AppColors.calorie)
                        }
                    }
                    .tint(.primary)

                    // Star the Repo
                    Link(destination: URL(string: "https://github.com/apoorvdarshan/fud-ai")!) {
                        Label {
                            Text("Star on GitHub")
                        } icon: {
                            Image(systemName: "star.circle.fill")
                                .foregroundStyle(AppColors.calorie)
                        }
                    }
                    .tint(.primary)

                    // Vote on Product Hunt
                    Link(destination: URL(string: "https://www.producthunt.com/products/fud-ai-calorie-tracker")!) {
                        Label {
                            Text("Vote on Product Hunt")
                        } icon: {
                            Image(systemName: "hand.thumbsup.fill")
                                .foregroundStyle(AppColors.calorie)
                        }
                    }
                    .tint(.primary)

                    // Support the Project
                    Link(destination: URL(string: "https://paypal.me/apoorvdarshan")!) {
                        Label {
                            Text("Support the Project")
                        } icon: {
                            Image(systemName: "heart.fill")
                                .foregroundStyle(AppColors.calorie)
                        }
                    }
                    .tint(.primary)

                    // Report an Issue
                    Link(destination: URL(string: "https://github.com/apoorvdarshan/fud-ai/issues/new?labels=bug&title=Bug:%20")!) {
                        Label {
                            Text("Report an Issue")
                        } icon: {
                            Image(systemName: "exclamationmark.bubble.fill")
                                .foregroundStyle(AppColors.calorie)
                        }
                    }
                    .tint(.primary)

                    // Request a Feature
                    Link(destination: URL(string: "https://github.com/apoorvdarshan/fud-ai/issues/new?labels=enhancement&title=Feature:%20")!) {
                        Label {
                            Text("Request a Feature")
                        } icon: {
                            Image(systemName: "lightbulb.fill")
                                .foregroundStyle(AppColors.calorie)
                        }
                    }
                    .tint(.primary)

                    // Contact
                    Link(destination: URL(string: "mailto:apoorv@fud-ai.app")!) {
                        Label {
                            Text("Contact Us")
                        } icon: {
                            Image(systemName: "envelope.fill")
                                .foregroundStyle(AppColors.calorie)
                        }
                    }
                    .tint(.primary)

                    // Follow on X
                    Link(destination: URL(string: "https://x.com/apoorvdarshan")!) {
                        Label {
                            Text("Follow on X")
                        } icon: {
                            Image(systemName: "at")
                                .foregroundStyle(AppColors.calorie)
                        }
                    }
                    .tint(.primary)

                    // LinkedIn
                    Link(destination: URL(string: "https://www.linkedin.com/company/fud-ai-app")!) {
                        Label {
                            Text("Follow on LinkedIn")
                        } icon: {
                            Image(systemName: "briefcase.fill")
                                .foregroundStyle(AppColors.calorie)
                        }
                    }
                    .tint(.primary)
                }
                .listRowBackground(AppColors.appCard)

                Section {
                    // Privacy Policy
                    Link(destination: URL(string: "https://fud-ai.app/privacy.html")!) {
                        Label {
                            Text("Privacy Policy")
                        } icon: {
                            Image(systemName: "lock.shield.fill")
                                .foregroundStyle(AppColors.calorie)
                        }
                    }
                    .tint(.primary)

                    // Terms of Service
                    Link(destination: URL(string: "https://fud-ai.app/terms.html")!) {
                        Label {
                            Text("Terms of Service")
                        } icon: {
                            Image(systemName: "doc.text.fill")
                                .foregroundStyle(AppColors.calorie)
                        }
                    }
                    .tint(.primary)
                }
                .listRowBackground(AppColors.appCard)

                Section {
                    VStack(spacing: 4) {
                        Text("Made by Apoorv Darshan")
                            .font(.system(.footnote, design: .rounded, weight: .medium))
                            .foregroundStyle(.secondary)
                        Text("with care, for everyone")
                            .font(.system(.caption2, design: .rounded))
                            .foregroundStyle(.tertiary)
                    }
                    .frame(maxWidth: .infinity)
                    .listRowBackground(Color.clear)
                    .listRowSeparator(.hidden)
                }
            }
            .scrollContentBackground(.hidden)
            .background(AppColors.appBackground)
            .navigationBarHidden(true)
            .sheet(isPresented: $showShareSheet) {
                ActivityShareSheet(activityItems: [shareMessage, appStoreURL])
            }
        }
    }

    private func requestNativeReview() {
        if let scene = UIApplication.shared.connectedScenes
            .first(where: { $0.activationState == .foregroundActive }) as? UIWindowScene {
            AppStore.requestReview(in: scene)
        }
    }
}

// MARK: - Share Sheet wrapper (UIActivityViewController)
// Used by AboutView so the personalized message AND the App Store URL
// both reach every share target. SwiftUI's ShareLink message arg is
// dropped by most targets; UIActivityViewController forwards every item.
struct ActivityShareSheet: UIViewControllerRepresentable {
    let activityItems: [Any]
    var applicationActivities: [UIActivity]? = nil

    func makeUIViewController(context: Context) -> UIActivityViewController {
        UIActivityViewController(activityItems: activityItems, applicationActivities: applicationActivities)
    }

    func updateUIViewController(_ uiViewController: UIActivityViewController, context: Context) {}
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
    @State private var showVoicePopover = false
    @State private var showTextPopover = false
    @State private var showManualPopover = false
    @State private var showRecentSheet = false
    @State private var pendingContextImage: UIImage?
    @State private var contextDescription: String = ""
    @State private var showContextSheet = false

    enum ActiveSheet: String, Identifiable {
        case analyzing, foodResult, analyzingText, editFood
        var id: String { rawValue }
    }
    @State private var activeSheet: ActiveSheet?
    @State private var editingEntry: FoodEntry?

    @State private var currentFoodResult: GeminiService.FoodAnalysis?
    @State private var currentImage: UIImage?
    @State private var currentEmoji: String?
    @State private var showNutritionDetail = false
    @Environment(ProfileStore.self) private var profileStore

    /// Force a body re-evaluation whenever profileStore.profile changes by reading it
    /// at the top of body. SwiftUI's @Observable tracking sometimes misses the access
    /// when the read is buried in a computed property; explicit access guarantees it.
    private var userProfile: UserProfile { profileStore.profile }
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
        // Explicit observation tracking — reads profileStore.profile at body root
        // so SwiftUI invalidates this view on every profile mutation.
        let _ = profileStore.profile
        return NavigationStack {
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
                                    .swipeActions(edge: .trailing, allowsFullSwipe: true) {
                                        Button(role: .destructive) {
                                            foodStore.deleteEntry(entry)
                                        } label: {
                                            Label("Delete", systemImage: "trash.fill")
                                        }
                                        Button {
                                            foodStore.toggleFavorite(entry)
                                        } label: {
                                            Label(foodStore.isFavorite(entry) ? "Unfavorite" : "Favorite", systemImage: foodStore.isFavorite(entry) ? "heart.slash.fill" : "heart.fill")
                                        }
                                        .tint(AppColors.calorie)
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

                                cameraMode = .snapFood
                                showCamera = true
                            }) {
                                Label("Camera", systemImage: "camera.fill")
                            }
                            Button(action: {

                                cameraMode = .snapFoodWithContext
                                showCamera = true
                            }) {
                                Label("Camera + Note", systemImage: "camera.badge.ellipsis")
                            }
                            Button(action: {

                                cameraMode = .nutritionLabel
                                showCamera = true
                            }) {
                                Label("Nutrition Label", systemImage: "text.viewfinder")
                            }
                            Button(action: {

                                showPhotoPicker = true
                            }) {
                                Label("From Photos", systemImage: "photo.on.rectangle")
                            }
                            Button(action: {

                                showTextPopover = true
                            }) {
                                Label("Text Input", systemImage: "character.cursor.ibeam")
                            }
                            Button(action: {

                                showVoicePopover = true
                            }) {
                                Label("Voice", systemImage: "mic.fill")
                            }
                            Button(action: {

                                showManualPopover = true
                            }) {
                                Label("Manual Entry", systemImage: "square.and.pencil")
                            }
                            Button(action: {

                                showRecentSheet = true
                            }) {
                                Label("Saved Meals", systemImage: "bookmark.fill")
                            }
                        } label: {
                            Image(systemName: "plus")
                        }
                        .popover(isPresented: $showTextPopover) {
                            TextFoodInputView(
                                onCancel: {
                                    showTextPopover = false
                                },
                                onSubmit: { description in
                                    showTextPopover = false
                                    currentImage = nil
                                    currentEmoji = nil
                                    Task {
                                        try? await Task.sleep(for: .milliseconds(300))
                                        activeSheet = .analyzingText
                                        do {
                                            let result = try await GeminiService.analyzeTextInput(description: description)

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
                            )
                            .presentationCompactAdaptation(.popover)
                        }
                        .popover(isPresented: $showVoicePopover) {
                            VoiceInputView(
                                onCancel: {
                                    showVoicePopover = false
                                },
                                onSubmit: { description in
                                    showVoicePopover = false
                                    currentImage = nil
                                    currentEmoji = nil
                                    Task {
                                        try? await Task.sleep(for: .milliseconds(300))
                                        activeSheet = .analyzingText
                                        do {
                                            let result = try await GeminiService.analyzeTextInput(description: description)

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
                            )
                            .presentationCompactAdaptation(.popover)
                        }
                        .popover(isPresented: $showManualPopover) {
                            ManualEntryView(
                                logDate: selectedDate,
                                onCancel: { showManualPopover = false },
                                onSave: { entry in
                                    showManualPopover = false
                                    foodStore.addEntry(entry)
                                }
                            )
                            .presentationCompactAdaptation(.popover)
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
                if cameraMode == .snapFoodWithContext {
                    pendingContextImage = image
                    contextDescription = ""
                    showContextSheet = true
                } else {
                    startAnalysis(image: image, mode: cameraMode)
                }
            }
            .sheet(isPresented: $showContextSheet) {
                ContextDescriptionSheet(
                    image: pendingContextImage,
                    description: $contextDescription,
                    onAnalyze: {
                        let desc = contextDescription
                        let image = pendingContextImage
                        showContextSheet = false
                        pendingContextImage = nil
                        if let image {
                            startAnalysis(image: image, mode: .snapFoodWithContext, description: desc)
                        }
                    },
                    onCancel: {
                        showContextSheet = false
                        pendingContextImage = nil
                        currentImage = nil
                    }
                )
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
                            source: currentImage == nil ? .textInput : (cameraMode == .nutritionLabel ? .nutritionLabel : .snapFood),
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
                }
            }
            .sheet(isPresented: $showRecentSheet, content: {
                RecentsView(logDate: selectedDate, onReview: { entry in
                    if let imageData = entry.imageData, let image = UIImage(data: imageData) {
                        currentImage = image
                    } else {
                        currentImage = nil
                    }
                    currentEmoji = entry.emoji
                    currentFoodResult = GeminiService.FoodAnalysis(
                        name: entry.name,
                        calories: entry.calories,
                        protein: entry.protein,
                        carbs: entry.carbs,
                        fat: entry.fat,
                        servingSizeGrams: entry.servingSizeGrams ?? 100,
                        emoji: entry.emoji,
                        sugar: entry.sugar,
                        addedSugar: entry.addedSugar,
                        fiber: entry.fiber,
                        saturatedFat: entry.saturatedFat,
                        monounsaturatedFat: entry.monounsaturatedFat,
                        polyunsaturatedFat: entry.polyunsaturatedFat,
                        cholesterol: entry.cholesterol,
                        sodium: entry.sodium,
                        potassium: entry.potassium
                    )
                    activeSheet = .foodResult
                })
            })
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
            .sheet(isPresented: $showNutritionDetail) {
                NutritionDetailView(date: selectedDate)
            }
        }
    }


    private func startAnalysis(image: UIImage, mode: CameraMode, description: String? = nil) {
        activeSheet = .analyzing

        Task {
            do {
                switch mode {
                case .snapFood:
                    let result = try await GeminiService.analyzeFood(image: image)
                    currentFoodResult = result
                    activeSheet = .foodResult

                case .snapFoodWithContext:
                    let result = try await GeminiService.analyzeFood(image: image, description: description)
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


// MARK: - Nutrition Detail View
struct NutritionDetailView: View {
    let date: Date
    @Environment(FoodStore.self) private var foodStore
    @Environment(ProfileStore.self) private var profileStore
    @Environment(\.dismiss) private var dismiss

    private var userProfile: UserProfile { profileStore.profile }

    var body: some View {
        let _ = profileStore.profile
        return NavigationStack {
            List {
                Section("Macros") {
                    NutritionDetailRow(icon: "flame.fill", label: "Calories", value: "\(foodStore.calories(for: date))", unit: "kcal", goal: "\(userProfile.effectiveCalories)")
                    NutritionDetailRow(icon: "p.circle.fill", label: "Protein", value: "\(foodStore.protein(for: date))", unit: "g", goal: "\(userProfile.effectiveProtein)")
                    NutritionDetailRow(icon: "c.circle.fill", label: "Carbs", value: "\(foodStore.carbs(for: date))", unit: "g", goal: "\(userProfile.effectiveCarbs)")
                    NutritionDetailRow(icon: "f.circle.fill", label: "Fat", value: "\(foodStore.fat(for: date))", unit: "g", goal: "\(userProfile.effectiveFat)")
                }
                .listRowBackground(AppColors.appCard)

                Section("Detailed Nutrition") {
                    NutritionDetailRow(icon: "cube.fill", label: "Sugar", value: formatMicro(foodStore.sugar(for: date)), unit: "g")
                    NutritionDetailRow(icon: "plus.circle.fill", label: "Added Sugar", value: formatMicro(foodStore.addedSugar(for: date)), unit: "g")
                    NutritionDetailRow(icon: "leaf.fill", label: "Fiber", value: formatMicro(foodStore.fiber(for: date)), unit: "g")
                    NutritionDetailRow(icon: "drop.fill", label: "Saturated Fat", value: formatMicro(foodStore.saturatedFat(for: date)), unit: "g")
                    NutritionDetailRow(icon: "drop", label: "Mono Unsat. Fat", value: formatMicro(foodStore.monounsaturatedFat(for: date)), unit: "g")
                    NutritionDetailRow(icon: "drop.halffull", label: "Poly Unsat. Fat", value: formatMicro(foodStore.polyunsaturatedFat(for: date)), unit: "g")
                    NutritionDetailRow(icon: "heart.circle.fill", label: "Cholesterol", value: formatMicro(foodStore.cholesterol(for: date)), unit: "mg")
                    NutritionDetailRow(icon: "sparkles", label: "Sodium", value: formatMicro(foodStore.sodium(for: date)), unit: "mg")
                    NutritionDetailRow(icon: "bolt.fill", label: "Potassium", value: formatMicro(foodStore.potassium(for: date)), unit: "mg")
                }
                .listRowBackground(AppColors.appCard)
            }
            .scrollContentBackground(.hidden)
            .background(AppColors.appBackground)
            .navigationTitle("Nutrition Details")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") { dismiss() }
                        .tint(AppColors.calorie)
                }
            }
        }
    }

    private func formatMicro(_ value: Double) -> String {
        value == 0 ? "—" : String(format: "%.1f", value)
    }
}

struct NutritionDetailRow: View {
    var icon: String? = nil
    let label: String
    let value: String
    let unit: String
    var goal: String? = nil

    var body: some View {
        HStack(spacing: 12) {
            if let icon {
                Image(systemName: icon)
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundStyle(
                        LinearGradient(colors: AppColors.calorieGradient, startPoint: .topLeading, endPoint: .bottomTrailing)
                    )
                    .frame(width: 24)
            }
            Text(label)
                .font(.system(.body, design: .rounded))
            Spacer()
            HStack(alignment: .firstTextBaseline, spacing: 3) {
                Text(value)
                    .font(.system(.body, design: .rounded, weight: .semibold))
                    .foregroundStyle(AppColors.calorie)
                    .contentTransition(.numericText())
                Text(unit)
                    .font(.system(.footnote, design: .rounded))
                    .foregroundStyle(.secondary)
            }
            if let goal {
                Text("/ \(goal)")
                    .font(.system(.caption, design: .rounded))
                    .foregroundStyle(.tertiary)
            }
        }
    }
}

// MARK: - Context Description Sheet
struct ContextDescriptionSheet: View {
    let image: UIImage?
    @Binding var description: String
    let onAnalyze: () -> Void
    let onCancel: () -> Void

    @FocusState private var isFocused: Bool

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 20) {
                    if let image {
                        Image(uiImage: image)
                            .resizable()
                            .scaledToFit()
                            .frame(maxHeight: 240)
                            .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
                            .overlay(
                                RoundedRectangle(cornerRadius: 16, style: .continuous)
                                    .strokeBorder(AppColors.calorie.opacity(0.15), lineWidth: 1)
                            )
                    }

                    VStack(alignment: .leading, spacing: 8) {
                        Text("Add context (optional)")
                            .font(.system(.subheadline, design: .rounded, weight: .semibold))
                            .foregroundStyle(.secondary)

                        ZStack(alignment: .topLeading) {
                            if description.isEmpty {
                                Text("e.g. \"This is a half portion\" or \"Cooked in olive oil\"")
                                    .foregroundStyle(.tertiary)
                                    .font(.body)
                                    .padding(.horizontal, 6)
                                    .padding(.vertical, 10)
                                    .allowsHitTesting(false)
                            }
                            TextField("", text: $description, axis: .vertical)
                                .font(.body)
                                .lineLimit(3...6)
                                .textFieldStyle(.plain)
                                .focused($isFocused)
                                .padding(.horizontal, 6)
                                .padding(.vertical, 10)
                        }
                        .padding(12)
                        .background(
                            RoundedRectangle(cornerRadius: 12, style: .continuous)
                                .fill(Color(.secondarySystemGroupedBackground))
                        )
                    }

                    Button {
                        onAnalyze()
                    } label: {
                        Text("Analyze")
                            .font(.headline)
                            .frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.borderedProminent)
                    .tint(AppColors.calorie)
                    .controlSize(.large)
                }
                .padding()
            }
            .background(Color(.systemGroupedBackground))
            .navigationTitle("Add Description")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { onCancel() }
                }
            }
            .onAppear { isFocused = true }
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
    @Environment(FoodStore.self) private var foodStore

    private var servingText: String? {
        guard let grams = entry.servingSizeGrams else { return nil }
        let formatted = grams == grams.rounded() ? "\(Int(grams))" : String(format: "%.1f", grams)
        return "\(formatted)g"
    }

    var body: some View {
        HStack(spacing: 12) {
            // Thumbnail
            if let imageData = entry.imageData, let uiImage = UIImage(data: imageData) {
                Image(uiImage: uiImage)
                    .resizable()
                    .scaledToFill()
                    .frame(width: 56, height: 56)
                    .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                    .overlay(
                        RoundedRectangle(cornerRadius: 12, style: .continuous)
                            .strokeBorder(AppColors.calorie.opacity(0.15), lineWidth: 1)
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
                HStack {
                    HStack(spacing: 4) {
                        Text(entry.name)
                            .font(.system(.body, design: .rounded, weight: .medium))
                            .fixedSize(horizontal: false, vertical: true)
                        if foodStore.isFavorite(entry) {
                            Image(systemName: "heart.fill")
                                .font(.caption2)
                                .foregroundStyle(AppColors.calorie)
                        }
                    }
                    Spacer()
                    Text(entry.timeString)
                        .font(.system(.caption, design: .rounded))
                        .foregroundStyle(.tertiary)
                }

                HStack(spacing: 6) {
                    Text("\(entry.calories) kcal")
                        .font(.system(.subheadline, design: .rounded, weight: .semibold))
                        .foregroundStyle(AppColors.calorie)

                    if let serving = servingText {
                        Text("·")
                            .foregroundStyle(.tertiary)
                        Text(serving)
                            .font(.system(.caption, design: .rounded))
                            .foregroundStyle(.secondary)
                    }
                }

                HStack(spacing: 8) {
                    MacroPill(label: "P", value: entry.protein)
                    MacroPill(label: "C", value: entry.carbs)
                    MacroPill(label: "F", value: entry.fat)
                }
            }
        }
        .padding(.vertical, 4)
    }
}

struct MacroPill: View {
    let label: String
    let value: Int

    var body: some View {
        Text("\(label) \(value)g")
            .font(.system(.caption2, design: .rounded, weight: .medium))
            .foregroundStyle(.secondary)
            .padding(.horizontal, 6)
            .padding(.vertical, 2)
            .background(AppColors.calorie.opacity(0.08), in: Capsule())
    }
}

// MARK: - Progress Tab
struct ProgressTabView: View {
    @Environment(FoodStore.self) private var foodStore
    @Environment(WeightStore.self) private var weightStore
    @Environment(BodyFatStore.self) private var bodyFatStore
    @Environment(ProfileStore.self) private var profileStore
    @AppStorage("useMetric") private var useMetric = false
    @State private var timeRange: TimeRange = .week
    @State private var showLogWeight = false
    @State private var showLogBodyFat = false
    @State private var showGoalReached = false
    @State private var showAllWeights = false

    private var userProfile: UserProfile { profileStore.profile }

    private var dateRange: ClosedRange<Date> { timeRange.dateRange() }

    private var filteredWeightEntries: [WeightEntry] {
        weightStore.entries(in: dateRange)
    }

    private var filteredBodyFatEntries: [BodyFatEntry] {
        bodyFatStore.entries(in: dateRange)
    }

    /// Show the Body Fat section to anyone who has either logged a reading,
    /// set a current value (legacy users from before BodyFatStore existed —
    /// they won't have any entries yet but we still want to show the tracker
    /// + Log button so they can start), or set a goal. Hidden entirely for
    /// users who skipped the body-fat track in onboarding.
    private var showsBodyFatSection: Bool {
        !bodyFatStore.entries.isEmpty
            || userProfile.bodyFatPercentage != nil
            || userProfile.goalBodyFatPercentage != nil
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
        let _ = profileStore.profile
        return NavigationStack {
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

                    // Weight / Body Fat Trend — single card with a segmented
                    // toggle (when the user has opted into body-fat tracking)
                    // or just the bare Weight chart (when they haven't, so the
                    // layout stays identical to v3.1 for those users).
                    BodyMetricsSection(
                        weightEntries: filteredWeightEntries,
                        goalWeightKg: userProfile.goalWeightKg,
                        currentWeightKg: weightStore.latestEntry?.weightKg,
                        onLogWeight: { showLogWeight = true },
                        bodyFatEntries: filteredBodyFatEntries,
                        goalBodyFatFraction: userProfile.goalBodyFatPercentage,
                        currentBodyFatFraction: bodyFatStore.latestEntry?.bodyFatFraction ?? userProfile.bodyFatPercentage,
                        onLogBodyFat: { showLogBodyFat = true },
                        bodyFatAvailable: showsBodyFatSection
                    )
                    .padding(.horizontal)

                    // Weight History — tap to view/delete entries
                    if !weightStore.entries.isEmpty {
                        WeightHistoryLink(
                            totalCount: weightStore.entries.count,
                            onTap: { showAllWeights = true }
                        )
                        .padding(.horizontal)
                    }

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
                    weightStore.addEntry(WeightEntry(weightKg: weightKg))
                }
            }
            .sheet(isPresented: $showLogBodyFat) {
                // Seed from latest entry → profile current → sane default,
                // mirroring the LogWeightSheet seeding chain.
                let seed = bodyFatStore.latestEntry?.bodyFatFraction
                    ?? userProfile.bodyFatPercentage
                    ?? 0.20
                LogBodyFatSheet(currentFraction: seed) { fraction in
                    bodyFatStore.addEntry(BodyFatEntry(bodyFatFraction: fraction))
                }
            }
            .alert("Congratulations!", isPresented: $showGoalReached) {
                Button("Keep Going", role: .cancel) { }
            } message: {
                Text("You've reached your goal weight! Head to Settings to switch your goal (Maintain, Lose, or Gain) and tap Recalculate Goals to refresh your targets.")
            }
            .onReceive(NotificationCenter.default.publisher(for: .weightGoalReached)) { _ in
                showGoalReached = true
            }
            .sheet(isPresented: $showAllWeights) {
                AllWeightHistoryView(
                    entries: weightStore.entries.sorted { $0.date > $1.date },
                    useMetric: useMetric,
                    onDelete: { entry in weightStore.deleteEntry(entry) }
                )
            }
        }
    }

}


struct ProfileView: View {
    @Environment(ProfileStore.self) private var profileStore
    @Environment(ChatStore.self) private var chatStore
    @Environment(WeightStore.self) private var weightStore
    @Environment(FoodStore.self) private var foodStore
    @Environment(NotificationManager.self) private var notificationManager
    @Environment(HealthKitManager.self) private var healthKitManager
    private var profile: UserProfile {
        get { profileStore.profile }
        nonmutating set { profileStore.profile = newValue }
    }
    private var profileBinding: Binding<UserProfile> {
        Binding(get: { profileStore.profile }, set: { profileStore.profile = $0 })
    }
    @AppStorage("appearanceMode") private var appearanceMode = "system"
    @AppStorage("useMetric") private var useMetric = false
    @AppStorage("hasCompletedOnboarding") private var hasCompletedOnboarding = true
    @AppStorage("healthKitEnabled") private var healthKitEnabled = false
    @AppStorage("weekStartsOnMonday") private var weekStartsOnMonday = false

    enum ActiveSheet: String, Identifiable {
        case editBirthday, editHeight, editWeight, editBodyFat, editGoalBodyFat, editGoalWeight, editCalories, editProtein, editCarbs, editFat
        var id: String { rawValue }
    }
    @State private var activeSheet: ActiveSheet?
    @State private var showDeleteConfirmation = false
    @State private var showClearFoodLogConfirmation = false
    @State private var showRecalculateConfirm = false
    @State private var showAutoMacroEditAlert = false
    @State private var showMaxPinnedAlert = false
    @State private var showInvalidGoalWeightAlert = false
    @State private var invalidGoalWeightMessage = ""
    @State private var selectedProvider: AIProvider = AIProviderSettings.selectedProvider
    @State private var selectedModel: String = AIProviderSettings.selectedModel
    @State private var apiKeyText: String = AIProviderSettings.apiKey(for: AIProviderSettings.selectedProvider) ?? ""
    @State private var customBaseURL: String = AIProviderSettings.customBaseURL(for: AIProviderSettings.selectedProvider) ?? ""
    @State private var showAPIKey = false
    @State private var customAIInstructions: String = AIProviderSettings.userContext
    @State private var savedAIInstructions: String = AIProviderSettings.userContext
    @FocusState private var customInstructionsFocused: Bool
    @State private var fallbackEnabled: Bool = AIProviderSettings.fallbackEnabled
    @State private var selectedFallbackProvider: AIProvider = AIProviderSettings.selectedFallbackProvider
    @State private var selectedFallbackModel: String = AIProviderSettings.selectedFallbackModel
    @State private var fallbackApiKeyText: String = AIProviderSettings.apiKey(for: AIProviderSettings.selectedFallbackProvider) ?? ""
    @State private var fallbackBaseURL: String = AIProviderSettings.customBaseURL(for: AIProviderSettings.selectedFallbackProvider) ?? ""
    @State private var showFallbackAPIKey = false
    @State private var selectedSpeechProvider: SpeechProvider = SpeechSettings.selectedProvider
    @State private var speechApiKeyText: String = SpeechSettings.apiKey(for: SpeechSettings.selectedProvider) ?? ""
    @State private var showSpeechAPIKey = false

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
                    Picker(selection: profileBinding.gender) {
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
                    .onChange(of: profile.gender) { _, _ in resetCustomGoalsAndSave() }

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

                    // Only surface the goal row to users who have a current
                    // body-fat value — feature was scoped to "skippable, no
                    // math impact, only visible if the user opted in to the
                    // body-fat track in onboarding (or set one later here)."
                    if profile.bodyFatPercentage != nil {
                        ProfileInfoRow(
                            icon: "target",
                            label: "Goal Body Fat",
                            value: profile.goalBodyFatPercentage != nil ? "\(Int(profile.goalBodyFatPercentage! * 100))%" : "Not set"
                        ) {
                            activeSheet = .editGoalBodyFat
                        }

                        // Toggle to disable Katch-McArdle without wiping the
                        // body-fat value — escape hatch for users whose
                        // reading is stale (weight shifted but they haven't
                        // re-measured). When off, BMR/TDEE/macros recompute
                        // from Mifflin-St Jeor (height/weight/age only).
                        Toggle(isOn: Binding(
                            get: { profile.useBodyFatInBMR ?? true },
                            set: { newValue in
                                profile.useBodyFatInBMR = newValue
                                resetCustomGoalsAndSave()
                            }
                        )) {
                            Label {
                                VStack(alignment: .leading, spacing: 2) {
                                    Text("Use Body Fat for BMR")
                                    Text("On = Katch-McArdle (uses lean mass for a more accurate BMR). Off = Mifflin-St Jeor (height/weight/age only). Turn off if your body-fat reading is outdated.")
                                        .font(.caption)
                                        .foregroundStyle(.secondary)
                                }
                            } icon: {
                                Image(systemName: "function")
                                    .foregroundStyle(AppColors.calorie)
                            }
                        }
                        .tint(AppColors.calorie)
                    }
                }
                .listRowBackground(AppColors.appCard)

                // Section 2: Goals & Nutrition
                Section("Goals & Nutrition") {
                    Picker(selection: profileBinding.goal) {
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
                        } else {
                            if profile.weeklyChangeKg == nil {
                                profile.weeklyChangeKg = 0.5
                            }
                            // Clear goal weight if it no longer matches the new direction
                            // (e.g., switching from Lose to Gain with an old target below current weight).
                            if let gw = profile.goalWeightKg {
                                let mismatch = (newValue == .lose && gw >= profile.weightKg)
                                            || (newValue == .gain && gw <= profile.weightKg)
                                if mismatch {
                                    profile.goalWeightKg = nil
                                }
                            }
                        }
                        resetCustomGoalsAndSave()
                    }

                    Picker(selection: profileBinding.activityLevel) {
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
                    .onChange(of: profile.activityLevel) { _, _ in resetCustomGoalsAndSave() }

                    if profile.goal != .maintain {
                        Picker(selection: Binding(
                            get: { profile.weeklyChangeKg ?? 0.5 },
                            set: { profile.weeklyChangeKg = $0; resetCustomGoalsAndSave() }
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

                    macroRow(label: "Protein", icon: "p.circle", macro: .protein, value: profile.effectiveProtein, sheet: .editProtein)
                    macroRow(label: "Carbs", icon: "c.circle", macro: .carbs, value: profile.effectiveCarbs, sheet: .editCarbs)
                    macroRow(label: "Fat", icon: "f.circle", macro: .fat, value: profile.effectiveFat, sheet: .editFat)

                    Button {
                        showRecalculateConfirm = true
                    } label: {
                        Label {
                            Text("Recalculate Goals")
                        } icon: {
                            Image(systemName: "arrow.clockwise")
                                .foregroundStyle(AppColors.calorie)
                        }
                    }
                    .tint(.primary)
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

                    Picker(selection: $weekStartsOnMonday) {
                        Text("Sunday").tag(false)
                        Text("Monday").tag(true)
                    } label: {
                        Label {
                            Text("Week Starts On")
                        } icon: {
                            Image(systemName: "calendar")
                                .foregroundStyle(AppColors.calorie)
                        }
                    }
                    .pickerStyle(.menu)
                    .tint(.secondary)

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

                // Section 4: AI Provider
                Section("AI Provider") {
                    Picker(selection: $selectedProvider) {
                        ForEach(AIProvider.allCases) { provider in
                            Label(provider.rawValue, systemImage: provider.icon).tag(provider)
                        }
                    } label: {
                        Label {
                            Text("Provider")
                        } icon: {
                            Image(systemName: "cpu")
                                .foregroundStyle(AppColors.calorie)
                        }
                    }
                    .pickerStyle(.menu)
                    .tint(.secondary)
                    .onChange(of: selectedProvider) { _, newProvider in
                        AIProviderSettings.selectedProvider = newProvider
                        selectedModel = newProvider.defaultModel
                        AIProviderSettings.selectedModel = newProvider.defaultModel
                        apiKeyText = AIProviderSettings.apiKey(for: newProvider) ?? ""
                        customBaseURL = AIProviderSettings.customBaseURL(for: newProvider) ?? ""
                    }

                    if selectedProvider.supportsCustomModelName {
                        // Free-form TextField for any model ID, with optional preset suggestions menu
                        // (e.g., OpenRouter has presets but lets user type any of openrouter.ai/models).
                        HStack {
                            Label {
                                Text("Model")
                            } icon: {
                                Image(systemName: "brain")
                                    .foregroundStyle(AppColors.calorie)
                            }
                            Spacer()
                            TextField(
                                selectedProvider == .openrouter
                                    ? "e.g. anthropic/claude-sonnet-4"
                                    : "e.g. gpt-4o-mini",
                                text: $selectedModel
                            )
                                .textFieldStyle(.plain)
                                .multilineTextAlignment(.trailing)
                                .autocorrectionDisabled()
                                .textInputAutocapitalization(.never)
                                .onChange(of: selectedModel) { _, newModel in
                                    AIProviderSettings.selectedModel = newModel
                                }
                            if !selectedProvider.models.isEmpty {
                                Menu {
                                    ForEach(selectedProvider.models, id: \.self) { model in
                                        Button(model) {
                                            selectedModel = model
                                            AIProviderSettings.selectedModel = model
                                        }
                                    }
                                } label: {
                                    Image(systemName: "list.bullet.circle")
                                        .foregroundStyle(AppColors.calorie)
                                }
                            }
                        }
                    } else {
                        Picker(selection: $selectedModel) {
                            ForEach(selectedProvider.models, id: \.self) { model in
                                Text(model).tag(model)
                            }
                        } label: {
                            Label {
                                Text("Model")
                            } icon: {
                                Image(systemName: "brain")
                                    .foregroundStyle(AppColors.calorie)
                            }
                        }
                        .pickerStyle(.menu)
                        .tint(.secondary)
                        .onAppear {
                            if !selectedProvider.models.contains(selectedModel) {
                                selectedModel = selectedProvider.defaultModel
                                AIProviderSettings.selectedModel = selectedModel
                            }
                        }
                        .onChange(of: selectedModel) { _, newModel in
                            AIProviderSettings.selectedModel = newModel
                        }
                    }

                    if selectedProvider.requiresAPIKey {
                        HStack {
                            Label {
                                Text("API Key")
                            } icon: {
                                Image(systemName: "key.fill")
                                    .foregroundStyle(AppColors.calorie)
                            }
                            Spacer()
                            Group {
                                if showAPIKey {
                                    TextField(selectedProvider.apiKeyPlaceholder, text: $apiKeyText)
                                } else {
                                    SecureField(selectedProvider.apiKeyPlaceholder, text: $apiKeyText)
                                }
                            }
                            .textFieldStyle(.plain)
                            .multilineTextAlignment(.trailing)
                            .autocorrectionDisabled()
                            .textInputAutocapitalization(.never)
                            .onChange(of: apiKeyText) { _, newValue in
                                AIProviderSettings.setAPIKey(newValue.isEmpty ? nil : newValue, for: selectedProvider)
                            }
                            Button {
                                showAPIKey.toggle()
                            } label: {
                                Image(systemName: showAPIKey ? "eye.fill" : "eye.slash.fill")
                                    .foregroundStyle(.secondary)
                                    .font(.system(size: 14))
                            }
                            .buttonStyle(.plain)
                        }
                    }

                    if selectedProvider == .ollama || selectedProvider.requiresCustomEndpoint {
                        HStack {
                            Label {
                                Text(selectedProvider.requiresCustomEndpoint ? "Base URL" : "Server URL")
                            } icon: {
                                Image(systemName: "link")
                                    .foregroundStyle(AppColors.calorie)
                            }
                            Spacer()
                            TextField(
                                selectedProvider.requiresCustomEndpoint
                                    ? "https://your-endpoint.com/v1"
                                    : selectedProvider.baseURL,
                                text: $customBaseURL
                            )
                                .textFieldStyle(.plain)
                                .multilineTextAlignment(.trailing)
                                .autocorrectionDisabled()
                                .textInputAutocapitalization(.never)
                                .keyboardType(.URL)
                                .onChange(of: customBaseURL) { _, newValue in
                                    AIProviderSettings.setCustomBaseURL(newValue.isEmpty ? nil : newValue, for: selectedProvider)
                                }
                        }
                    }
                }
                .listRowBackground(AppColors.appCard)

                // Custom AI Instructions (User Context) — prepended to every AI request when non-empty
                Section {
                    TextField(
                        "I live in Germany, assume European portion sizes. I'm on a bodybuilding cut.",
                        text: $customAIInstructions,
                        axis: .vertical
                    )
                    .lineLimit(3...6)
                    .autocorrectionDisabled(false)
                    .focused($customInstructionsFocused)

                    Button {
                        AIProviderSettings.userContext = customAIInstructions
                        let canonical = AIProviderSettings.userContext
                        customAIInstructions = canonical
                        savedAIInstructions = canonical
                        customInstructionsFocused = false
                    } label: {
                        HStack {
                            Spacer()
                            Label("Save", systemImage: "checkmark.circle.fill")
                                .font(.system(.body, design: .rounded, weight: .semibold))
                                .foregroundStyle(customAIInstructions == savedAIInstructions ? .secondary : AppColors.calorie)
                            Spacer()
                        }
                    }
                    .disabled(customAIInstructions == savedAIInstructions)
                } header: {
                    Text("Custom AI Instructions")
                } footer: {
                    Text("Optional context sent with every AI request — region, diet, athletic goals, anything you'd otherwise repeat each time. Leave empty to disable.")
                }
                .listRowBackground(AppColors.appCard)

                // Fallback Provider — retry on a second provider when the primary fails
                Section {
                    Toggle(isOn: $fallbackEnabled) {
                        Label {
                            Text("Enable Fallback")
                        } icon: {
                            Image(systemName: "arrow.triangle.2.circlepath")
                                .foregroundStyle(AppColors.calorie)
                        }
                    }
                    .tint(AppColors.calorie)
                    .onChange(of: fallbackEnabled) { _, newValue in
                        AIProviderSettings.fallbackEnabled = newValue
                    }

                    if fallbackEnabled {
                        // Fallback provider list shows all 13 — same provider as primary IS allowed
                        // (so e.g. Gemini Pro primary + Gemini Flash fallback works for capacity diversity).
                        // The collision is handled at the model layer below + at the runtime check in
                        // AIProviderSettings.currentFallbackConfig.
                        Picker(selection: $selectedFallbackProvider) {
                            ForEach(AIProvider.allCases) { provider in
                                Text(provider.rawValue).tag(provider)
                            }
                        } label: {
                            Label {
                                Text("Provider")
                            } icon: {
                                Image(systemName: "sparkles")
                                    .foregroundStyle(AppColors.calorie)
                            }
                        }
                        .onChange(of: selectedFallbackProvider) { _, newProvider in
                            AIProviderSettings.selectedFallbackProvider = newProvider
                            if !newProvider.supportsCustomModelName,
                               !newProvider.models.contains(selectedFallbackModel) {
                                selectedFallbackModel = newProvider.defaultModel
                                AIProviderSettings.selectedFallbackModel = selectedFallbackModel
                            }
                            // If switching fallback to same provider as primary AND model collides,
                            // bump to first non-primary model so picker doesn't show identical config.
                            if newProvider == selectedProvider, selectedFallbackModel == selectedModel,
                               let alt = newProvider.models.first(where: { $0 != selectedModel }) {
                                selectedFallbackModel = alt
                                AIProviderSettings.selectedFallbackModel = alt
                            }
                            fallbackApiKeyText = AIProviderSettings.apiKey(for: newProvider) ?? ""
                            fallbackBaseURL = AIProviderSettings.customBaseURL(for: newProvider) ?? ""
                        }

                        if selectedFallbackProvider.supportsCustomModelName {
                            // Free-form TextField + preset Menu, mirrors primary AI Provider section.
                            // When fallback provider == primary, the preset menu hides the primary's model.
                            let presetOptions: [String] = {
                                if selectedFallbackProvider == selectedProvider {
                                    return selectedFallbackProvider.models.filter { $0 != selectedModel }
                                }
                                return selectedFallbackProvider.models
                            }()
                            HStack {
                                Label {
                                    Text("Model")
                                } icon: {
                                    Image(systemName: "brain")
                                        .foregroundStyle(AppColors.calorie)
                                }
                                Spacer()
                                TextField(
                                    selectedFallbackProvider == .openrouter
                                        ? "e.g. anthropic/claude-sonnet-4"
                                        : "e.g. gpt-4o-mini",
                                    text: $selectedFallbackModel
                                )
                                .textFieldStyle(.plain)
                                .multilineTextAlignment(.trailing)
                                .autocorrectionDisabled()
                                .textInputAutocapitalization(.never)
                                .onChange(of: selectedFallbackModel) { _, newModel in
                                    AIProviderSettings.selectedFallbackModel = newModel
                                }
                                if !presetOptions.isEmpty {
                                    Menu {
                                        ForEach(presetOptions, id: \.self) { model in
                                            Button(model) {
                                                selectedFallbackModel = model
                                                AIProviderSettings.selectedFallbackModel = model
                                            }
                                        }
                                    } label: {
                                        Image(systemName: "list.bullet.circle")
                                            .foregroundStyle(AppColors.calorie)
                                    }
                                }
                            }
                        } else {
                            // Same provider as primary → exclude the primary's model from the picker so
                            // user can't accidentally pick an identical config.
                            let modelOptions: [String] = {
                                if selectedFallbackProvider == selectedProvider {
                                    return selectedFallbackProvider.models.filter { $0 != selectedModel }
                                }
                                return selectedFallbackProvider.models
                            }()
                            if !modelOptions.isEmpty {
                                Picker(selection: $selectedFallbackModel) {
                                    ForEach(modelOptions, id: \.self) { model in
                                        Text(model).tag(model)
                                    }
                                } label: {
                                    Label {
                                        Text("Model")
                                    } icon: {
                                        Image(systemName: "brain")
                                            .foregroundStyle(AppColors.calorie)
                                    }
                                }
                                .onChange(of: selectedFallbackModel) { _, newModel in
                                    AIProviderSettings.selectedFallbackModel = newModel
                                }
                                .onAppear {
                                    if !modelOptions.contains(selectedFallbackModel),
                                       let first = modelOptions.first {
                                        selectedFallbackModel = first
                                        AIProviderSettings.selectedFallbackModel = first
                                    }
                                }
                            }
                        }

                        if selectedFallbackProvider.requiresAPIKey {
                            HStack {
                                Label {
                                    Text("API Key")
                                } icon: {
                                    Image(systemName: "key.fill")
                                        .foregroundStyle(AppColors.calorie)
                                }
                                Spacer()
                                Group {
                                    if showFallbackAPIKey {
                                        TextField(selectedFallbackProvider.apiKeyPlaceholder, text: $fallbackApiKeyText)
                                    } else {
                                        SecureField(selectedFallbackProvider.apiKeyPlaceholder, text: $fallbackApiKeyText)
                                    }
                                }
                                .textFieldStyle(.plain)
                                .multilineTextAlignment(.trailing)
                                .autocorrectionDisabled()
                                .textInputAutocapitalization(.never)
                                .onChange(of: fallbackApiKeyText) { _, newValue in
                                    AIProviderSettings.setAPIKey(newValue.isEmpty ? nil : newValue, for: selectedFallbackProvider)
                                }
                                Button {
                                    showFallbackAPIKey.toggle()
                                } label: {
                                    Image(systemName: showFallbackAPIKey ? "eye.fill" : "eye.slash.fill")
                                        .foregroundStyle(.secondary)
                                        .font(.system(size: 14))
                                }
                                .buttonStyle(.plain)
                            }
                        }

                        if selectedFallbackProvider == .ollama || selectedFallbackProvider.requiresCustomEndpoint {
                            HStack {
                                Label {
                                    Text(selectedFallbackProvider.requiresCustomEndpoint ? "Base URL" : "Server URL")
                                } icon: {
                                    Image(systemName: "link")
                                        .foregroundStyle(AppColors.calorie)
                                }
                                Spacer()
                                TextField(
                                    selectedFallbackProvider.requiresCustomEndpoint
                                        ? "https://your-endpoint.com/v1"
                                        : selectedFallbackProvider.baseURL,
                                    text: $fallbackBaseURL
                                )
                                .textFieldStyle(.plain)
                                .multilineTextAlignment(.trailing)
                                .autocorrectionDisabled()
                                .textInputAutocapitalization(.never)
                                .keyboardType(.URL)
                                .onChange(of: fallbackBaseURL) { _, newValue in
                                    AIProviderSettings.setCustomBaseURL(newValue.isEmpty ? nil : newValue, for: selectedFallbackProvider)
                                }
                            }
                        }
                    }
                } header: {
                    Text("Fallback Provider")
                } footer: {
                    Text("If your primary provider fails (overloaded, no credits, network error), the request automatically retries on this fallback. Same provider as primary is allowed — just pick a different model.")
                }
                .listRowBackground(AppColors.appCard)

                // Speech-to-Text Provider
                Section {
                    Picker(selection: $selectedSpeechProvider) {
                        ForEach(SpeechProvider.allCases) { provider in
                            Text(provider.rawValue).tag(provider)
                        }
                    } label: {
                        Label {
                            Text("Provider")
                        } icon: {
                            Image(systemName: selectedSpeechProvider.icon)
                                .foregroundStyle(AppColors.calorie)
                        }
                    }
                    .pickerStyle(.menu)
                    .tint(.secondary)
                    .onChange(of: selectedSpeechProvider) { _, newProvider in
                        SpeechSettings.selectedProvider = newProvider
                        speechApiKeyText = SpeechSettings.apiKey(for: newProvider) ?? ""
                    }

                    Text(selectedSpeechProvider.description)
                        .font(.system(.caption, design: .rounded))
                        .foregroundStyle(.secondary)
                        .fixedSize(horizontal: false, vertical: true)

                    if selectedSpeechProvider.requiresAPIKey {
                        HStack {
                            Label {
                                Text("API Key")
                            } icon: {
                                Image(systemName: "key.fill")
                                    .foregroundStyle(AppColors.calorie)
                            }
                            Spacer()
                            Group {
                                if showSpeechAPIKey {
                                    TextField(selectedSpeechProvider.apiKeyPlaceholder, text: $speechApiKeyText)
                                } else {
                                    SecureField(selectedSpeechProvider.apiKeyPlaceholder, text: $speechApiKeyText)
                                }
                            }
                            .textFieldStyle(.plain)
                            .multilineTextAlignment(.trailing)
                            .autocorrectionDisabled()
                            .textInputAutocapitalization(.never)
                            .onChange(of: speechApiKeyText) { _, newValue in
                                SpeechSettings.setAPIKey(newValue.isEmpty ? nil : newValue, for: selectedSpeechProvider)
                            }
                            Button {
                                showSpeechAPIKey.toggle()
                            } label: {
                                Image(systemName: showSpeechAPIKey ? "eye.fill" : "eye.slash.fill")
                                    .foregroundStyle(.secondary)
                                    .font(.system(size: 14))
                            }
                            .buttonStyle(.plain)
                        }
                    }
                } header: {
                    Text("Speech-to-Text")
                } footer: {
                    Text("Used when you tap the voice icon to log a meal. Native iOS runs on-device for free; third-party providers often have better accuracy on food terms and accents.")
                }
                .listRowBackground(AppColors.appCard)

                // Section 5: Health & Data
                Section("Health & Data") {
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

                    // Clear Food Log
                    Button(role: .destructive) {
                        showClearFoodLogConfirmation = true
                    } label: {
                        Label {
                            Text("Clear Food Log")
                        } icon: {
                            Image(systemName: "fork.knife")
                        }
                        .foregroundStyle(.orange)
                    }
                    .buttonStyle(.plain)

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
            .sheet(item: $activeSheet) { sheet in
                switch sheet {
                case .editBirthday:
                    NavigationStack {
                        VStack(spacing: 20) {
                            Text("Birthday")
                                .font(.system(.title2, design: .rounded, weight: .bold))

                            DatePicker(
                                "Birthday",
                                selection: profileBinding.birthday,
                                in: ...Date.now,
                                displayedComponents: .date
                            )
                            .datePickerStyle(.wheel)
                            .labelsHidden()

                            Button {
                                saveProfile()
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
                        resetCustomGoalsAndSave()
                    }

                case .editWeight:
                    WeightPickerSheet(
                        useMetric: useMetric,
                        currentWeightKg: profile.weightKg
                    ) { newWeight in
                        profile.weightKg = newWeight
                        // Invalidate goal weight if the new current weight makes the direction impossible.
                        if let gw = profile.goalWeightKg {
                            let mismatch = (profile.goal == .lose && gw >= newWeight)
                                        || (profile.goal == .gain && gw <= newWeight)
                            if mismatch { profile.goalWeightKg = nil }
                        }
                        resetCustomGoalsAndSave()
                        weightStore.addEntry(WeightEntry(weightKg: newWeight))
                    }

                case .editBodyFat:
                    BodyFatPickerSheet(
                        currentPercentage: profile.bodyFatPercentage
                    ) { newValue in
                        profile.bodyFatPercentage = newValue
                        // Goal body fat only makes sense alongside a current
                        // value — clear it whenever the current is cleared so
                        // a stale goal doesn't linger on a user who's opted out.
                        if newValue == nil { profile.goalBodyFatPercentage = nil }
                        resetCustomGoalsAndSave()
                    }

                case .editGoalBodyFat:
                    // Goal body fat is purely cosmetic — does NOT participate
                    // in BMR / TDEE / macro math. Use a plain saveProfile()
                    // path (not resetCustomGoalsAndSave) so editing the goal
                    // never silently wipes a user's pinned macros.
                    GoalBodyFatPickerSheet(
                        currentGoal: profile.goalBodyFatPercentage,
                        currentBodyFat: profile.bodyFatPercentage
                    ) { newValue in
                        profile.goalBodyFatPercentage = newValue
                        saveProfile()
                    }

                case .editGoalWeight:
                    WeightPickerSheet(
                        useMetric: useMetric,
                        currentWeightKg: profile.goalWeightKg ?? profile.weightKg
                    ) { newGoalWeight in
                        // Validate against current goal direction.
                        let invalid = (profile.goal == .lose && newGoalWeight >= profile.weightKg)
                                   || (profile.goal == .gain && newGoalWeight <= profile.weightKg)
                        if invalid {
                            invalidGoalWeightMessage = profile.goal == .lose
                                ? "A Lose goal needs a target below your current weight."
                                : "A Gain goal needs a target above your current weight."
                            showInvalidGoalWeightAlert = true
                            return
                        }
                        profile.goalWeightKg = newGoalWeight
                        saveProfile()
                    }

                case .editCalories:
                    NutritionPickerSheet(label: "Calories", unit: "kcal", currentValue: profile.effectiveCalories, range: 800...6000, step: 50) { value in
                        profile.customCalories = value
                        saveProfile()
                    }

                case .editProtein:
                    NutritionPickerSheet(
                        label: "Protein", unit: "g",
                        currentValue: profile.effectiveProtein,
                        range: 10...500, step: 5,
                        onSave: { setMacro(.protein, to: $0) },
                        onResetToAuto: profile.isPinned(.protein) ? { setMacro(.protein, to: nil) } : nil
                    )

                case .editCarbs:
                    NutritionPickerSheet(
                        label: "Carbs", unit: "g",
                        currentValue: profile.effectiveCarbs,
                        range: 0...800, step: 5,
                        onSave: { setMacro(.carbs, to: $0) },
                        onResetToAuto: profile.isPinned(.carbs) ? { setMacro(.carbs, to: nil) } : nil
                    )

                case .editFat:
                    NutritionPickerSheet(
                        label: "Fat", unit: "g",
                        currentValue: profile.effectiveFat,
                        range: 10...300, step: 5,
                        onSave: { setMacro(.fat, to: $0) },
                        onResetToAuto: profile.isPinned(.fat) ? { setMacro(.fat, to: nil) } : nil
                    )

                }
            }
            .alert("Clear Food Log", isPresented: $showClearFoodLogConfirmation) {
                Button("Cancel", role: .cancel) { }
                Button("Clear All Logs", role: .destructive) {
                    foodStore.replaceAllEntries([])
                }
            } message: {
                Text("This will permanently delete all your logged food entries. Your profile, weight entries, and favorites will be kept. This action cannot be undone.")
            }
            .alert("Recalculate Goals", isPresented: $showRecalculateConfirm) {
                Button("Cancel", role: .cancel) { }
                Button("Recalculate") { recalculateGoalsNow() }
            } message: {
                Text("Recompute calories, protein, carbs, and fat from your current weight, activity, and goal? Your custom values will be replaced and Auto-balance will reset to Carbs.")
            }
            .alert("Auto-balanced", isPresented: $showAutoMacroEditAlert) {
                Button("OK", role: .cancel) { }
            } message: {
                Text("This macro is auto-balanced from the others. Tap the lock icon to pin it, then tap the row to set a custom value.")
            }
            .alert("Max 2 Pinned", isPresented: $showMaxPinnedAlert) {
                Button("OK", role: .cancel) { }
            } message: {
                Text("At most 2 macros can be pinned at a time. Unpin another macro first (tap its lock icon).")
            }
            .alert("Invalid Goal Weight", isPresented: $showInvalidGoalWeightAlert) {
                Button("OK", role: .cancel) { }
            } message: {
                Text(invalidGoalWeightMessage)
            }
            .alert("Delete All Data", isPresented: $showDeleteConfirmation) {
                Button("Cancel", role: .cancel) { }
                Button("Delete Everything", role: .destructive) {
                    // Delete All Data is local-only. We intentionally do NOT touch Apple
                    // Health samples — that data is personal and belongs to the user, not
                    // this app's storage. If they want HK cleaned up they can do it from
                    // the Health app's Sources → Fud AI screen.
                    foodStore.replaceAllEntries([])
                    weightStore.replaceAllEntries([])
                    // Wipe the food-image folder defensively — replaceAllEntries
                    // already cleans per-entry files, but a belt-and-braces
                    // deleteAll catches any orphans from earlier crash recovery.
                    FoodImageStore.shared.deleteAll()
                    // Cancel all notifications
                    notificationManager.cancelAllNotifications()
                    // Wipe all persisted data
                    let domain = Bundle.main.bundleIdentifier ?? ""
                    UserDefaults.standard.removePersistentDomain(forName: domain)
                    // Wipe Keychain API keys
                    AIProviderSettings.deleteAllData()
                    SpeechSettings.deleteAllData()
                    chatStore.reset()
                    // Wipe the widget snapshot out of the App Group container —
                    // it lives outside UserDefaults.standard and would otherwise
                    // keep showing the previous profile's numbers on the widget.
                    WidgetSnapshot.clear()
                    WidgetCenter.shared.reloadAllTimelines()
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

    /// Clear all custom goal overrides so calories + macros recompute from the current
    /// weight / activity / goal formulas. Triggered automatically when those underlying
    /// inputs change (gender, activity, weight, etc.) and via the Recalculate button.
    private func resetCustomGoalsAndSave() {
        profile.recalculateGoalsFromFormulas()
        saveProfile()
    }

    /// Macro row. Tap to open the picker (which lets the user enter a value to pin, or reset to auto).
    /// "(auto)" suffix when the macro is unpinned. Lock icon shows current pin state.
    @ViewBuilder
    private func macroRow(label: String, icon: String, macro: AutoBalanceMacro, value: Int, sheet: ActiveSheet) -> some View {
        let pinned = profile.isPinned(macro)
        Button {
            // Enforce max-2 only at the moment of trying to pin a NEW macro.
            // Opening the picker on an already-pinned macro is fine; opening on an auto macro
            // is also fine since user might just want to view + tap "Reset to Auto" to no-op.
            if !pinned && profile.pinnedCount >= 2 {
                showMaxPinnedAlert = true
            } else {
                activeSheet = sheet
            }
        } label: {
            HStack(spacing: 12) {
                Image(systemName: icon)
                    .foregroundStyle(AppColors.calorie)
                    .frame(width: 22)
                Text(label)
                    .foregroundStyle(.primary)
                Spacer()
                Text(pinned ? "\(value)g" : "\(value)g · auto")
                    .foregroundStyle(.secondary)
                Image(systemName: pinned ? "lock.fill" : "lock.open")
                    .font(.system(size: 13, weight: .medium))
                    .foregroundStyle(pinned ? AppColors.calorie : .secondary)
            }
        }
        .buttonStyle(.plain)
    }

    private func setMacro(_ macro: AutoBalanceMacro, to value: Int?) {
        switch macro {
        case .protein: profile.customProtein = value
        case .carbs:   profile.customCarbs   = value
        case .fat:     profile.customFat     = value
        }
        saveProfile()
    }

    private func recalculateGoalsNow() {
        profile.recalculateGoalsFromFormulas()
        saveProfile()
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
                    healthKitManager.backfillNutritionIfNeeded(
                        entries: foodStore.entries,
                        currentEntryIDs: { Set(foodStore.entries.map(\.id)) }
                    )
                } else {
                    healthKitEnabled = false
                }
            }
        } else {
            healthKitManager.stopObserver()
        }
    }

}

#Preview {
    ContentView()
        .environment(FoodStore())
        .environment(WeightStore())
}
