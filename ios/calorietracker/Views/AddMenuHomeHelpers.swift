import SwiftUI

extension HomeView {
    @ViewBuilder
    var configuredFoodAddMenuContent: some View {
        let config = AddMenuSettings.load()
        if config.usesFlatLayout {
            ForEach(config.flatMethods) { method in
                addMenuButton(for: method)
            }
        } else {
            ForEach(config.groups.filter { !$0.methods.isEmpty }) { group in
                Menu {
                    ForEach(group.methods) { method in
                        addMenuButton(for: method)
                    }
                } label: {
                    Label(group.name, systemImage: addMenuGroupIcon(for: group))
                }
            }
        }
    }

    @ViewBuilder
    func addMenuButton(for method: FoodLogMethod) -> some View {
        Button {
            presentFoodDestination {
                performFoodLogMethod(method)
            }
        } label: {
            Label(method.title, systemImage: method.systemImageName)
        }
    }

    func performFoodLogMethod(_ method: FoodLogMethod) {
        switch method {
        case .camera:
            cameraMode = .snapFoodWithContext
            isImportingPhotos = false
            captureImages = []
            contextDescription = ""
            showCamera = true
        case .photos:
            cameraMode = .snapFoodWithContext
            isImportingPhotos = true
            captureImages = []
            contextDescription = ""
            selectedPhotoItems = []
            showPhotoPicker = true
        case .barcode:
            showBarcodeScanner = true
        case .voice:
            showVoicePopover = true
        case .text:
            showTextPopover = true
        case .manual:
            showManualPopover = true
        case .siriPhrases:
            showSiriPhrases = true
        case .favorites:
            savedMealsMode = .favorites
        case .frequent:
            savedMealsMode = .frequent
        case .recent:
            savedMealsMode = .recent
        case .copyFromDay:
            showCopyFromDaySheet = true
        }
    }

    private func addMenuGroupIcon(for group: AddMenuGroupConfig) -> String {
        group.methods.first?.systemImageName ?? "folder.fill"
    }
}

struct FoodLogMethodRequest: Identifiable, Equatable, Sendable {
    let id = UUID()
    let method: FoodLogMethod
}
