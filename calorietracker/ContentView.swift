//
//  ContentView.swift
//  calorietracker
//
//  Created by Apoorv Darshan on 05/02/26.
//

import SwiftUI
import UIKit

// MARK: - Main Content View
struct ContentView: View {
    var body: some View {
        TabView {
            HomeView()
                .tabItem {
                    Image(systemName: "house.fill")
                    Text("Home")
                }

            ProgressView()
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
    @State private var showCamera = false
    @State private var capturedImage: UIImage?

    var body: some View {
        NavigationStack {
            List {
                // Week Selector
                Section {
                    WeekSelectorView()
                }

                // Calorie Summary
                Section("Calories") {
                    CalorieRow()
                }

                // Log Food
                Section("Log Food") {
                    Button(action: { showCamera = true }) {
                        HStack(spacing: 12) {
                            Image(systemName: "camera.fill")
                                .font(.title2)
                                .foregroundStyle(.white)
                                .frame(width: 50, height: 50)
                                .background(Color.accentColor)
                                .clipShape(RoundedRectangle(cornerRadius: 12))

                            VStack(alignment: .leading, spacing: 2) {
                                Text("Snap a photo")
                                    .font(.headline)
                                    .foregroundStyle(.primary)
                                Text("Take a picture and we'll count the calories")
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                            }

                            Spacer()

                            Image(systemName: "chevron.right")
                                .foregroundStyle(.tertiary)
                        }
                    }

                    HStack(spacing: 16) {
                        LogOptionButton(icon: "barcode.viewfinder", label: "Barcode")
                        LogOptionButton(icon: "text.viewfinder", label: "Food Label")
                        LogOptionButton(icon: "magnifyingglass", label: "Search")
                    }
                    .frame(maxWidth: .infinity)
                }

                // Macros
                Section("Macros") {
                    MacroRow(name: "Protein", current: 75, goal: 150, unit: "g", color: .orange)
                    MacroRow(name: "Carbs", current: 138, goal: 275, unit: "g", color: .yellow)
                    MacroRow(name: "Fat", current: 35, goal: 70, unit: "g", color: .blue)
                }

                // Recently Uploaded
                Section("Recently uploaded") {
                    FoodRow(name: "Grilled Salmon", calories: 550, time: "12:37pm")
                    FoodRow(name: "Caesar Salad", calories: 330, time: "6:21pm")
                    FoodRow(name: "Protein Smoothie", calories: 280, time: "8:15am")
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
        }
    }
}

// MARK: - Log Option Button
struct LogOptionButton: View {
    let icon: String
    let label: String

    var body: some View {
        Button(action: {}) {
            VStack(spacing: 6) {
                Image(systemName: icon)
                    .font(.title3)
                Text(label)
                    .font(.caption2)
            }
            .frame(maxWidth: .infinity)
            .padding(.vertical, 8)
        }
        .buttonStyle(.bordered)
        .tint(.primary)
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
    let eaten: Int = 1250
    let goal: Int = 2500

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
    let name: String
    let calories: Int
    let time: String

    var body: some View {
        HStack {
            Image(systemName: "photo")
                .font(.title)
                .frame(width: 44, height: 44)
                .background(.quaternary)
                .clipShape(RoundedRectangle(cornerRadius: 8))

            VStack(alignment: .leading) {
                Text(name)
                    .fontWeight(.medium)
                Label("\(calories) cal", systemImage: "flame.fill")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }

            Spacer()

            Text(time)
                .font(.caption)
                .foregroundStyle(.tertiary)
        }
    }
}

// MARK: - Placeholder Views for Other Tabs
struct ProgressView: View {
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
}
