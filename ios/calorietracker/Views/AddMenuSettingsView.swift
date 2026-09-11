import SwiftUI

struct AddMenuSettingsView: View {
    @State private var config = AddMenuSettings.load()
    @State private var editMode: EditMode = .active
    @State private var methodPickerGroupID: UUID?

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

                if config.usesFlatLayout {
                    flatMethodsSection
                } else {
                    groupOrderSection
                    ForEach($config.groups) { $group in
                        groupSection(group: $group)
                    }
                }

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

                Button("Reset to Default", role: .destructive) {
                    AddMenuSettings.reset()
                    config = .iOSDefault
                }
            } header: {
                Text("Food Add Menu")
            } footer: {
                Text("Customize the Home + button food menu. Water and fasting stay separate when enabled. This does not change app-icon Quick Actions.")
            }
        }
        .environment(\.editMode, $editMode)
        .navigationTitle("+ Menu")
        .navigationBarTitleDisplayMode(.inline)
        .onChange(of: config) { _, newValue in
            AddMenuSettings.save(newValue)
        }
        .sheet(item: methodPickerBinding) { target in
            MethodAssignmentSheet(
                methods: hiddenMethods,
                onSelect: { method in
                    assign(method, to: target.id)
                    methodPickerGroupID = nil
                },
                onCancel: { methodPickerGroupID = nil }
            )
        }
    }

    private var methodPickerBinding: Binding<GroupPickerTarget?> {
        Binding(
            get: { methodPickerGroupID.map(GroupPickerTarget.init) },
            set: { methodPickerGroupID = $0?.id }
        )
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

    private var flatMethodsSection: some View {
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
                    if let method = hiddenMethods.first {
                        config.flatMethods.append(method)
                    }
                } label: {
                    Label("Add Method", systemImage: "plus.circle.fill")
                }
            }
        } header: {
            Text("Flat Menu")
        } footer: {
            Text("With no groups, enabled methods appear directly under +.")
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
                    methodPickerGroupID = group.id.wrappedValue
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
            let visible = config.groups.isEmpty ? config.flatMethods : config.visibleMethods
            config = AddMenuConfig(groups: [], flatMethods: visible)
            return
        }

        var groups = config.groups
        if groups.isEmpty {
            let flat = config.flatMethods
            if flat.isEmpty {
                groups = AddMenuConfig.iOSDefault.groups
            } else {
                groups = [
                    AddMenuGroupConfig(
                        name: String(localized: "New Group", comment: "Default name for new add menu group"),
                        methods: flat
                    )
                ]
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

        config = AddMenuConfig(groups: groups, flatMethods: [])
    }

    private func assign(_ method: FoodLogMethod, to groupID: UUID) {
        guard let index = config.groups.firstIndex(where: { $0.id == groupID }) else { return }
        config.flatMethods.removeAll { $0 == method }
        for idx in config.groups.indices {
            config.groups[idx].methods.removeAll { $0 == method }
        }
        config.groups[index].methods.append(method)
    }
}

private struct GroupPickerTarget: Identifiable {
    let id: UUID
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
