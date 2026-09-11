import SwiftUI
import UIKit

struct WorkoutsView: View {
    @AppStorage(WorkoutTabMode.storageKey) private var selectedModeRaw = WorkoutTabMode.defaultMode.rawValue
    @AppStorage(AppThemeColor.storageKey) private var appThemeColorRaw = AppThemeColor.defaultColor.rawValue
    @State private var workoutLogSession = WorkoutLogSessionState()

    private var selectedMode: WorkoutTabMode {
        WorkoutTabMode.mode(for: selectedModeRaw)
    }

    var body: some View {
        NavigationStack {
            Group {
                if selectedMode == .log {
                    WorkoutLogView(
                        session: workoutLogSession,
                        embedsInNavigationStack: false,
                        onShowLibrary: { showMode(.library) }
                    )
                    .transition(.opacity)
                } else {
                    ExerciseLibraryBrowserView(
                        onShowWorkoutLog: { showMode(.log) }
                    )
                    .background(WorkoutsScreenBackground())
                    .navigationTitle("Workouts")
                    .navigationBarTitleDisplayMode(.inline)
                    .toolbar(.hidden, for: .navigationBar)
                    .transition(.opacity)
                }
            }
        }
        // Refresh static workout theme tokens without replacing this stack or
        // discarding its route and session-only timer state.
        .animation(.easeInOut(duration: 0.2), value: appThemeColorRaw)
    }

    private func showMode(_ mode: WorkoutTabMode) {
        withAnimation(.easeInOut(duration: 0.2)) {
            selectedModeRaw = mode.rawValue
        }
    }
}

private struct ExerciseLibraryBrowserView: View {
    @Environment(StrengthWorkoutStore.self) private var workoutStore
    var onShowWorkoutLog: (() -> Void)?

    @State private var searchText = ""
    @State private var debouncedSearchText = ""
    @State private var displayItems: [ExerciseLibraryItem] = []
    @State private var searchDebounceTask: Task<Void, Never>?
    @State private var filterPersistTask: Task<Void, Never>?
    @State private var selectedSplitGroupTitles: Set<String> = []
    @State private var selectedLevels: Set<String> = []
    @State private var selectedRawEquipment: Set<String> = []
    @State private var selectedPrimaryMuscles: Set<String> = []
    @State private var selectedSecondaryMuscles: Set<String> = []
    @State private var selectedForces: Set<String> = []
    @State private var selectedMechanics: Set<String> = []
    @State private var selectedCategories: Set<String> = []
    @State private var selectedSort: ExerciseLibrarySort = .name
    @State private var isCreateExercisePresented = false
    @State private var editingExerciseRequest: EditingExerciseRequest?

    private var service: ExerciseLibraryService { workoutStore.exerciseLibrary }

    private var selectedWorkoutSplit: StrengthWorkoutSplit {
        workoutStore.preferences.split
    }

    private var bodyPartFilterTitle: String {
        String(localized: "Body Part")
    }

    private var usesBodyPartSplitFilter: Bool {
        selectedWorkoutSplit == .fullBody || selectedWorkoutSplit == .custom
    }

    private var splitGroups: [StrengthWorkoutSplitGroup] {
        StrengthWorkoutSplitGroup.selectionGroups(
            for: selectedWorkoutSplit,
            availablePrimaryMuscles: service.availablePrimaryMuscles,
            availableSecondaryMuscles: service.availableSecondaryMuscles
        )
    }

    private var selectedSplitGroups: [StrengthWorkoutSplitGroup] {
        splitGroups.filter { selectedSplitGroupTitles.contains($0.title) }
    }

    private var shouldShowPrimaryFilter: Bool {
        !(usesBodyPartSplitFilter && !selectedSplitGroupTitles.isEmpty)
    }

    private var primaryFilterOptions: [String] {
        guard !selectedSplitGroups.isEmpty else { return service.availablePrimaryMuscles }
        let allowedMuscles = Set(selectedSplitGroups.flatMap(\.muscles))
        return service.availablePrimaryMuscles.filter(allowedMuscles.contains)
    }

    private var profileRawEquipmentOptions: [String] {
        service.availableRawEquipment
    }

    private var effectiveRawEquipmentSelection: Set<String> {
        if selectedRawEquipment.isEmpty {
            return Set(profileRawEquipmentOptions)
        }
        return selectedRawEquipment
    }

    private var hasActiveFilters: Bool {
        !searchText.isEmpty ||
            !selectedSplitGroupTitles.isEmpty ||
            !selectedLevels.isEmpty ||
            !selectedRawEquipment.isEmpty ||
            !selectedPrimaryMuscles.isEmpty ||
            !selectedSecondaryMuscles.isEmpty ||
            !selectedForces.isEmpty ||
            !selectedMechanics.isEmpty ||
            !selectedCategories.isEmpty ||
            selectedSort != .name
    }

    private var filterStateSnapshot: ExerciseFilterState {
        ExerciseFilterState(
            searchText: searchText,
            splitIdentifier: selectedWorkoutSplit.rawValue,
            splitGroups: selectedSplitGroupTitles,
            levels: selectedLevels,
            rawEquipment: selectedRawEquipment,
            primaryMuscles: selectedPrimaryMuscles,
            secondaryMuscles: selectedSecondaryMuscles,
            forces: selectedForces,
            mechanics: selectedMechanics,
            categories: selectedCategories,
            sort: selectedSort
        )
    }

    var body: some View {
        // Search, filter chips, and the results header stay pinned; only the
        // exercise list scrolls beneath them.
        VStack(alignment: .leading, spacing: 0) {
            filters
                .padding(.horizontal, 20)
                .padding(.top, 18)
                .padding(.bottom, 18)

            ResultsHeader(
                count: displayItems.count,
                noun: String(localized: "exercise"),
                subtitle: selectedSort.title,
                selectedSort: $selectedSort,
                canReset: hasActiveFilters,
                onReset: {
                    withAnimation(.snappy) {
                        resetFilters()
                    }
                }
            )
            .padding(.horizontal, 20)
            .padding(.bottom, 4)

            scrollingList
        }
        .workoutScreen()
        .onAppear {
            applyFilterState(ExerciseFilterStateStore.load(key: ExerciseFilterStateStore.workoutsKey))
            debouncedSearchText = searchText
            refreshDisplayItems()
            normalizeSplitGroupSelection()
            normalizePrimaryFilterSelection()
            normalizeEquipmentFilterSelection()
        }
        .onDisappear {
            filterPersistTask?.cancel()
            ExerciseFilterStateStore.save(filterStateSnapshot, key: ExerciseFilterStateStore.workoutsKey)
        }
        .onChange(of: searchText) { _, newValue in
            WorkoutSearchDebounce.schedule(task: &searchDebounceTask) {
                debouncedSearchText = newValue
                refreshDisplayItems()
            }
            scheduleFilterPersist()
        }
        .onChange(of: selectedSplitGroupTitles) { _, _ in
            refreshDisplayItems()
            scheduleFilterPersist()
            normalizePrimaryFilterSelection()
        }
        .onChange(of: selectedLevels) { _, _ in refreshDisplayItems(); scheduleFilterPersist() }
        .onChange(of: selectedRawEquipment) { _, _ in refreshDisplayItems(); scheduleFilterPersist() }
        .onChange(of: selectedPrimaryMuscles) { _, _ in refreshDisplayItems(); scheduleFilterPersist() }
        .onChange(of: selectedSecondaryMuscles) { _, _ in refreshDisplayItems(); scheduleFilterPersist() }
        .onChange(of: selectedForces) { _, _ in refreshDisplayItems(); scheduleFilterPersist() }
        .onChange(of: selectedMechanics) { _, _ in refreshDisplayItems(); scheduleFilterPersist() }
        .onChange(of: selectedCategories) { _, _ in refreshDisplayItems(); scheduleFilterPersist() }
        .onChange(of: selectedSort) { _, _ in refreshDisplayItems(); scheduleFilterPersist() }
        .onChange(of: selectedWorkoutSplit) {
            selectedSplitGroupTitles.removeAll()
            refreshDisplayItems()
            scheduleFilterPersist()
            normalizePrimaryFilterSelection()
        }
        .onChange(of: workoutStore.userExercises.count) { _, _ in refreshDisplayItems() }
        .sheet(isPresented: $isCreateExercisePresented) {
            UserExerciseEditorView()
        }
        .sheet(item: $editingExerciseRequest) { request in
            UserExerciseEditorView(existingItemID: request.id)
        }
    }

    private var scrollingList: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 0) {
                if displayItems.isEmpty {
                    VStack(spacing: 16) {
                        ContentUnavailableView {
                            Label("No exercises match", systemImage: "line.3.horizontal.decrease")
                        } description: {
                            Text("Try a different muscle, equipment, or search — or create your own exercise.")
                        }
                        Button {
                            isCreateExercisePresented = true
                        } label: {
                            Label("Create exercise", systemImage: "plus.circle.fill")
                                .font(.headline.weight(.semibold))
                        }
                        .buttonStyle(.borderedProminent)
                        .tint(Color.workoutAccent)
                    }
                    .frame(maxWidth: .infinity, minHeight: 260)
                    .padding(.horizontal, 20)
                } else {
                    ForEach(displayItems) { item in
                        NavigationLink {
                            ExerciseLibraryDetailView(item: item, onEdit: UserExercise.isUserExercise(item.id) ? {
                                editingExerciseRequest = EditingExerciseRequest(id: item.id)
                            } : nil)
                        } label: {
                            ExerciseLibraryRow(item: item)
                        }
                        .accessibilityIdentifier("workouts.exercise.\(item.id)")
                        .buttonStyle(.plain)
                        .padding(.horizontal, 20)

                        if item.id != displayItems.last?.id {
                            Divider()
                                .overlay(Color.workoutHairline.opacity(0.28))
                                .padding(.leading, 144)
                                .padding(.horizontal, 20)
                        }
                    }
                }
            }
            .padding(.bottom, 112)
        }
        .contentMargins(.bottom, 104, for: .scrollContent)
        .scrollDismissesKeyboard(.immediately)
    }

    private var filters: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 10) {
                WorkoutsSearchPill(searchText: $searchText)

                if let onShowWorkoutLog {
                    Button(action: onShowWorkoutLog) {
                        Image(systemName: "figure.strengthtraining.traditional")
                            .font(.system(size: 18, weight: .bold))
                            .foregroundStyle(Color.workoutAccent)
                            .frame(width: 50, height: 50)
                            .contentShape(RoundedRectangle(cornerRadius: 22, style: .continuous))
                    }
                    .buttonStyle(.plain)
                    .workoutPressable()
                    .workoutLiquidBarSurface(cornerRadius: 22)
                    .accessibilityLabel("Workout log")
                    .accessibilityHint("Opens your workout diary")
                }
            }

            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 9) {
                    filterMenuPill(
                        title: bodyPartFilterTitle,
                        value: selectionTitle(selectedSplitGroupTitles),
                        systemImage: "square.grid.2x2",
                        isActive: !selectedSplitGroupTitles.isEmpty
                    ) {
                        menuChoice(
                            String(localized: "All \(bodyPartFilterTitle)"),
                            isSelected: selectedSplitGroupTitles.isEmpty
                        ) {
                            selectedSplitGroupTitles.removeAll()
                        }
                        ForEach(splitGroups) { group in
                            muscleMenuChoice(
                                group.title,
                                muscles: group.muscles,
                                isSelected: selectedSplitGroupTitles.contains(group.title)
                            ) {
                                selectedSplitGroupTitles = [group.title]
                            }
                        }
                    }

                    if shouldShowPrimaryFilter {
                        filterMenuPill(
                            title: String(localized: "Primary"),
                            value: primaryFilterTitle,
                            systemImage: "scope",
                            isActive: !selectedPrimaryMuscles.isEmpty
                        ) {
                            menuChoice(allPrimaryMenuTitle, isSelected: selectedPrimaryMuscles.isEmpty) {
                                selectedPrimaryMuscles.removeAll()
                            }
                            ForEach(primaryFilterOptions, id: \.self) { muscle in
                                muscleMenuChoice(muscle, muscles: [muscle], isSelected: selectedPrimaryMuscles.contains(muscle)) {
                                    selectedPrimaryMuscles = [muscle]
                                }
                            }
                        }
                    }

                    filterMenuPill(
                        title: String(localized: "Secondary"),
                        value: selectionTitle(selectedSecondaryMuscles),
                        systemImage: "scope",
                        isActive: !selectedSecondaryMuscles.isEmpty
                    ) {
                        menuChoice(String(localized: "All Secondary"), isSelected: selectedSecondaryMuscles.isEmpty) {
                            selectedSecondaryMuscles.removeAll()
                        }
                        ForEach(service.availableSecondaryMuscles, id: \.self) { muscle in
                            muscleMenuChoice(muscle, muscles: [muscle], isSelected: selectedSecondaryMuscles.contains(muscle)) {
                                selectedSecondaryMuscles = [muscle]
                            }
                        }
                    }

                    filterMenuPill(
                        title: String(localized: "Equipment"),
                        value: equipmentFilterTitle,
                        systemImage: "dumbbell.fill",
                        isActive: !selectedRawEquipment.isEmpty
                    ) {
                        menuChoice(allEquipmentMenuTitle, isSelected: selectedRawEquipment.isEmpty) {
                            selectedRawEquipment.removeAll()
                        }
                        ForEach(profileRawEquipmentOptions, id: \.self) { equipment in
                            menuChoice(equipment, isSelected: selectedRawEquipment.contains(equipment)) {
                                selectedRawEquipment = [equipment]
                            }
                        }
                    }

                    filterMenuPill(
                        title: String(localized: "Level"),
                        value: selectionTitle(selectedLevels),
                        systemImage: "chart.bar.fill",
                        isActive: !selectedLevels.isEmpty
                    ) {
                        menuChoice(String(localized: "All Levels"), isSelected: selectedLevels.isEmpty) { selectedLevels.removeAll() }
                        ForEach(service.availableLevels, id: \.self) { level in
                            menuChoice(level, isSelected: selectedLevels.contains(level)) {
                                selectedLevels = [level]
                            }
                        }
                    }

                    filterMenuPill(
                        title: String(localized: "Force"),
                        value: selectionTitle(selectedForces),
                        systemImage: "arrow.left.arrow.right",
                        isActive: !selectedForces.isEmpty
                    ) {
                        menuChoice(String(localized: "All Forces"), isSelected: selectedForces.isEmpty) { selectedForces.removeAll() }
                        ForEach(service.availableForces, id: \.self) { force in
                            menuChoice(force, isSelected: selectedForces.contains(force)) {
                                selectedForces = [force]
                            }
                        }
                    }

                    filterMenuPill(
                        title: String(localized: "Mechanic"),
                        value: selectionTitle(selectedMechanics),
                        systemImage: "gearshape",
                        isActive: !selectedMechanics.isEmpty
                    ) {
                        menuChoice(String(localized: "All Mechanics"), isSelected: selectedMechanics.isEmpty) { selectedMechanics.removeAll() }
                        ForEach(service.availableMechanics, id: \.self) { mechanic in
                            menuChoice(mechanic, isSelected: selectedMechanics.contains(mechanic)) {
                                selectedMechanics = [mechanic]
                            }
                        }
                    }

                    filterMenuPill(
                        title: String(localized: "Category"),
                        value: categoryFilterTitle,
                        systemImage: "tag",
                        isActive: !selectedCategories.isEmpty
                    ) {
                        menuChoice(String(localized: "All Categories"), isSelected: selectedCategories.isEmpty) { selectedCategories.removeAll() }
                        ForEach(service.availableCategoryCounts) { categoryCount in
                            menuChoice(categoryMenuTitle(categoryCount), isSelected: selectedCategories.contains(categoryCount.category)) {
                                selectedCategories = [categoryCount.category]
                            }
                        }
                    }
                }
                .padding(.vertical, 1)
            }
        }
    }

    private var equipmentFilterTitle: String {
        if selectedRawEquipment.isEmpty {
            return String(localized: "All \(profileRawEquipmentOptions.count)")
        }
        return selectionTitle(selectedRawEquipment)
    }

    private var primaryFilterTitle: String {
        if selectedPrimaryMuscles.isEmpty {
            return String(localized: "All \(primaryFilterOptions.count)")
        }
        return selectionTitle(selectedPrimaryMuscles)
    }

    private var allPrimaryMenuTitle: String {
        String(localized: "All Primary (\(primaryFilterOptions.count))")
    }

    private var allEquipmentMenuTitle: String {
        String(localized: "All Equipment (\(profileRawEquipmentOptions.count))")
    }

    private var categoryFilterTitle: String {
        selectionTitle(selectedCategories)
    }

    private func categoryMenuTitle(_ categoryCount: ExerciseCategoryCount) -> String {
        categoryCount.category
    }

    private func refreshDisplayItems() {
        let rawEquipmentSelection = effectiveRawEquipmentSelection
        guard !rawEquipmentSelection.isEmpty else {
            displayItems = []
            return
        }

        let filtered = service.filtered(
            levels: selectedLevels,
            rawEquipment: rawEquipmentSelection,
            primaryMuscles: selectedPrimaryMuscles,
            secondaryMuscles: selectedSecondaryMuscles,
            forces: selectedForces,
            mechanics: selectedMechanics,
            categories: selectedCategories,
            sort: selectedSort,
            searchText: debouncedSearchText
        )

        if selectedSplitGroups.isEmpty {
            displayItems = filtered
            return
        }

        let selectedMuscles = Set(selectedSplitGroups.flatMap(\.muscles))
        displayItems = filtered.filter { item in
            item.primaryMuscles.contains(where: selectedMuscles.contains) ||
                item.secondaryMuscles.contains(where: selectedMuscles.contains)
        }
    }

    private func scheduleFilterPersist() {
        let snapshot = filterStateSnapshot
        WorkoutSearchDebounce.schedule(
            task: &filterPersistTask,
            delayNanoseconds: WorkoutSearchDebounce.persistDelayNanoseconds
        ) {
            ExerciseFilterStateStore.save(snapshot, key: ExerciseFilterStateStore.workoutsKey)
        }
    }

    private func resetFilters() {
        searchDebounceTask?.cancel()
        searchText = ""
        debouncedSearchText = ""
        selectedSplitGroupTitles.removeAll()
        selectedLevels.removeAll()
        selectedRawEquipment.removeAll()
        selectedPrimaryMuscles.removeAll()
        selectedSecondaryMuscles.removeAll()
        selectedForces.removeAll()
        selectedMechanics.removeAll()
        selectedCategories.removeAll()
        selectedSort = .name
        refreshDisplayItems()
        scheduleFilterPersist()
    }

    private func applyFilterState(_ state: ExerciseFilterState) {
        searchText = state.searchText
        debouncedSearchText = state.searchText
        selectedSplitGroupTitles = state.splitIdentifier == selectedWorkoutSplit.rawValue
            ? singleStoredSelection(state.splitGroups)
            : []
        selectedLevels = singleStoredSelection(state.levels)
        selectedRawEquipment = singleStoredSelection(state.rawEquipment)
        selectedPrimaryMuscles = singleStoredSelection(state.primaryMuscles)
        selectedSecondaryMuscles = singleStoredSelection(state.secondaryMuscles)
        selectedForces = singleStoredSelection(state.forces)
        selectedMechanics = singleStoredSelection(state.mechanics)
        selectedCategories = singleStoredSelection(state.categories)
        selectedSort = state.sort
    }

    private func normalizeSplitGroupSelection() {
        let validTitles = Set(splitGroups.map(\.title))
        selectedSplitGroupTitles = singleStoredSelection(selectedSplitGroupTitles.intersection(validTitles))
    }

    private func normalizePrimaryFilterSelection() {
        if !shouldShowPrimaryFilter {
            selectedPrimaryMuscles.removeAll()
            return
        }

        let validOptions = Set(primaryFilterOptions)
        guard !validOptions.isEmpty else {
            selectedPrimaryMuscles.removeAll()
            return
        }
        selectedPrimaryMuscles = singleStoredSelection(selectedPrimaryMuscles.intersection(validOptions))
    }

    private func normalizeEquipmentFilterSelection() {
        let validOptions = Set(profileRawEquipmentOptions)
        selectedRawEquipment = singleStoredSelection(selectedRawEquipment.intersection(validOptions))
    }

    private func selectionTitle(_ selection: Set<String>) -> String {
        if selection.isEmpty { return "All" }
        if selection.count == 1 { return selection.first ?? "All" }
        return String(localized: "\(selection.count) selected")
    }

    private func singleStoredSelection(_ selection: Set<String>) -> Set<String> {
        guard let value = selection.sorted().first else { return [] }
        return [value]
    }

    private func filterMenuPill<Content: View>(
        title: String,
        value: String,
        systemImage: String,
        isActive: Bool,
        @ViewBuilder content: () -> Content
    ) -> some View {
        Menu {
            content()
        } label: {
            FilterMenuPill(title: title, value: value, systemImage: systemImage, isActive: isActive)
        }
        .workoutPressable()
    }

    private func menuChoice(_ title: String, isSelected: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            if isSelected {
                Label(title, systemImage: "checkmark")
            } else {
                Text(title)
            }
        }
    }

    private func muscleMenuChoice(
        _ title: String,
        muscles: Set<String>,
        isSelected: Bool,
        action: @escaping () -> Void
    ) -> some View {
        Button(action: action) {
            if isSelected {
                Label(title, systemImage: "checkmark")
            } else {
                Label(title, image: MuscleGlyphAsset.name(title: title, muscles: muscles))
            }
        }
    }
}

private struct WorkoutsSearchPill: View {
    @Binding var searchText: String

    var body: some View {
        HStack(spacing: 8) {
            Image(systemName: "magnifyingglass")
                .font(.system(size: 14, weight: .bold))
                .foregroundStyle(searchText.isEmpty ? Color.workoutSecondaryAccent : Color.workoutAccent)

            TextField("Search", text: $searchText)
                .accessibilityIdentifier("workouts.search")
                .textFieldStyle(.plain)
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(Color.workoutCharcoal)
                .lineLimit(1)
                .frame(maxWidth: .infinity)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()

            if !searchText.isEmpty {
                Button {
                    searchText = ""
                } label: {
                    Image(systemName: "xmark.circle.fill")
                        .font(.system(size: 15, weight: .bold))
                        .foregroundStyle(Color.workoutMutedText)
                }
                .buttonStyle(.plain)
                .accessibilityIdentifier("workouts.search.clear")
                .accessibilityLabel("Clear search")
            }
        }
        .padding(.horizontal, 14)
        .frame(maxWidth: .infinity, minHeight: 50, alignment: .leading)
        .workoutLiquidBarSurface(cornerRadius: 22)
    }
}

private struct FilterMenuPill: View {
    let title: String
    let value: String
    let systemImage: String
    let isActive: Bool

    private var isDefaultValue: Bool {
        !isActive
    }

    var body: some View {
        HStack(spacing: 9) {
            Image(systemName: systemImage)
                .font(.system(size: 14, weight: .bold))
                .foregroundStyle(isDefaultValue ? Color.workoutSecondaryAccent : Color.workoutAccent)
                .frame(width: 18)

            VStack(alignment: .leading, spacing: 1) {
                Text(title)
                    .font(.caption2.weight(.bold))
                    .foregroundStyle(Color.workoutMutedText)
                    .textCase(.uppercase)
                    .lineLimit(1)

                Text(value)
                    .font(.subheadline.weight(.bold))
                    .foregroundStyle(Color.workoutCharcoal)
                    .lineLimit(1)
                    .minimumScaleFactor(0.78)
            }

            Image(systemName: "chevron.down")
                .font(.caption2.weight(.bold))
                .foregroundStyle(Color.workoutMutedText)
                .padding(.leading, 1)
        }
        .padding(.horizontal, 12)
        .frame(minWidth: 112, minHeight: 46, alignment: .leading)
        .background(
            Color.workoutPanel.opacity(isDefaultValue ? 0.30 : 0.46),
            in: RoundedRectangle(cornerRadius: 17, style: .continuous)
        )
        .overlay {
            RoundedRectangle(cornerRadius: 17, style: .continuous)
                .stroke(
                    (isDefaultValue ? Color.workoutHairline : Color.workoutAccent).opacity(isDefaultValue ? 0.30 : 0.42),
                    lineWidth: 0.5
                )
        }
        .contentShape(RoundedRectangle(cornerRadius: 17, style: .continuous))
    }
}

private struct ResultsHeader: View {
    let count: Int
    let noun: String
    let subtitle: String
    @Binding var selectedSort: ExerciseLibrarySort
    let canReset: Bool
    let onReset: () -> Void

    var body: some View {
        HStack(alignment: .firstTextBaseline) {
            VStack(alignment: .leading, spacing: 2) {
                Text("\(count) \(count == 1 ? noun : String(localized: "\(noun)s"))")
                    .font(.headline.weight(.semibold))
                    .foregroundStyle(Color.workoutCharcoal)
                    .textCase(nil)
                Text(subtitle)
                    .font(.caption)
                    .foregroundStyle(Color.workoutMutedText)
                    .textCase(nil)
            }

            Spacer()

            HStack(spacing: 8) {
                Button {
                    onReset()
                } label: {
                    Label("Reset", systemImage: "arrow.counterclockwise")
                        .font(.caption.weight(.bold))
                        .foregroundStyle(canReset ? Color.workoutInferno : Color.workoutMutedText)
                        .lineLimit(1)
                        .padding(.horizontal, 11)
                        .frame(height: 34)
                        .background((canReset ? Color.workoutInferno : Color.workoutPanel).opacity(canReset ? 0.10 : 0.22), in: Capsule())
                        .overlay {
                            Capsule()
                                .stroke((canReset ? Color.workoutInferno : Color.workoutHairline).opacity(canReset ? 0.28 : 0.24), lineWidth: 0.5)
                        }
                }
                .disabled(!canReset)
                .buttonStyle(.plain)
                .workoutPressable()

                Menu {
                    ForEach(ExerciseLibrarySort.allCases) { sort in
                        Button {
                            selectedSort = sort
                        } label: {
                            if selectedSort == sort {
                                Label(sort.title, systemImage: "checkmark")
                            } else {
                                Text(sort.title)
                            }
                        }
                    }
                } label: {
                    Label("Sort", systemImage: "arrow.up.arrow.down")
                        .font(.caption.weight(.bold))
                        .foregroundStyle(count == 0 ? Color.workoutMutedText : (selectedSort == .name ? Color.workoutMutedText : Color.workoutAccent))
                        .lineLimit(1)
                        .padding(.horizontal, 11)
                        .frame(height: 34)
                        .background(Color.workoutPanel.opacity(selectedSort == .name ? 0.30 : 0.46), in: Capsule())
                        .overlay {
                            Capsule()
                                .stroke((selectedSort == .name ? Color.workoutHairline : Color.workoutAccent).opacity(0.32), lineWidth: 0.5)
                        }
                }
                .buttonStyle(.plain)
                .workoutPressable()
                .disabled(count == 0)
            }
        }
        .padding(.top, 6)
    }
}

private struct ExerciseLibraryRow: View {
    let item: ExerciseLibraryItem

    var body: some View {
        HStack(spacing: 16) {
            thumbnail

            VStack(alignment: .leading, spacing: 9) {
                Text(item.name)
                    .font(.headline.weight(.semibold))
                    .foregroundStyle(Color.workoutCharcoal)
                    .lineLimit(2)
                    .minimumScaleFactor(0.82)

                ViewThatFits(in: .horizontal) {
                    HStack(spacing: 8) {
                        LibraryTag(title: item.primaryMusclesTitle, systemImage: "scope", tint: Color.workoutMutedText)
                        LibraryTag(title: item.rawEquipment, systemImage: "dumbbell.fill", tint: Color.workoutMutedText)
                        LibraryTag(title: item.rawLevel, systemImage: "chart.bar.fill", tint: Color.workoutMutedText)
                    }

                    VStack(alignment: .leading, spacing: 5) {
                        LibraryTag(title: item.primaryMusclesTitle, systemImage: "scope", tint: Color.workoutMutedText)
                        LibraryTag(title: "\(item.rawEquipment) - \(item.rawLevel)", systemImage: "dumbbell.fill", tint: Color.workoutMutedText)
                    }
                }

                Label(item.databaseMetadataSummary, systemImage: "server.rack")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(Color.workoutSecondaryAccent)
                    .lineLimit(1)
                    .minimumScaleFactor(0.78)
            }

            Spacer(minLength: 8)

            Image(systemName: "chevron.right")
                .font(.footnote.weight(.semibold))
                .foregroundStyle(Color.workoutHairline)
        }
        .padding(.vertical, 15)
        .contentShape(Rectangle())
        .accessibilityElement(children: .combine)
    }

    private var thumbnail: some View {
        AnimatedExerciseVisual(
            exerciseName: item.name,
            imagePaths: item.imagePaths,
            height: 104,
            fillsWidth: false,
            allowsDerivedImageLookup: false,
            animatesFrames: false
        )
        .frame(width: 104, height: 104)
        .background(Color.workoutPanel.opacity(0.32), in: RoundedRectangle(cornerRadius: 18, style: .continuous))
        .clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
        .overlay {
            RoundedRectangle(cornerRadius: 18, style: .continuous)
                .stroke(Color.workoutHairline.opacity(0.38), lineWidth: 0.5)
        }
        .accessibilityHidden(true)
    }

}

private struct LibraryTag: View {
    let title: String
    let systemImage: String
    let tint: Color

    var body: some View {
        Label(title, systemImage: systemImage)
            .font(.caption2.weight(.bold))
            .labelStyle(.titleAndIcon)
            .foregroundStyle(tint)
            .lineLimit(1)
            .minimumScaleFactor(0.78)
            .padding(.horizontal, 9)
            .padding(.vertical, 4)
            .background(Color.workoutPanel.opacity(0.28), in: Capsule())
            .overlay {
                Capsule().stroke(Color.workoutHairline.opacity(0.22), lineWidth: 0.5)
            }
            .accessibilityElement(children: .combine)
    }
}

private struct EditingExerciseRequest: Identifiable {
    let id: String
}

struct ExerciseLibraryDetailView: View {
    let item: ExerciseLibraryItem
    var onEdit: (() -> Void)?
    @State private var isMetricsPresented = false

    var body: some View {
        GeometryReader { geometry in
            let screenWidth = geometry.size.width

            ZStack(alignment: .top) {
                ScrollView(.vertical) {
                    VStack(alignment: .leading, spacing: 0) {
                        Color.clear
                            .frame(width: screenWidth, height: 294)

                        VStack(alignment: .leading, spacing: 24) {
                            DetailInstructionSection(
                                exerciseName: item.name,
                                instructions: item.instructions
                            )
                        }
                        .padding(.horizontal, 20)
                        .padding(.top, 24)
                        .padding(.bottom, 40)
                        .frame(width: screenWidth, alignment: .leading)
                    }
                }
                .scrollIndicators(.hidden)
                .scrollDismissesKeyboard(.interactively)

                detailHero(width: screenWidth)
                    .zIndex(1)
            }
            .frame(width: screenWidth, alignment: .top)
        }
        .workoutScreen()
        .navigationTitle(item.name)
        .navigationBarTitleDisplayMode(.inline)
        .toolbarBackground(.visible, for: .navigationBar)
        .toolbarBackground(Color.workoutBackground, for: .navigationBar)
        .toolbar {
            if let onEdit {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Edit", action: onEdit)
                        .font(.headline.weight(.semibold))
                        .foregroundStyle(Color.workoutAccent)
                }
            }
        }
    }

    private func detailHero(width: CGFloat) -> some View {
        ZStack(alignment: .topTrailing) {
            // Opaque fill so transparent PNG cutouts don't let instructions
            // show through while this pinned hero covers the scroll content.
            Color.workoutBackground
                .frame(width: width, height: 294)

            AnimatedExerciseVisual(
                exerciseName: item.name,
                imagePaths: item.imagePaths,
                height: 294,
                allowsDerivedImageLookup: false
            )
            .frame(width: width, height: 294)
            .frame(width: width, height: 294, alignment: .bottomLeading)

            if isMetricsPresented {
                Color.black.opacity(0.5)
                    .frame(width: width, height: 294)
                    .allowsHitTesting(false)
                    .transition(.opacity)

                ExerciseHeroMetricOverlay(item: item)
                    .padding(.top, 8)
                    .padding(.leading, 24)
                    .padding(.trailing, 84)
                    .frame(width: width, height: 294, alignment: .topLeading)
                    .transition(.asymmetric(
                        insertion: .move(edge: .trailing).combined(with: .opacity),
                        removal: .move(edge: .trailing).combined(with: .opacity)
                    ))
            }

            Button {
                withAnimation(.snappy(duration: 0.28)) {
                    isMetricsPresented.toggle()
                }
            } label: {
                Image(systemName: "info.circle.fill")
                    .font(.system(size: 20, weight: .bold))
                    .foregroundStyle(Color.workoutAccent)
                    .frame(width: 44, height: 44)
                    .background(Color.workoutBackground.opacity(0.78), in: Circle())
                    .overlay {
                        Circle()
                            .stroke(Color.workoutHairline.opacity(0.42), lineWidth: 0.7)
                    }
            }
            .buttonStyle(.plain)
            .workoutPressable()
            .padding(.top, 14)
            .padding(.trailing, 16)
            .accessibilityLabel(isMetricsPresented ? String(localized: "Hide exercise details") : String(localized: "Show exercise details"))
        }
        .frame(width: width, height: 294)
        .animation(.snappy(duration: 0.28), value: isMetricsPresented)
        .clipped()
        .accessibilityElement(children: .combine)
        .accessibilityIdentifier("workouts.exercise.visual.\(item.id)")
        .accessibilityLabel(Text("\(item.name) exercise visual"))
    }

}

private struct ExerciseHeroMetricOverlay: View {
    let item: ExerciseLibraryItem
    @Environment(\.colorScheme) private var colorScheme

    var body: some View {
        VStack(alignment: .center, spacing: 6) {
            HStack(spacing: 8) {
                metricButton(title: String(localized: "Level"), value: item.rawLevel, systemImage: "chart.bar.fill", compact: true)
                metricButton(title: String(localized: "Category"), value: item.category, systemImage: "tag", compact: true)
            }

            HStack(spacing: 8) {
                metricButton(title: String(localized: "Force"), value: item.force, systemImage: "arrow.left.arrow.right", compact: true)
                metricButton(title: String(localized: "Mechanic"), value: item.mechanic, systemImage: "gearshape", compact: true)
            }

            metricButton(title: String(localized: "Primary"), value: item.primaryMusclesTitle, systemImage: "scope", compact: false, valueLineLimit: 2)
            metricButton(title: String(localized: "Secondary"), value: item.secondaryMusclesTitle, systemImage: "scope", compact: false, valueLineLimit: 3)
            metricButton(title: String(localized: "Equipment"), value: item.rawEquipment, systemImage: "dumbbell.fill", compact: false, valueLineLimit: 2)
        }
        .frame(maxWidth: .infinity)
    }

    private func metricButton(title: String, value: String, systemImage: String, compact: Bool = true, valueLineLimit: Int = 1) -> some View {
        let valueFontSize: CGFloat = compact ? 15 : (valueLineLimit > 2 ? 13 : 14)
        let labelColor = colorScheme == .light ? Color.workoutSecondaryAccent : Color.workoutAccent
        let fillColor = colorScheme == .light ? Color.workoutBackground.opacity(0.92) : Color.workoutBackground.opacity(0.55)
        let strokeColor = colorScheme == .light ? Color.workoutHairline.opacity(0.45) : Color.workoutHairline.opacity(0.32)

        return VStack(alignment: .leading, spacing: 3) {
            Label(title, systemImage: systemImage)
                .font(.system(size: 10, weight: .bold, design: .rounded))
                .foregroundStyle(labelColor)
                .lineLimit(1)

            Text(value)
                .font(.system(size: valueFontSize, weight: .bold, design: .rounded))
                .foregroundStyle(Color.workoutCharcoal)
                .lineLimit(valueLineLimit)
                .minimumScaleFactor(0.50)
                .allowsTightening(true)
                .fixedSize(horizontal: false, vertical: true)
        }
        .padding(.horizontal, 12)
        .padding(.vertical, compact ? 6 : 5)
        .frame(maxWidth: .infinity, minHeight: compact ? 44 : (valueLineLimit > 2 ? 50 : 42), alignment: .leading)
        .background {
            if colorScheme == .light {
                RoundedRectangle(cornerRadius: 16, style: .continuous)
                    .fill(fillColor)
            } else {
                RoundedRectangle(cornerRadius: 16, style: .continuous)
                    .fill(fillColor)
                    .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
            }
        }
        .overlay {
            RoundedRectangle(cornerRadius: 16, style: .continuous)
                .stroke(strokeColor, lineWidth: 0.6)
        }
        .shadow(color: Color.black.opacity(colorScheme == .light ? 0.12 : 0.32), radius: 9, x: 0, y: 4)
    }
}

private struct DetailInstructionSection: View {
    let exerciseName: String
    let instructions: [String]
    @Environment(\.openURL) private var openURL

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            HStack(spacing: 10) {
                Image(systemName: "list.number")
                    .font(.subheadline.weight(.bold))
                    .foregroundStyle(Color.workoutAccent)
                    .frame(width: 30, height: 30)
                    .background(Color.workoutAccent.opacity(0.12), in: RoundedRectangle(cornerRadius: 10, style: .continuous))

                Text("Instructions")
                    .font(.title3.weight(.bold))
                    .foregroundStyle(Color.workoutCharcoal)

                Spacer(minLength: 0)

                Text("\(instructions.count)")
                    .font(.caption.weight(.bold))
                    .monospacedDigit()
                    .foregroundStyle(Color.workoutSecondaryAccent)
                    .padding(.horizontal, 9)
                    .padding(.vertical, 4)
                    .background(Color.workoutSecondaryAccent.opacity(0.12), in: Capsule())
            }

            VStack(alignment: .leading, spacing: 10) {
                ForEach(Array(instructions.enumerated()), id: \.offset) { index, instruction in
                    HStack(alignment: .top, spacing: 13) {
                        Text("\(index + 1)")
                            .font(.subheadline.weight(.heavy))
                            .monospacedDigit()
                            .foregroundStyle(Color.workoutOnAccent)
                            .frame(width: 27, height: 27)
                            .background(Color.workoutAccent, in: Circle())
                            .shadow(color: Color.workoutAccent.opacity(0.35), radius: 6, y: 2)

                        Text(instruction)
                            .font(.callout)
                            .foregroundStyle(Color.workoutCharcoal.opacity(0.86))
                            .lineSpacing(4)
                            .fixedSize(horizontal: false, vertical: true)
                            .frame(maxWidth: .infinity, alignment: .leading)
                    }
                    .padding(14)
                    .background(Color.workoutPanel.opacity(0.16), in: RoundedRectangle(cornerRadius: 18, style: .continuous))
                    .overlay {
                        RoundedRectangle(cornerRadius: 18, style: .continuous)
                            .stroke(Color.workoutHairline.opacity(0.20), lineWidth: 0.5)
                    }
                }
            }

            Button {
                if let url = youtubeSearchURL(for: exerciseName) {
                    openURL(url)
                }
            } label: {
                HStack(spacing: 12) {
                    Image(systemName: "play.rectangle.fill")
                        .font(.body.weight(.bold))
                        .foregroundStyle(Color.workoutAccent)
                        .frame(width: 30, height: 30)
                        .background(Color.workoutAccent.opacity(0.12), in: RoundedRectangle(cornerRadius: 10, style: .continuous))

                    VStack(alignment: .leading, spacing: 2) {
                        Text("Watch on YouTube")
                            .font(.callout.weight(.bold))
                            .foregroundStyle(Color.workoutCharcoal)
                        Text("Opens YouTube search — not an official Fud AI video")
                            .font(.caption)
                            .foregroundStyle(Color.workoutMutedText)
                            .fixedSize(horizontal: false, vertical: true)
                    }

                    Spacer(minLength: 0)

                    Image(systemName: "arrow.up.right")
                        .font(.caption.weight(.bold))
                        .foregroundStyle(Color.workoutMutedText)
                }
                .padding(14)
                .background(Color.workoutPanel.opacity(0.16), in: RoundedRectangle(cornerRadius: 18, style: .continuous))
                .overlay {
                    RoundedRectangle(cornerRadius: 18, style: .continuous)
                        .stroke(Color.workoutHairline.opacity(0.20), lineWidth: 0.5)
                }
            }
            .buttonStyle(.plain)
            .workoutPressable()
            .accessibilityHint(String(localized: "Opens YouTube search — not an official Fud AI video"))
        }
    }

    private func youtubeSearchURL(for exerciseName: String) -> URL? {
        var components = URLComponents(string: "https://www.youtube.com/results")
        components?.queryItems = [
            URLQueryItem(name: "search_query", value: "\(exerciseName) exercise")
        ]
        return components?.url
    }
}

private struct WorkoutsScreenBackground: View {
    var body: some View {
        WorkoutBackground()
    }
}
