import SwiftUI

struct AddMenuSettingsView: View {
    @State private var config = AddMenuSettings.load()
    @State private var editMode: EditMode = .active
    @State private var methodPickerTarget: AddMenuMethodTarget?

    var body: some View {
        Form {
            Section {
                Stepper(
                    value: Binding(
                        get: { config.groups.count },
                        set: updateGroupCount
                    ),
                    in: 0...3
                ) {
                    Label {
                        Text("Groups")
                    } icon: {
                        Image(systemName: "square.stack.3d.up.fill")
                            .foregroundStyle(AppColors.calorie)
                    }
                }
            } header: {
                Text("Food Add Menu")
            } footer: {
                Text("Customize the Home + button food menu. Water and fasting stay separate when enabled. This does not change app-icon Quick Actions.")
            }

            if !config.groups.isEmpty {
                groupOrderSection
                ForEach($config.groups) { $group in
                    groupSection(group: $group)
                }
            }

            standaloneMethodsSection

            if !hiddenMethods.isEmpty {
                Section {
                    ForEach(hiddenMethods) { method in
                        Label(method.title, systemImage: method.systemImageName)
                            .foregroundStyle(.secondary)
                    }
                } header: {
                    Text("Hidden Methods")
                } footer: {
                    Text("These logging methods are not shown on the Home + menu.")
                }
            }

            Section {
                Button("Reset to Default", role: .destructive) {
                    AddMenuSettings.reset()
                    config = .iOSDefault
                }
            }
        }
        .environment(\.editMode, $editMode)
        .navigationTitle("+ Menu")
        .navigationBarTitleDisplayMode(.inline)
        .onChange(of: config) { _, newValue in
            AddMenuSettings.save(newValue)
        }
        .sheet(item: $methodPickerTarget) { target in
            MethodAssignmentSheet(
                methods: hiddenMethods,
                onSelect: { method in
                    assign(method, to: target)
                    methodPickerTarget = nil
                },
                onCancel: { methodPickerTarget = nil }
            )
        }
    }

    private var groupOrderSection: some View {
        Section {
            ForEach(config.groups) { group in
                Label(group.name, systemImage: addMenuGroupIcon(for: group))
            }
            .onMove { from, to in
                config.groups.move(fromOffsets: from, toOffset: to)
            }
        } header: {
            Text("Group Order")
        }
    }

    private var standaloneMethodsSection: some View {
        Section {
            ForEach(config.flatMethods) { method in
                Label(method.title, systemImage: method.systemImageName)
            }
            .onMove { from, to in
                config.flatMethods.move(fromOffsets: from, toOffset: to)
            }
            .onDelete { offsets in
                config.flatMethods.remove(atOffsets: offsets)
            }

            if !hiddenMethods.isEmpty {
                Button {
                    methodPickerTarget = .standalone
                } label: {
                    Label("Add Method", systemImage: "plus.circle.fill")
                }
            }
        } header: {
            Text("Standalone Methods")
        } footer: {
            Text("These methods appear directly under +, outside any group.")
        }
    }

    @ViewBuilder
    private func groupSection(group: Binding<AddMenuGroupConfig>) -> some View {
        Section {
            TextField("Group Name", text: group.name)

            ForEach(group.wrappedValue.methods) { method in
                Label(method.title, systemImage: method.systemImageName)
            }
            .onMove { from, to in
                group.methods.wrappedValue.move(fromOffsets: from, toOffset: to)
            }
            .onDelete { offsets in
                group.methods.wrappedValue.remove(atOffsets: offsets)
            }

            if !hiddenMethods.isEmpty {
                Button {
                    methodPickerTarget = .group(group.id.wrappedValue)
                } label: {
                    Label("Add Method", systemImage: "plus.circle.fill")
                }
            }
        } header: {
            Text(group.name.wrappedValue)
        }
    }

    private var hiddenMethods: [FoodLogMethod] {
        let visible = Set(config.visibleMethods)
        return FoodLogMethod.addMenuCases.filter { !visible.contains($0) }
    }

    private func addMenuGroupIcon(for group: AddMenuGroupConfig) -> String {
        group.methods.first?.systemImageName ?? "folder.fill"
    }

    private func updateGroupCount(_ count: Int) {
        if count == 0 {
            config = AddMenuConfig(groups: [], flatMethods: config.visibleMethods)
            return
        }

        var groups = config.groups
        var standalone = config.flatMethods
        if groups.isEmpty {
            if standalone.isEmpty {
                groups = AddMenuConfig.iOSDefault.groups
            } else {
                groups = [
                    AddMenuGroupConfig(
                        name: String(localized: "New Group", comment: "Default name for new add menu group"),
                        methods: standalone
                    )
                ]
                standalone = []
            }
        }

        while groups.count < count {
            groups.append(
                AddMenuGroupConfig(
                    name: String(localized: "New Group", comment: "Default name for new add menu group"),
                    methods: []
                )
            )
        }
        while groups.count > count {
            groups.removeLast()
        }

        config = AddMenuConfig(groups: groups, flatMethods: standalone)
    }

    private func assign(_ method: FoodLogMethod, to target: AddMenuMethodTarget) {
        config = config.assigning(method, to: target)
    }
}

private struct MethodAssignmentSheet: View {
    let methods: [FoodLogMethod]
    let onSelect: (FoodLogMethod) -> Void
    let onCancel: () -> Void

    var body: some View {
        NavigationStack {
            List(methods) { method in
                Button {
                    onSelect(method)
                } label: {
                    Label(method.title, systemImage: method.systemImageName)
                }
            }
            .navigationTitle("Add Method")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel", action: onCancel)
                }
            }
        }
        .presentationDetents([.medium, .large])
    }
}
