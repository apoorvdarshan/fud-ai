import Testing
@testable import calorietracker

struct FoodLogSheetIdentityTests {
    @Test func analyzingAndResultShareSheetIdentity() {
        let foodLog = HomeView.ActiveSheet.foodResult.id
        #expect(HomeView.ActiveSheet.analyzing.id == foodLog)
        #expect(HomeView.ActiveSheet.analyzingText.id == foodLog)
        #expect(HomeView.ActiveSheet.lookingUpBarcode.id == foodLog)
        #expect(HomeView.ActiveSheet.editFood.id != foodLog)
        #expect(HomeView.ActiveSheet.importSharedMeal.id != foodLog)
    }
}
