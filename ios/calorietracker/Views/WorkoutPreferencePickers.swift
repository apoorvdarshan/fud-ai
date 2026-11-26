import SwiftUI
import UIKit

// MARK: - Shared preference rows

struct WorkoutPreferenceMenuRow<Option: Hashable>: View {
    let title: String
    let systemImage: String
    @Binding var selection: Option
    let options: [Option]
    let label: (Option) -> String

    var body: some View {
        WorkoutPreferenceFieldRow(title: title, systemImage: systemImage) {
            Menu {
                ForEach(options, id: \.self) { option in
                    Button {
                        selection = option
                    } label: {
                        if option == selection {
                            Label {
                                WorkoutPreferenceMenuOptionText(text: label(option))
                            } icon: {
                                Image(systemName: "checkmark")
                            }
                        } else {
                            WorkoutPreferenceMenuOptionText(text: label(option))
                        }
                    }
                }
            } label: {
                WorkoutPreferenceMenuValueLabel(text: label(selection))
            }
            .workoutPressable()
            .accessibilityLabel(title)
            .accessibilityValue(label(selection))
        }
    }
}

struct WorkoutIssueMultiSelectRow: View {
    var title = String(localized: "Issues & Injuries")
    var systemImage = "cross.case.fill"
    @Binding var selection: Set<StrengthWorkoutIssue>

    private var selectedOptions: [StrengthWorkoutIssue] {
        StrengthWorkoutIssue.allCases.filter(selection.contains)
    }

    private var summary: String {
        if selectedOptions.isEmpty {
            return String(localized: "None")
        }
        if selectedOptions.count <= 2 {
            return selectedOptions.map(\.rawValue).joined(separator: ", ")
        }
        return String(localized: "\(selectedOptions.count) selected")
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            WorkoutPreferenceFieldRow(title: title, systemImage: systemImage) {
                Menu {
                    ForEach(StrengthWorkoutIssue.allCases) { option in
                        Button {
                            toggle(option)
                        } label: {
                            if selection.contains(option) {
                                Label(option.rawValue, systemImage: "checkmark")
                            } else {
                                Text(option.rawValue)
                            }
                        }
                    }
                } label: {
                    WorkoutPreferenceMenuValueLabel(text: summary)
                }
                .menuActionDismissBehavior(.disabled)
                .workoutPressable()
                .accessibilityLabel(title)
                .accessibilityValue(summary)
            }

            if !selectedOptions.isEmpty {
                WorkoutPreferenceChipRail(titles: selectedOptions.map(\.rawValue))
            }
        }
    }

    private func toggle(_ option: StrengthWorkoutIssue) {
        if selection.contains(option) {
            selection.remove(option)
        } else {
            selection.insert(option)
        }
    }
}

private struct WorkoutPreferenceFieldLabel: View {
    let title: String
    let systemImage: String
    var tint = Color.workoutAccent

    var body: some View {
        HStack(alignment: .center, spacing: 8) {
            Image(systemName: systemImage)
                .font(.body)
                .symbolRenderingMode(.monochrome)
                .foregroundStyle(tint)
                .frame(width: 24)

            Text(title)
                .font(.body)
                .foregroundStyle(Color.workoutCharcoal)
                .lineLimit(1)
                .minimumScaleFactor(0.82)
                .fixedSize(horizontal: false, vertical: true)
                .layoutPriority(1)
        }
    }
}

private struct WorkoutPreferenceFieldRow<Content: View>: View {
    let title: String
    let systemImage: String
    let tint: Color
    let content: Content
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize

    init(
        title: String,
        systemImage: String,
        tint: Color = .workoutAccent,
        @ViewBuilder content: () -> Content
    ) {
        self.title = title
        self.systemImage = systemImage
        self.tint = tint
        self.content = content()
    }

    var body: some View {
        if dynamicTypeSize.isAccessibilitySize {
            VStack(alignment: .leading, spacing: 10) {
                WorkoutPreferenceFieldLabel(title: title, systemImage: systemImage, tint: tint)
                content
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
            .padding(.vertical, 12)
            .contentShape(Rectangle())
        } else {
            HStack(alignment: .center, spacing: 12) {
                WorkoutPreferenceFieldLabel(title: title, systemImage: systemImage, tint: tint)
                    .layoutPriority(2)

                Spacer(minLength: 12)

                content
                    .layoutPriority(0)
            }
            .contentShape(Rectangle())
        }
    }
}

private struct WorkoutPreferenceMenuOptionText: View {
    let text: String

    private var nonWrappingText: String {
        text
            .replacingOccurrences(of: " ", with: "\u{00A0}")
            .map(String.init)
            .joined(separator: "\u{2060}")
    }

    var body: some View {
        Text(nonWrappingText)
            .lineLimit(1)
            .fixedSize(horizontal: true, vertical: false)
            .accessibilityLabel(text)
    }
}

private struct WorkoutPreferenceMenuValueLabel: View {
    let text: String

    var body: some View {
        HStack(spacing: 7) {
            Text(text)
                .font(.body)
                .foregroundStyle(Color.workoutMutedText)
                .lineLimit(1)
                .minimumScaleFactor(0.82)
                .multilineTextAlignment(.trailing)

            Image(systemName: "chevron.up.chevron.down")
                .font(.caption2.weight(.bold))
                .foregroundStyle(Color.workoutMutedText)
        }
        .frame(minWidth: 72, maxWidth: 178, alignment: .trailing)
    }
}

private struct WorkoutPreferenceChipRail: View {
    let titles: [String]

    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                ForEach(titles, id: \.self) { title in
                    WorkoutPreferenceChip(title: title)
                }
            }
            .padding(.leading, 48)
            .padding(.trailing, 6)
            .padding(.bottom, 10)
        }
    }
}

private struct WorkoutPreferenceChip: View {
    let title: String

    var body: some View {
        Text(title)
            .font(.caption.weight(.bold))
            .foregroundStyle(Color.workoutAccent)
            .padding(.horizontal, 10)
            .padding(.vertical, 6)
            .background(Color.workoutAccent.opacity(0.12), in: Capsule())
            .overlay {
                Capsule()
                    .stroke(Color.workoutAccent.opacity(0.22), lineWidth: 0.5)
            }
    }
}

// MARK: - Equipment

struct WorkoutEquipmentImagePickerRow: View {
    let title: String
    let systemImage: String
    let options: [String]
    let exercises: [ExerciseLibraryItem]
    @Binding var selection: Set<String>
    let label: (String) -> String
    @State private var isPickerPresented = false

    private var selectedTitles: [String] {
        options.filter(selection.contains).map(label)
    }

    private var summary: String {
        if selectedTitles.isEmpty {
            return String(localized: "None")
        }
        if selectedTitles.count <= 2 {
            return selectedTitles.joined(separator: ", ")
        }
        return String(localized: "\(selectedTitles.count) selected")
    }

    private var imageOptions: [WorkoutEquipmentImageOption] {
        options.map { option in
            let matchingExercises = exercises.filter { $0.rawEquipment == option }
            let representative = matchingExercises.first { !$0.imagePaths.isEmpty && $0.category != "Stretching" }
                ?? matchingExercises.first { !$0.imagePaths.isEmpty }

            return WorkoutEquipmentImageOption(
                value: option,
                title: label(option),
                count: matchingExercises.count,
                imagePaths: representative?.imagePaths ?? []
            )
        }
    }

    var body: some View {
        WorkoutPreferenceFieldRow(title: title, systemImage: systemImage) {
            Button {
                isPickerPresented = true
            } label: {
                WorkoutPreferenceMenuValueLabel(text: summary)
            }
            .workoutPressable()
            .accessibilityLabel(title)
            .accessibilityValue(summary)
            .sheet(isPresented: $isPickerPresented) {
                WorkoutEquipmentImagePickerSheet(
                    title: title,
                    options: imageOptions,
                    selection: $selection
                )
            }
        }
    }
}

private struct WorkoutEquipmentImageOption: Identifiable {
    var id: String { value }
    let value: String
    let title: String
    let count: Int
    let imagePaths: [String]
}

private struct WorkoutEquipmentImagePickerSheet: View {
    let title: String
    let options: [WorkoutEquipmentImageOption]
    @Binding var selection: Set<String>
    @Environment(\.dismiss) private var dismiss

    private let columns = [
        GridItem(.flexible(), spacing: 18),
        GridItem(.flexible(), spacing: 18)
    ]

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 14) {
                    WorkoutSelectionActionRow(
                        selectAllDisabled: selectableValues.isEmpty || selectableValues.isSubset(of: selection),
                        clearAllDisabled: selection.isEmpty,
                        selectAll: { selection = selectableValues },
                        clearAll: { selection.removeAll() }
                    )

                    LazyVGrid(columns: columns, spacing: 18) {
                        ForEach(options) { option in
                            Button {
                                toggle(option.value)
                            } label: {
                                WorkoutEquipmentImageTile(
                                    option: option,
                                    isSelected: selection.contains(option.value)
                                )
                            }
                            .buttonStyle(.plain)
                            .workoutPressable()
                        }
                    }
                }
                .padding(.horizontal, 18)
                .padding(.top, 16)
                .padding(.bottom, 28)
            }
            .background(WorkoutBackground())
            .navigationTitle(title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Done") { dismiss() }
                        .font(.headline.weight(.bold))
                        .foregroundStyle(Color.workoutAccent)
                }
            }
        }
        .presentationDetents([.large])
        .presentationDragIndicator(.visible)
    }

    private var selectableValues: Set<String> {
        Set(options.map(\.value))
    }

    private func toggle(_ value: String) {
        if selection.contains(value) {
            selection.remove(value)
        } else {
            selection.insert(value)
        }
    }
}

private struct WorkoutEquipmentImageTile: View {
    let option: WorkoutEquipmentImageOption
    let isSelected: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            ZStack(alignment: .topTrailing) {
                AnimatedExerciseVisual(
                    imagePaths: option.imagePaths,
                    height: 96,
                    allowsDerivedImageLookup: false,
                    animatesFrames: false,
                    fallbackSystemImage: "dumbbell.fill",
                    fallbackTitle: option.title
                )

                if isSelected {
                    Image(systemName: "checkmark.circle.fill")
                        .font(.system(size: 18, weight: .bold))
                        .foregroundStyle(Color.workoutAccent)
                        .shadow(color: Color.workoutCharcoal.opacity(0.28), radius: 6, y: 2)
                        .padding(7)
                }
            }

            Text(option.title)
                .font(.subheadline.weight(.heavy))
                .foregroundStyle(Color.workoutCharcoal)
                .lineLimit(2)
                .minimumScaleFactor(0.70)
                .frame(maxWidth: .infinity, alignment: .leading)
                .clipped()

            Text("\(option.count) exercises")
                .font(.caption2.weight(.bold))
                .foregroundStyle(Color.workoutMutedText)
                .lineLimit(1)
                .minimumScaleFactor(0.70)
                .frame(maxWidth: .infinity, alignment: .leading)
                .clipped()
        }
        .padding(7)
        .frame(maxWidth: .infinity, minHeight: 172, alignment: .topLeading)
        .contentShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
        .background(
            RoundedRectangle(cornerRadius: 18, style: .continuous)
                .fill(isSelected ? Color.workoutAccent.opacity(0.16) : Color.workoutPanel.opacity(0.24))
        )
        .overlay {
            RoundedRectangle(cornerRadius: 18, style: .continuous)
                .stroke(
                    isSelected ? Color.workoutAccent.opacity(0.72) : Color.workoutHairline.opacity(0.32),
                    lineWidth: 1
                )
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("\(option.title), \(option.count) exercises")
        .accessibilityValue(isSelected ? "Selected" : "Not selected")
        .accessibilityAddTraits(isSelected ? [.isButton, .isSelected] : [.isButton])
    }
}

// MARK: - RPE scale

struct WorkoutRPEScalePickerRow: View {
    var title = String(localized: "RPE Scale")
    var systemImage = "gauge.with.dots.needle.50percent"
    @Binding var selection: StrengthWorkoutRPEScale

    var body: some View {
        WorkoutPreferenceMenuRow(
            title: title,
            systemImage: systemImage,
            selection: $selection,
            options: StrengthWorkoutRPEScale.allCases,
            label: \.title
        )
    }
}

private struct WorkoutRPEScalePickerSheet: View {
    @Binding var selection: StrengthWorkoutRPEScale
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            ScrollViewReader { proxy in
                ScrollView {
                    LazyVStack(spacing: 16) {
                        ForEach(StrengthWorkoutRPEScale.allCases) { scale in
                            Button {
                                selection = scale
                            } label: {
                                WorkoutRPEScaleChoiceRow(
                                    scale: scale,
                                    isSelected: scale == selection
                                )
                            }
                            .id(scale.rawValue)
                            .buttonStyle(.plain)
                            .workoutPressable()
                        }
                    }
                    .padding(.horizontal, 18)
                    .padding(.top, 16)
                    .padding(.bottom, 36)
                }
                .onAppear {
                    DispatchQueue.main.async {
                        proxy.scrollTo(selection.rawValue, anchor: .center)
                    }
                }
            }
            .background(WorkoutBackground())
            .navigationTitle("RPE scale")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Done") { dismiss() }
                        .font(.headline.weight(.bold))
                        .foregroundStyle(Color.workoutAccent)
                }
            }
        }
        .presentationDetents([.large])
        .presentationDragIndicator(.visible)
    }
}

private struct WorkoutRPEScaleChoiceRow: View {
    let scale: StrengthWorkoutRPEScale
    let isSelected: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 11) {
            WorkoutRPEScaleVisual(scale: scale, isSelected: isSelected)

            HStack(alignment: .top, spacing: 12) {
                VStack(alignment: .leading, spacing: 6) {
                    Text(scale.title)
                        .font(.headline.weight(.heavy))
                        .foregroundStyle(Color.workoutCharcoal)
                        .lineLimit(2)
                        .minimumScaleFactor(0.78)

                    Text(scale.workoutPreferenceDescription)
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(Color.workoutMutedText)
                        .lineLimit(3)
                        .minimumScaleFactor(0.82)
                        .fixedSize(horizontal: false, vertical: true)

                    Text(scale.workoutPreferenceInputDetail)
                        .font(.caption.weight(.heavy))
                        .foregroundStyle(Color.workoutAccent)
                        .lineLimit(1)
                        .minimumScaleFactor(0.76)
                }
                .frame(maxWidth: .infinity, alignment: .leading)

                if isSelected {
                    Image(systemName: "checkmark.circle.fill")
                        .font(.system(size: 23, weight: .bold))
                        .foregroundStyle(Color.workoutAccent)
                        .frame(width: 27, height: 27)
                } else {
                    Color.clear.frame(width: 27, height: 27)
                }
            }
        }
        .padding(11)
        .frame(maxWidth: .infinity, alignment: .topLeading)
        .contentShape(RoundedRectangle(cornerRadius: 22, style: .continuous))
        .background(
            RoundedRectangle(cornerRadius: 22, style: .continuous)
                .fill(isSelected ? Color.workoutAccent.opacity(0.16) : Color.workoutPanel.opacity(0.20))
        )
        .overlay {
            RoundedRectangle(cornerRadius: 22, style: .continuous)
                .stroke(
                    isSelected ? Color.workoutAccent.opacity(0.74) : Color.workoutHairline.opacity(0.28),
                    lineWidth: isSelected ? 1.3 : 0.7
                )
        }
        .accessibilityElement(children: .combine)
        .accessibilityValue(isSelected ? "Selected" : "Not selected")
        .accessibilityAddTraits(isSelected ? [.isButton, .isSelected] : [.isButton])
    }
}

private struct WorkoutRPEScaleVisual: View {
    let scale: StrengthWorkoutRPEScale
    let isSelected: Bool

    var body: some View {
        ZStack {
            RoundedRectangle(cornerRadius: 18, style: .continuous)
                .fill(Color.workoutPanel.opacity(isSelected ? 0.52 : 0.32))

            if let assetImage = UIImage(named: scale.workoutPreferenceAssetName) {
                Image(uiImage: assetImage)
                    .resizable()
                    .scaledToFill()
                    .frame(maxWidth: .infinity)
                    .frame(height: 164)
                    .clipped()
                    .overlay {
                        LinearGradient(
                            colors: [Color.black.opacity(0.04), Color.black.opacity(0.18)],
                            startPoint: .top,
                            endPoint: .bottom
                        )
                    }
            } else {
                Image(systemName: "gauge.medium")
                    .font(.system(size: 36, weight: .bold))
                    .symbolRenderingMode(.hierarchical)
                    .foregroundStyle(isSelected ? Color.workoutAccent : Color.workoutSecondaryAccent)
            }
        }
        .frame(maxWidth: .infinity)
        .frame(height: 164)
        .clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
        .overlay {
            RoundedRectangle(cornerRadius: 18, style: .continuous)
                .stroke(Color.workoutHairline.opacity(0.34), lineWidth: 0.7)
        }
        .accessibilityHidden(true)
    }
}

private extension StrengthWorkoutRPEScale {
    var workoutPreferenceAssetName: String {
        switch self {
        case .strength: return "rpe_strength"
        case .cr10: return "rpe_cr10"
        case .borg: return "rpe_borg"
        }
    }

    var workoutPreferenceDescription: String {
        switch self {
        case .strength:
            return String(localized: "Best for lifting: rate effort by how many reps you had left.")
        case .cr10:
            return String(localized: "General effort scale for strength, conditioning, and mixed sessions.")
        case .borg:
            return String(localized: "Classic endurance scale tied to breathing, fatigue, and heart rate.")
        }
    }

    var workoutPreferenceInputDetail: String {
        switch self {
        case .strength: return String(localized: "Range 1–10 · decimals allowed")
        case .cr10: return String(localized: "Range 0–10 · decimals allowed")
        case .borg: return String(localized: "Range 6–20 · whole numbers")
        }
    }
}

// MARK: - Workout split

struct WorkoutSplitPickerRow: View {
    var title = String(localized: "Training Split")
    var systemImage = "square.grid.2x2.fill"
    @Binding var selection: StrengthWorkoutSplit

    var body: some View {
        WorkoutPreferenceMenuRow(
            title: title,
            systemImage: systemImage,
            selection: $selection,
            options: StrengthWorkoutSplit.selectableCases,
            label: \.title
        )
    }
}

private struct WorkoutSplitPickerSheet: View {
    @Binding var selection: StrengthWorkoutSplit
    @Environment(\.dismiss) private var dismiss

    private let splits: [StrengthWorkoutSplit] = [
        .fullBody, .upperLower, .pushPullLegs, .broSplit, .arnold,
        .pushPull, .antagonistSplit, .hybridSplit
    ]

    var body: some View {
        NavigationStack {
            ScrollViewReader { proxy in
                ScrollView {
                    LazyVStack(spacing: 12) {
                        ForEach(splits) { split in
                            Button {
                                selection = split
                            } label: {
                                WorkoutSplitChoiceRow(
                                    split: split,
                                    isSelected: split == selection
                                )
                            }
                            .id(split.id)
                            .buttonStyle(.plain)
                            .workoutPressable()
                        }
                    }
                    .padding(.horizontal, 18)
                    .padding(.top, 16)
                    .padding(.bottom, 28)
                }
                .onAppear {
                    DispatchQueue.main.async {
                        proxy.scrollTo(selection.id, anchor: .center)
                    }
                }
            }
            .background(WorkoutBackground())
            .navigationTitle("Workout split")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Done") { dismiss() }
                        .font(.headline.weight(.bold))
                        .foregroundStyle(Color.workoutAccent)
                }
            }
        }
        .presentationDetents([.large])
        .presentationDragIndicator(.visible)
    }
}

private struct WorkoutSplitChoiceRow: View {
    let split: StrengthWorkoutSplit
    let isSelected: Bool

    var body: some View {
        HStack(alignment: .center, spacing: 13) {
            WorkoutSplitVisual(split: split, isSelected: isSelected)

            VStack(alignment: .leading, spacing: 6) {
                Text(split.title)
                    .font(.subheadline.weight(.heavy))
                    .foregroundStyle(Color.workoutCharcoal)
                    .lineLimit(2)
                    .minimumScaleFactor(0.78)

                Text(split.workoutPreferenceDescription)
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(Color.workoutMutedText)
                    .lineLimit(3)
                    .minimumScaleFactor(0.82)
                    .fixedSize(horizontal: false, vertical: true)
            }
            .frame(maxWidth: .infinity, alignment: .leading)

            if isSelected {
                Image(systemName: "checkmark.circle.fill")
                    .font(.system(size: 22, weight: .bold))
                    .foregroundStyle(Color.workoutAccent)
                    .frame(width: 25, height: 25)
            } else {
                Color.clear.frame(width: 25, height: 25)
            }
        }
        .padding(10)
        .frame(maxWidth: .infinity, minHeight: 168, alignment: .leading)
        .contentShape(RoundedRectangle(cornerRadius: 22, style: .continuous))
        .background(
            RoundedRectangle(cornerRadius: 22, style: .continuous)
                .fill(isSelected ? Color.workoutAccent.opacity(0.16) : Color.workoutPanel.opacity(0.20))
        )
        .overlay {
            RoundedRectangle(cornerRadius: 22, style: .continuous)
                .stroke(
                    isSelected ? Color.workoutAccent.opacity(0.74) : Color.workoutHairline.opacity(0.28),
                    lineWidth: isSelected ? 1.3 : 0.7
                )
        }
        .accessibilityElement(children: .combine)
        .accessibilityValue(isSelected ? "Selected" : "Not selected")
        .accessibilityAddTraits(isSelected ? [.isButton, .isSelected] : [.isButton])
    }
}

private struct WorkoutSplitVisual: View {
    let split: StrengthWorkoutSplit
    let isSelected: Bool

    var body: some View {
        ZStack {
            RoundedRectangle(cornerRadius: 20, style: .continuous)
                .fill(Color.workoutPanel.opacity(isSelected ? 0.52 : 0.32))

            if let assetImage = UIImage(named: split.workoutPreferenceAssetName) {
                Image(uiImage: assetImage)
                    .resizable()
                    .scaledToFill()
                    .frame(width: 148, height: 148)
                    .clipped()
                    .overlay {
                        LinearGradient(
                            colors: [Color.black.opacity(0.08), Color.black.opacity(0.34)],
                            startPoint: .top,
                            endPoint: .bottom
                        )
                    }
            } else {
                VStack(spacing: 7) {
                    HStack(spacing: 5) {
                        ForEach(Array(split.workoutPreferencePattern.enumerated()), id: \.offset) { _, symbol in
                            Image(systemName: symbol)
                                .font(.system(size: 15, weight: .heavy))
                        }
                    }
                    .foregroundStyle(
                        isSelected
                            ? Color.workoutOnAccent.opacity(0.78)
                            : Color.workoutSecondaryAccent.opacity(0.72)
                    )

                    Image(systemName: split.workoutPreferenceSystemImage)
                        .font(.system(size: 50, weight: .bold))
                        .symbolRenderingMode(.hierarchical)
                        .foregroundStyle(isSelected ? Color.workoutOnAccent : Color.workoutSecondaryAccent)
                }
            }
        }
        .frame(width: 148, height: 148)
        .clipShape(RoundedRectangle(cornerRadius: 20, style: .continuous))
        .overlay {
            RoundedRectangle(cornerRadius: 20, style: .continuous)
                .stroke(Color.workoutHairline.opacity(0.34), lineWidth: 0.7)
        }
        .accessibilityHidden(true)
    }
}

private extension StrengthWorkoutSplit {
    var workoutPreferenceAssetName: String {
        switch self {
        case .fullBody: return "workout_split_full_body"
        case .upperLower: return "workout_split_upper_lower"
        case .pushPullLegs: return "workout_split_push_pull_legs"
        case .broSplit: return "workout_split_bro_split"
        case .arnold: return "workout_split_arnold_split"
        case .pushPull: return "workout_split_push_pull"
        case .antagonistSplit: return "workout_split_antagonist_split"
        case .hybridSplit: return "workout_split_hybrid_split"
        case .custom: return "workout_split_custom"
        }
    }

    var workoutPreferenceSystemImage: String {
        switch self {
        case .fullBody: return "figure.strengthtraining.traditional"
        case .upperLower: return "square.split.2x1"
        case .pushPullLegs: return "arrow.triangle.branch"
        case .broSplit: return "person.3.fill"
        case .arnold: return "figure.arms.open"
        case .pushPull: return "arrow.left.arrow.right"
        case .antagonistSplit: return "circle.grid.cross"
        case .hybridSplit: return "sparkles"
        case .custom: return "pencil"
        }
    }

    var workoutPreferencePattern: [String] {
        switch self {
        case .fullBody: return ["circle.fill", "circle.fill", "circle.fill"]
        case .upperLower: return ["rectangle.tophalf.filled", "rectangle.bottomhalf.filled"]
        case .pushPullLegs: return ["arrow.up.forward", "arrow.down.backward", "figure.run"]
        case .broSplit: return ["1.circle.fill", "2.circle.fill", "3.circle.fill"]
        case .arnold: return ["figure.arms.open", "dumbbell.fill", "figure.run"]
        case .pushPull: return ["arrow.left", "arrow.right"]
        case .antagonistSplit: return ["arrow.left.and.right.circle.fill", "circle.grid.cross"]
        case .hybridSplit: return ["sparkle", "dumbbell.fill", "plus"]
        case .custom: return ["pencil", "text.line.first.and.arrowtriangle.forward"]
        }
    }

    var workoutPreferenceDescription: String {
        switch self {
        case .fullBody:
            return String(localized: "Train the whole body each workout with broad coverage.")
        case .upperLower:
            return String(localized: "Alternate upper-body, lower-body, and core-focused days.")
        case .pushPullLegs:
            return String(localized: "Group exercises into push, pull, legs, and core work.")
        case .broSplit:
            return String(localized: "Focus each day around one major muscle or body region.")
        case .arnold:
            return String(localized: "Pair chest/back, shoulders/arms, legs, and core days.")
        case .pushPull:
            return String(localized: "Split work by pushing and pulling patterns across the body.")
        case .antagonistSplit:
            return String(localized: "Pair opposing muscle groups for balanced sessions.")
        case .hybridSplit:
            return String(localized: "Mix compound strength days with accessory hypertrophy work.")
        case .custom:
            return String(localized: "Use your own split text for plan prompts and filtering.")
        }
    }
}

// MARK: - Target muscles

struct WorkoutTargetMuscleSelectorRow: View {
    @Binding var selection: Set<String>
    let allowedValues: [String]
    @Binding var isPresented: Bool

    private var selectedGroups: [WorkoutTargetMuscleGroup] {
        WorkoutTargetMuscleGroup.selectedGroups(
            selection: selection,
            allowedValues: allowedValues
        )
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Button {
                isPresented = true
            } label: {
                WorkoutPreferenceFieldRow(
                    title: String(localized: "Target Muscles"),
                    systemImage: "scope"
                ) {
                    WorkoutPreferenceMenuValueLabel(
                        text: WorkoutTargetMuscleGroup.summary(
                            selection: selection,
                            allowedValues: allowedValues
                        )
                    )
                }
            }
            .buttonStyle(.plain)
            .workoutPressable()
            .accessibilityLabel("Target muscles")
            .accessibilityValue(
                WorkoutTargetMuscleGroup.summary(
                    selection: selection,
                    allowedValues: allowedValues
                )
            )

            if !selectedGroups.isEmpty {
                WorkoutPreferenceChipRail(titles: selectedGroups.map(\.title))
            }
        }
    }
}

struct WorkoutTargetMuscleSelectionView: View {
    @Binding var selection: Set<String>
    let allowedValues: [String]
    let gender: Gender
    @Environment(\.dismiss) private var dismiss

    private var sections: [WorkoutTargetMuscleSection] {
        WorkoutTargetMuscleSection.sections(allowedValues: allowedValues)
    }

    private var normalizedSelection: Set<String> {
        WorkoutTargetMuscleGroup.normalized(selection, allowedValues: allowedValues)
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 18) {
                    VStack(alignment: .leading, spacing: 6) {
                        Text("TARGET MUSCLES")
                            .font(.caption.weight(.heavy))
                            .foregroundStyle(Color.workoutAccent)

                        Text("Pick body parts")
                            .font(.title.weight(.heavy))
                            .foregroundStyle(Color.workoutCharcoal)
                    }

                    WorkoutSelectionActionRow(
                        selectAllDisabled: selectableMuscles.isEmpty || selectableMuscles.isSubset(of: normalizedSelection),
                        clearAllDisabled: normalizedSelection.isEmpty,
                        selectAll: { selection = selectableMuscles },
                        clearAll: { selection.removeAll() }
                    )

                    ForEach(sections) { section in
                        let groups = section.groups(allowedValues: allowedValues)
                        VStack(alignment: .leading, spacing: 10) {
                            Text(section.title)
                                .font(.title2.weight(.heavy))
                                .foregroundStyle(Color.workoutCharcoal)

                            LazyVGrid(
                                columns: [
                                    GridItem(.flexible(), spacing: 10),
                                    GridItem(.flexible(), spacing: 10)
                                ],
                                alignment: .leading,
                                spacing: 10
                            ) {
                                ForEach(groups) { group in
                                    let groupMuscles = Set(group.availableMuscles(allowedValues: allowedValues))
                                    WorkoutTargetMuscleCard(
                                        group: group,
                                        gender: gender,
                                        isSelected: groupMuscles.isSubset(of: normalizedSelection),
                                        toggle: {
                                            selection = WorkoutTargetMuscleGroup.toggled(
                                                selection: selection,
                                                group: group,
                                                allowedValues: allowedValues
                                            )
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
                .padding(.horizontal, 20)
                .padding(.top, 12)
                .padding(.bottom, 32)
            }
            .background(Color.workoutBackground.ignoresSafeArea())
            .navigationTitle("Target muscles")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Done") { dismiss() }
                        .font(.body.weight(.bold))
                        .foregroundStyle(Color.workoutAccent)
                }
            }
        }
        .onAppear {
            if selection != normalizedSelection {
                selection = normalizedSelection
            }
        }
    }

    private var selectableMuscles: Set<String> {
        Set(
            sections.flatMap { section in
                section.groups(allowedValues: allowedValues)
                    .flatMap { $0.availableMuscles(allowedValues: allowedValues) }
            }
        )
    }
}

private struct WorkoutTargetMuscleGroup: Identifiable, Hashable {
    let id: String
    let title: String
    let detail: String
    let systemImage: String
    let muscles: [String]

    nonisolated func availableMuscles(allowedValues: [String]) -> [String] {
        muscles.filter(allowedValues.contains)
    }

    func imageName(gender: Gender) -> String {
        let prefix = gender == .female ? "target_female" : "target_male"
        return "\(prefix)_\(id)"
    }

    nonisolated static let all: [WorkoutTargetMuscleGroup] = [
        .init(id: "chest", title: "Chest", detail: String(localized: "Pecs"), systemImage: "figure.strengthtraining.traditional", muscles: ["Chest"]),
        .init(id: "shoulders", title: "Shoulders", detail: String(localized: "Delts"), systemImage: "figure.strengthtraining.functional", muscles: ["Shoulders"]),
        .init(id: "abdominals", title: "Abdominals", detail: String(localized: "Abdominals"), systemImage: "figure.core.training", muscles: ["Abdominals"]),
        .init(id: "biceps", title: "Biceps", detail: String(localized: "Front upper arm"), systemImage: "dumbbell.fill", muscles: ["Biceps"]),
        .init(id: "triceps", title: "Triceps", detail: String(localized: "Back upper arm"), systemImage: "dumbbell.fill", muscles: ["Triceps"]),
        .init(id: "forearms", title: "Forearms", detail: String(localized: "Grip and lower arm"), systemImage: "dumbbell.fill", muscles: ["Forearms"]),
        .init(id: "lats", title: "Lats", detail: String(localized: "Width-focused back"), systemImage: "figure.pullup", muscles: ["Lats"]),
        .init(id: "middle_back", title: "Middle Back", detail: String(localized: "Rows and upper-back thickness"), systemImage: "figure.pullup", muscles: ["Middle Back"]),
        .init(id: "lower_back", title: "Lower Back", detail: String(localized: "Spinal erectors"), systemImage: "figure.flexibility", muscles: ["Lower Back"]),
        .init(id: "traps", title: "Traps", detail: String(localized: "Upper back and neck line"), systemImage: "figure.strengthtraining.functional", muscles: ["Traps"]),
        .init(id: "quadriceps", title: "Quadriceps", detail: String(localized: "Quadriceps"), systemImage: "figure.run", muscles: ["Quadriceps"]),
        .init(id: "hamstrings", title: "Hamstrings", detail: String(localized: "Posterior thigh"), systemImage: "figure.run", muscles: ["Hamstrings"]),
        .init(id: "glutes", title: "Glutes", detail: String(localized: "Hips and glutes"), systemImage: "figure.run", muscles: ["Glutes"]),
        .init(id: "calves", title: "Calves", detail: String(localized: "Lower leg"), systemImage: "figure.run", muscles: ["Calves"]),
        .init(id: "abductors", title: "Abductors", detail: String(localized: "Outer hip"), systemImage: "figure.walk", muscles: ["Abductors"]),
        .init(id: "adductors", title: "Adductors", detail: String(localized: "Inner thigh"), systemImage: "figure.walk", muscles: ["Adductors"]),
        .init(id: "neck", title: "Neck", detail: String(localized: "Neck"), systemImage: "figure.stand", muscles: ["Neck"])
    ]

    static func groups(allowedValues: [String]) -> [WorkoutTargetMuscleGroup] {
        all.filter { !$0.availableMuscles(allowedValues: allowedValues).isEmpty }
    }

    nonisolated static func group(id: String) -> WorkoutTargetMuscleGroup? {
        all.first { $0.id == id }
    }

    static func normalized(_ values: Set<String>, allowedValues: [String]) -> Set<String> {
        let allowedSet = Set(allowedValues)
        var normalizedValues = Set<String>()

        for value in values {
            if allowedSet.contains(value) {
                normalizedValues.insert(value)
                continue
            }

            if let group = all.first(where: {
                $0.title.caseInsensitiveCompare(value) == .orderedSame
                    || $0.id.caseInsensitiveCompare(value) == .orderedSame
            }) {
                normalizedValues.formUnion(group.availableMuscles(allowedValues: allowedValues))
                continue
            }

            if let legacyMuscles = legacyAliases[value.lowercased()] {
                normalizedValues.formUnion(legacyMuscles.filter(allowedSet.contains))
            }
        }

        return normalizedValues
    }

    static func toggled(
        selection: Set<String>,
        group: WorkoutTargetMuscleGroup,
        allowedValues: [String]
    ) -> Set<String> {
        let normalizedSelection = normalized(selection, allowedValues: allowedValues)
        let groupMuscles = Set(group.availableMuscles(allowedValues: allowedValues))
        guard !groupMuscles.isEmpty else { return normalizedSelection }

        if groupMuscles.isSubset(of: normalizedSelection) {
            return normalizedSelection.subtracting(groupMuscles)
        }
        return normalizedSelection.union(groupMuscles)
    }

    static func selectedGroups(
        selection: Set<String>,
        allowedValues: [String]
    ) -> [WorkoutTargetMuscleGroup] {
        let normalizedSelection = normalized(selection, allowedValues: allowedValues)
        guard !normalizedSelection.isEmpty else { return [] }
        return groups(allowedValues: allowedValues).filter {
            Set($0.availableMuscles(allowedValues: allowedValues)).isSubset(of: normalizedSelection)
        }
    }

    static func summary(selection: Set<String>, allowedValues: [String]) -> String {
        let titles = selectedGroups(selection: selection, allowedValues: allowedValues).map(\.title)
        if titles.isEmpty {
            return String(localized: "None")
        }
        if titles.count <= 2 {
            return titles.joined(separator: ", ")
        }
        return String(localized: "\(titles.count) selected")
    }

    private static let legacyAliases: [String: [String]] = [
        "core": ["Abdominals"],
        "abs / core": ["Abdominals"],
        "quads": ["Quadriceps"],
        "hips": ["Abductors", "Adductors"],
        "arms": ["Biceps", "Triceps", "Forearms"],
        "back": ["Lats", "Middle Back", "Lower Back", "Traps"],
        "legs": ["Quadriceps", "Hamstrings", "Glutes", "Calves", "Abductors", "Adductors"]
    ]
}

private struct WorkoutTargetMuscleSection: Identifiable {
    let id: String
    let title: String
    let groupIDs: [String]

    func groups(allowedValues: [String]) -> [WorkoutTargetMuscleGroup] {
        groupIDs
            .compactMap(WorkoutTargetMuscleGroup.group(id:))
            .filter { !$0.availableMuscles(allowedValues: allowedValues).isEmpty }
    }

    static let all: [WorkoutTargetMuscleSection] = [
        .init(id: "upper", title: String(localized: "Upper Body"), groupIDs: ["chest", "shoulders"]),
        .init(id: "back", title: String(localized: "Back"), groupIDs: ["lats", "middle_back", "lower_back", "traps"]),
        .init(id: "arms", title: String(localized: "Arms"), groupIDs: ["biceps", "triceps", "forearms"]),
        .init(id: "core", title: String(localized: "Core"), groupIDs: ["abdominals"]),
        .init(id: "legs", title: String(localized: "Legs / Hips"), groupIDs: ["quadriceps", "hamstrings", "glutes", "calves", "abductors", "adductors"]),
        .init(id: "neck", title: String(localized: "Neck"), groupIDs: ["neck"])
    ]

    static func sections(allowedValues: [String]) -> [WorkoutTargetMuscleSection] {
        all.filter { !$0.groups(allowedValues: allowedValues).isEmpty }
    }
}

private struct WorkoutTargetMuscleCard: View {
    let group: WorkoutTargetMuscleGroup
    let gender: Gender
    let isSelected: Bool
    let toggle: () -> Void

    var body: some View {
        Button(action: toggle) {
            VStack(alignment: .leading, spacing: 10) {
                ZStack(alignment: .topTrailing) {
                    WorkoutTargetMuscleAssetImage(imageName: group.imageName(gender: gender))
                        .frame(height: 88)
                        .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))

                    if isSelected {
                        Image(systemName: "checkmark")
                            .font(.system(size: 12, weight: .black))
                            .foregroundStyle(Color.workoutOnAccent)
                            .frame(width: 25, height: 25)
                            .background(Color.workoutAccent, in: Circle())
                            .overlay {
                                Circle()
                                    .stroke(Color.workoutOnAccent.opacity(0.20), lineWidth: 0.7)
                            }
                            .padding(7)
                    }
                }

                VStack(alignment: .leading, spacing: 4) {
                    Text(group.title)
                        .font(.headline.weight(.heavy))
                        .foregroundStyle(Color.workoutCharcoal)
                        .lineLimit(1)
                        .minimumScaleFactor(0.74)

                    Text(group.detail)
                        .font(.caption.weight(.bold))
                        .foregroundStyle(Color.workoutMutedText)
                        .lineLimit(1)
                        .minimumScaleFactor(0.74)
                }
            }
            .padding(10)
            .frame(maxWidth: .infinity, minHeight: 158, alignment: .topLeading)
            .background(
                Color.workoutPanel.opacity(isSelected ? 0.34 : 0.18),
                in: RoundedRectangle(cornerRadius: 18, style: .continuous)
            )
            .overlay {
                RoundedRectangle(cornerRadius: 18, style: .continuous)
                    .stroke(
                        isSelected ? Color.workoutAccent.opacity(0.62) : Color.workoutHairline.opacity(0.24),
                        lineWidth: isSelected ? 1.5 : 1
                    )
            }
            .contentShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
        }
        .buttonStyle(.plain)
        .workoutPressable()
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("\(group.title), \(group.detail)")
        .accessibilityValue(isSelected ? "Selected" : "Not selected")
        .accessibilityAddTraits(isSelected ? [.isButton, .isSelected] : [.isButton])
    }
}

private struct WorkoutTargetMuscleAssetImage: View {
    let imageName: String

    var body: some View {
        ZStack {
            Rectangle()
                .fill(Color.workoutPanel.opacity(0.42))

            Image(imageName)
                .resizable()
                .scaledToFill()
                .overlay {
                    LinearGradient(
                        colors: [.clear, Color.black.opacity(0.42)],
                        startPoint: .top,
                        endPoint: .bottom
                    )
                }
        }
        .accessibilityHidden(true)
    }
}

// MARK: - Shared sheet actions

private struct WorkoutSelectionActionRow: View {
    let selectAllDisabled: Bool
    let clearAllDisabled: Bool
    let selectAll: () -> Void
    let clearAll: () -> Void

    var body: some View {
        HStack(spacing: 10) {
            WorkoutSelectionActionButton(
                title: String(localized: "Select all"),
                systemImage: "checkmark.circle",
                isDisabled: selectAllDisabled,
                action: selectAll
            )

            WorkoutSelectionActionButton(
                title: String(localized: "Clear all"),
                systemImage: "xmark.circle",
                isDisabled: clearAllDisabled,
                action: clearAll
            )
        }
    }
}

private struct WorkoutSelectionActionButton: View {
    let title: String
    let systemImage: String
    let isDisabled: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Label(title, systemImage: systemImage)
                .font(.subheadline.weight(.heavy))
                .foregroundStyle(isDisabled ? Color.workoutMutedText.opacity(0.55) : Color.workoutAccent)
                .frame(maxWidth: .infinity)
                .frame(height: 40)
                .background(Color.workoutPanel.opacity(isDisabled ? 0.12 : 0.28), in: Capsule())
                .overlay {
                    Capsule()
                        .stroke(Color.workoutHairline.opacity(isDisabled ? 0.18 : 0.42), lineWidth: 0.7)
                }
        }
        .buttonStyle(.plain)
        .disabled(isDisabled)
        .workoutPressable()
    }
}
