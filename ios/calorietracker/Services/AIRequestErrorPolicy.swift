import Foundation

enum AIRequestErrorPolicy {
    /// A failed remote fallback must not hide the actionable on-device failure.
    /// Remote-primary requests keep their existing behavior and surface the fallback error.
    static func errorToSurface(
        primaryProvider: AIProvider,
        primaryError: Error,
        fallbackError: Error
    ) -> Error {
        primaryProvider.localGemmaVariant != nil ? primaryError : fallbackError
    }
}
