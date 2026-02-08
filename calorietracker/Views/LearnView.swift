import SwiftUI

// MARK: - Sort Option
enum ArticleSortOption: String, CaseIterable {
    case defaultOrder = "Default"
    case newestFirst = "Newest First"
    case shortest = "Shortest First"
    case longest = "Longest First"
    case titleAZ = "Title A–Z"
}

// MARK: - Learn View
struct LearnView: View {
    @State private var searchText = ""
    @State private var selectedCategory: ArticleCategory? = nil
    @State private var sortOption: ArticleSortOption = .defaultOrder

    private var filteredArticles: [Article] {
        var articles = Article.allArticles

        // Filter by category
        if let category = selectedCategory {
            articles = articles.filter { $0.category == category }
        }

        // Filter by search
        if !searchText.isEmpty {
            let query = searchText.lowercased()
            articles = articles.filter {
                $0.title.lowercased().contains(query) ||
                $0.summary.lowercased().contains(query)
            }
        }

        // Sort
        switch sortOption {
        case .defaultOrder:
            break
        case .newestFirst:
            articles.sort { $0.dateAdded > $1.dateAdded }
        case .shortest:
            articles.sort { $0.readingTimeMinutes < $1.readingTimeMinutes }
        case .longest:
            articles.sort { $0.readingTimeMinutes > $1.readingTimeMinutes }
        case .titleAZ:
            articles.sort { $0.title < $1.title }
        }

        return articles
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 12) {
                    // Category filter chips
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: 8) {
                            CategoryChip(label: "All", isSelected: selectedCategory == nil) {
                                selectedCategory = nil
                            }
                            ForEach(ArticleCategory.allCases, id: \.self) { category in
                                CategoryChip(
                                    label: category.rawValue,
                                    color: category.color,
                                    isSelected: selectedCategory == category
                                ) {
                                    selectedCategory = selectedCategory == category ? nil : category
                                }
                            }
                        }
                        .padding(.horizontal)
                    }

                    // Sort menu
                    HStack {
                        Text("\(filteredArticles.count) article\(filteredArticles.count == 1 ? "" : "s")")
                            .font(.system(.subheadline, design: .rounded))
                            .foregroundStyle(.secondary)

                        Spacer()

                        Menu {
                            ForEach(ArticleSortOption.allCases, id: \.self) { option in
                                Button {
                                    sortOption = option
                                } label: {
                                    if sortOption == option {
                                        Label(option.rawValue, systemImage: "checkmark")
                                    } else {
                                        Text(option.rawValue)
                                    }
                                }
                            }
                        } label: {
                            HStack(spacing: 4) {
                                Image(systemName: "arrow.up.arrow.down")
                                Text(sortOption.rawValue)
                            }
                            .font(.system(.subheadline, design: .rounded, weight: .medium))
                            .foregroundStyle(AppColors.calorie)
                        }
                    }
                    .padding(.horizontal)

                    // Article cards
                    if filteredArticles.isEmpty {
                        VStack(spacing: 8) {
                            Image(systemName: "doc.text.magnifyingglass")
                                .font(.system(size: 36))
                                .foregroundStyle(.tertiary)
                            Text("No articles found")
                                .font(.system(.subheadline, design: .rounded))
                                .foregroundStyle(.secondary)
                        }
                        .frame(maxWidth: .infinity)
                        .padding(.top, 40)
                    } else {
                        ForEach(filteredArticles) { article in
                            NavigationLink(destination: ArticleDetailView(article: article)) {
                                ArticleCardView(article: article)
                            }
                            .buttonStyle(.plain)
                            .padding(.horizontal)
                        }
                    }
                }
                .padding(.vertical)
            }
            .background(AppColors.appBackground)
            .navigationTitle("")
            .navigationBarTitleDisplayMode(.inline)
            .searchable(text: $searchText, prompt: "Search articles")
        }
    }
}

// MARK: - Category Chip
struct CategoryChip: View {
    let label: String
    var color: Color = .primary
    let isSelected: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Text(label)
                .font(.system(.subheadline, design: .rounded, weight: .medium))
                .padding(.horizontal, 14)
                .padding(.vertical, 8)
                .background(isSelected ? color.opacity(0.2) : AppColors.appCard)
                .foregroundStyle(isSelected ? color : .secondary)
                .clipShape(Capsule())
                .overlay(
                    Capsule()
                        .strokeBorder(isSelected ? color.opacity(0.4) : Color.clear, lineWidth: 1)
                )
        }
    }
}

// MARK: - Article Card
struct ArticleCardView: View {
    let article: Article

    var body: some View {
        VStack(spacing: 0) {
            // Image thumbnail
            Color.clear
                .frame(height: 180)
                .overlay {
                    AsyncImage(url: URL(string: article.imageURL)) { phase in
                        switch phase {
                        case .success(let image):
                            image
                                .resizable()
                                .aspectRatio(contentMode: .fill)
                        case .failure:
                            imagePlaceholder
                        case .empty:
                            imagePlaceholder
                                .overlay(ProgressView().tint(.white))
                        @unknown default:
                            imagePlaceholder
                        }
                    }
                }
                .clipped()

            // Article info
            VStack(alignment: .leading, spacing: 6) {
                Text(article.title)
                    .font(.system(.headline, design: .rounded, weight: .semibold))
                    .lineLimit(2)
                    .multilineTextAlignment(.leading)

                Text(article.summary)
                    .font(.system(.subheadline, design: .rounded))
                    .foregroundStyle(.secondary)
                    .lineLimit(2)
                    .multilineTextAlignment(.leading)

                HStack(spacing: 8) {
                    Label("\(article.readingTimeMinutes) min read", systemImage: "clock")
                        .font(.system(.caption, design: .rounded))
                        .foregroundStyle(.tertiary)

                    Text("·")
                        .foregroundStyle(.tertiary)

                    Text(article.formattedDate)
                        .font(.system(.caption, design: .rounded))
                        .foregroundStyle(.tertiary)

                    Text(article.category.rawValue)
                        .font(.system(.caption, design: .rounded, weight: .medium))
                        .padding(.horizontal, 8)
                        .padding(.vertical, 3)
                        .background(article.category.color.opacity(0.15))
                        .foregroundStyle(article.category.color)
                        .clipShape(Capsule())
                }
            }
            .padding()
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .background(AppColors.appCard)
        .clipShape(RoundedRectangle(cornerRadius: 16))
    }

    private var imagePlaceholder: some View {
        ZStack {
            LinearGradient(
                colors: article.category.gradient,
                startPoint: .topLeading,
                endPoint: .bottomTrailing
            )
            Image(systemName: article.icon)
                .font(.system(size: 40))
                .foregroundStyle(.white.opacity(0.7))
        }
    }
}

// MARK: - Article Detail
struct ArticleDetailView: View {
    let article: Article

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                Text(article.title)
                    .font(.system(.title2, design: .rounded, weight: .bold))
                    .frame(maxWidth: .infinity, alignment: .leading)

                // Content paragraphs
                ForEach(Array(article.contentParagraphs.enumerated()), id: \.offset) { _, paragraph in
                    let trimmed = paragraph.trimmingCharacters(in: .whitespacesAndNewlines)
                    if trimmed.hasPrefix("## ") {
                        Text(String(trimmed.dropFirst(3)))
                            .font(.system(.headline, design: .rounded, weight: .bold))
                            .padding(.top, 4)
                    } else {
                        Text(trimmed)
                            .font(.system(.body, design: .rounded))
                            .foregroundStyle(.secondary)
                    }
                }
            }
            .padding()
        }
        .background(AppColors.appBackground)
        .navigationBarTitleDisplayMode(.inline)
    }

}
