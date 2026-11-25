import Foundation

nonisolated enum ExerciseImageReport {
    static let noteLimit = 400

    static func cleanNote(_ note: String) -> String {
        String(String.UnicodeScalarView(note.trimmingCharacters(in: .whitespacesAndNewlines).unicodeScalars.prefix(noteLimit)))
    }

    static func text(exercise: String, id: String, visual: String, version: String, note: String) -> String {
        "Exercise image: \(exercise)\nCatalog ID: \(id)\nPlatform: iOS · Fud AI \(version)\nVisual: \(visual)\n\n\(cleanNote(note))"
    }

    static func issueURL(exercise: String, id: String, visual: String, version: String, note: String) -> URL? {
        let report = text(exercise: exercise, id: id, visual: visual, version: version, note: note)
        // Indent all submitted text so an @mention or Markdown link stays literal.
        let body = report.components(separatedBy: .newlines).map { "    \($0)" }.joined(separator: "\n")
        var url = URLComponents(string: "https://github.com/apoorvdarshan/fud-ai/issues/new")
        url?.queryItems = [
            URLQueryItem(name: "template", value: "exercise_visual.md"),
            URLQueryItem(name: "title", value: "Exercise image: \(exercise)"),
            URLQueryItem(name: "body", value: body),
        ]
        // Escape literal pluses for form-style query decoders.
        if let encoded = url?.percentEncodedQuery {
            url?.percentEncodedQuery = encoded.replacingOccurrences(of: "+", with: "%2B")
        }
        return url?.url
    }
}
