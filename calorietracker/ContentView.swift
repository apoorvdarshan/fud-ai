//
//  ContentView.swift
//  calorietracker
//
//  Created by Apoorv Darshan on 05/02/26.
//

import SwiftUI

// MARK: - Color Theme (Cal AI Inspired - Light Theme)
extension Color {
    static let appBackground = Color(red: 0.98, green: 0.98, blue: 0.98)
    static let cardBackground = Color.white
    static let cardBorder = Color(red: 0.92, green: 0.92, blue: 0.92)
    static let textPrimary = Color(red: 0.1, green: 0.1, blue: 0.1)
    static let textSecondary = Color(red: 0.5, green: 0.5, blue: 0.5)
    static let textTertiary = Color(red: 0.7, green: 0.7, blue: 0.7)
    static let proteinColor = Color(red: 0.95, green: 0.45, blue: 0.35)
    static let carbsColor = Color(red: 0.95, green: 0.75, blue: 0.3)
    static let fatColor = Color(red: 0.35, green: 0.55, blue: 0.9)
    static let streakOrange = Color(red: 1.0, green: 0.6, blue: 0.2)
    static let progressGray = Color(red: 0.93, green: 0.93, blue: 0.93)
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
        .tint(.textPrimary)
    }
}

// MARK: - Home View (Main Dashboard)
struct HomeView: View {
    var body: some View {
        ZStack(alignment: .bottomTrailing) {
            Color.appBackground.ignoresSafeArea()

            ScrollView(showsIndicators: false) {
                VStack(spacing: 20) {
                    // Header
                    HeaderView()

                    // Week Selector
                    WeekSelectorView()

                    // Calorie Card
                    CalorieCard()

                    // Macro Pills
                    MacroPillsView()

                    // Recently Uploaded
                    RecentlyUploadedSection()
                }
                .padding(.horizontal, 20)
                .padding(.bottom, 100)
            }

            // Floating Add Button
            FloatingAddButton()
        }
    }
}

// MARK: - Header View
struct HeaderView: View {
    var body: some View {
        HStack {
            // Logo
            HStack(spacing: 6) {
                Image(systemName: "apple.logo")
                    .font(.system(size: 22, weight: .medium))
                    .foregroundColor(.textPrimary)

                Text("Cal AI")
                    .font(.system(size: 24, weight: .bold, design: .rounded))
                    .foregroundColor(.textPrimary)
            }

            Spacer()

            // Streak Badge
            HStack(spacing: 4) {
                Image(systemName: "flame.fill")
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundColor(.streakOrange)
                Text("15")
                    .font(.system(size: 15, weight: .semibold, design: .rounded))
                    .foregroundColor(.textPrimary)
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 6)
            .background(Color.cardBackground)
            .clipShape(Capsule())
            .overlay(
                Capsule()
                    .stroke(Color.cardBorder, lineWidth: 1)
            )
        }
        .padding(.top, 8)
    }
}

// MARK: - Week Selector View
struct WeekSelectorView: View {
    let days = ["Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"]
    let dates = [10, 11, 12, 13, 14, 15, 16]
    @State private var selectedIndex = 3 // Wednesday

    var body: some View {
        HStack(spacing: 0) {
            ForEach(0..<7, id: \.self) { index in
                VStack(spacing: 6) {
                    Text(days[index])
                        .font(.system(size: 11, weight: .medium, design: .rounded))
                        .foregroundColor(index == selectedIndex ? .textPrimary : .textTertiary)

                    Text("\(dates[index])")
                        .font(.system(size: 14, weight: .semibold, design: .rounded))
                        .foregroundColor(index == selectedIndex ? .white : .textPrimary)
                        .frame(width: 32, height: 32)
                        .background(
                            Circle()
                                .fill(index == selectedIndex ? Color.textPrimary : Color.clear)
                        )
                }
                .frame(maxWidth: .infinity)
                .onTapGesture {
                    withAnimation(.easeInOut(duration: 0.2)) {
                        selectedIndex = index
                    }
                }
            }
        }
        .padding(.vertical, 12)
        .padding(.horizontal, 8)
        .background(Color.cardBackground)
        .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: 16, style: .continuous)
                .stroke(Color.cardBorder, lineWidth: 1)
        )
    }
}

// MARK: - Calorie Card
struct CalorieCard: View {
    let eaten: Int = 1250
    let goal: Int = 2500

    var progress: CGFloat {
        CGFloat(eaten) / CGFloat(goal)
    }

    var body: some View {
        HStack {
            // Calorie Text
            VStack(alignment: .leading, spacing: 4) {
                HStack(alignment: .firstTextBaseline, spacing: 0) {
                    Text("\(eaten)")
                        .font(.system(size: 48, weight: .bold, design: .rounded))
                        .foregroundColor(.textPrimary)

                    Text("/\(goal)")
                        .font(.system(size: 20, weight: .medium, design: .rounded))
                        .foregroundColor(.textSecondary)
                }

                Text("Calories eaten")
                    .font(.system(size: 14, weight: .medium, design: .rounded))
                    .foregroundColor(.textSecondary)
            }

            Spacer()

            // Circular Progress
            ZStack {
                Circle()
                    .stroke(Color.progressGray, lineWidth: 8)
                    .frame(width: 64, height: 64)

                Circle()
                    .trim(from: 0, to: progress)
                    .stroke(Color.textPrimary, style: StrokeStyle(lineWidth: 8, lineCap: .round))
                    .frame(width: 64, height: 64)
                    .rotationEffect(.degrees(-90))

                Image(systemName: "flame.fill")
                    .font(.system(size: 20, weight: .medium))
                    .foregroundColor(.textPrimary)
            }
        }
        .padding(20)
        .background(Color.cardBackground)
        .clipShape(RoundedRectangle(cornerRadius: 20, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: 20, style: .continuous)
                .stroke(Color.cardBorder, lineWidth: 1)
        )
    }
}

// MARK: - Macro Pills View
struct MacroPillsView: View {
    var body: some View {
        HStack(spacing: 12) {
            MacroPill(
                label: "Protein eaten",
                current: 75,
                goal: 150,
                unit: "g",
                color: .proteinColor,
                icon: "fork.knife"
            )

            MacroPill(
                label: "Carbs eaten",
                current: 138,
                goal: 275,
                unit: "g",
                color: .carbsColor,
                icon: "leaf.fill"
            )

            MacroPill(
                label: "Fat eaten",
                current: 35,
                goal: 70,
                unit: "g",
                color: .fatColor,
                icon: "drop.fill"
            )
        }
    }
}

// MARK: - Macro Pill
struct MacroPill: View {
    let label: String
    let current: Int
    let goal: Int
    let unit: String
    let color: Color
    let icon: String

    var progress: CGFloat {
        min(CGFloat(current) / CGFloat(goal), 1.0)
    }

    var body: some View {
        VStack(spacing: 10) {
            // Value
            HStack(alignment: .firstTextBaseline, spacing: 0) {
                Text("\(current)")
                    .font(.system(size: 20, weight: .bold, design: .rounded))
                    .foregroundColor(.textPrimary)

                Text("/\(goal)\(unit)")
                    .font(.system(size: 11, weight: .medium, design: .rounded))
                    .foregroundColor(.textSecondary)
            }

            // Label
            Text(label)
                .font(.system(size: 10, weight: .medium, design: .rounded))
                .foregroundColor(.textSecondary)
                .lineLimit(1)

            // Circular Progress with Icon
            ZStack {
                Circle()
                    .stroke(Color.progressGray, lineWidth: 4)
                    .frame(width: 44, height: 44)

                Circle()
                    .trim(from: 0, to: progress)
                    .stroke(color, style: StrokeStyle(lineWidth: 4, lineCap: .round))
                    .frame(width: 44, height: 44)
                    .rotationEffect(.degrees(-90))

                Image(systemName: icon)
                    .font(.system(size: 16, weight: .medium))
                    .foregroundColor(color)
            }
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 16)
        .background(Color.cardBackground)
        .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: 16, style: .continuous)
                .stroke(Color.cardBorder, lineWidth: 1)
        )
    }
}

// MARK: - Recently Uploaded Section
struct RecentlyUploadedSection: View {
    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Recently uploaded")
                .font(.system(size: 18, weight: .semibold, design: .rounded))
                .foregroundColor(.textPrimary)

            VStack(spacing: 12) {
                FoodCard(
                    imageName: "photo",
                    name: "Grilled Salmon",
                    calories: 550,
                    protein: 35,
                    carbs: 40,
                    fat: 28,
                    time: "12:37pm"
                )

                FoodCard(
                    imageName: "photo",
                    name: "Caesar Salad",
                    calories: 330,
                    protein: 8,
                    carbs: 20,
                    fat: 18,
                    time: "6:21pm"
                )

                FoodCard(
                    imageName: "photo",
                    name: "Protein Smoothie",
                    calories: 280,
                    protein: 24,
                    carbs: 32,
                    fat: 6,
                    time: "8:15am"
                )
            }
        }
    }
}

// MARK: - Food Card
struct FoodCard: View {
    let imageName: String
    let name: String
    let calories: Int
    let protein: Int
    let carbs: Int
    let fat: Int
    let time: String

    var body: some View {
        HStack(spacing: 14) {
            // Food Image Placeholder
            RoundedRectangle(cornerRadius: 12, style: .continuous)
                .fill(Color.progressGray)
                .frame(width: 64, height: 64)
                .overlay(
                    Image(systemName: imageName)
                        .font(.system(size: 24))
                        .foregroundColor(.textTertiary)
                )

            // Food Info
            VStack(alignment: .leading, spacing: 6) {
                HStack {
                    Text(name)
                        .font(.system(size: 16, weight: .semibold, design: .rounded))
                        .foregroundColor(.textPrimary)

                    Spacer()

                    Text(time)
                        .font(.system(size: 12, weight: .medium, design: .rounded))
                        .foregroundColor(.textTertiary)
                }

                // Calories
                HStack(spacing: 4) {
                    Image(systemName: "flame.fill")
                        .font(.system(size: 12, weight: .medium))
                        .foregroundColor(.streakOrange)
                    Text("\(calories) Calories")
                        .font(.system(size: 13, weight: .medium, design: .rounded))
                        .foregroundColor(.textSecondary)
                }

                // Macros
                HStack(spacing: 12) {
                    MacroLabel(value: protein, unit: "g", color: .proteinColor)
                    MacroLabel(value: carbs, unit: "g", color: .carbsColor)
                    MacroLabel(value: fat, unit: "g", color: .fatColor)
                }
            }
        }
        .padding(14)
        .background(Color.cardBackground)
        .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: 16, style: .continuous)
                .stroke(Color.cardBorder, lineWidth: 1)
        )
    }
}

// MARK: - Macro Label
struct MacroLabel: View {
    let value: Int
    let unit: String
    let color: Color

    var body: some View {
        HStack(spacing: 4) {
            Circle()
                .fill(color)
                .frame(width: 8, height: 8)
            Text("\(value)\(unit)")
                .font(.system(size: 12, weight: .medium, design: .rounded))
                .foregroundColor(.textSecondary)
        }
    }
}

// MARK: - Floating Add Button
struct FloatingAddButton: View {
    var body: some View {
        Button(action: {}) {
            Image(systemName: "plus")
                .font(.system(size: 24, weight: .semibold))
                .foregroundColor(.white)
                .frame(width: 56, height: 56)
                .background(Color.textPrimary)
                .clipShape(Circle())
                .shadow(color: Color.black.opacity(0.15), radius: 8, x: 0, y: 4)
        }
        .padding(.trailing, 20)
        .padding(.bottom, 100)
    }
}

// MARK: - Placeholder Views for Other Tabs
struct ProgressView: View {
    var body: some View {
        ZStack {
            Color.appBackground.ignoresSafeArea()
            Text("Progress")
                .font(.system(size: 24, weight: .bold, design: .rounded))
                .foregroundColor(.textPrimary)
        }
    }
}

struct GroupsView: View {
    var body: some View {
        ZStack {
            Color.appBackground.ignoresSafeArea()
            Text("Groups")
                .font(.system(size: 24, weight: .bold, design: .rounded))
                .foregroundColor(.textPrimary)
        }
    }
}

struct ProfileView: View {
    var body: some View {
        ZStack {
            Color.appBackground.ignoresSafeArea()
            Text("Profile")
                .font(.system(size: 24, weight: .bold, design: .rounded))
                .foregroundColor(.textPrimary)
        }
    }
}

#Preview {
    ContentView()
}
