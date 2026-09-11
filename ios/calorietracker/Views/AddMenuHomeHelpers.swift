import Foundation

struct FoodLogMethodRequest: Identifiable, Equatable, Sendable {
    let id = UUID()
    let method: FoodLogMethod
}
