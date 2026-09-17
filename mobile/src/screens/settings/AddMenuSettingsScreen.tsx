import { useMemo, useState } from 'react';
import { Alert, ScrollView, TextInput, View, type AlertButton } from 'react-native';

import type { SFSymbolName } from '../../components/Icon';
import { PickerSheet } from '../../components/PickerSheet';
import { AppText, Screen } from '../../components/primitives';
import { SettingsRow, SettingsSection } from '../../components/SettingsRow';
import { StepperField } from '../../components/StepperField';
import { foodLogMethodSystemImage, foodLogMethodTitle, type FoodLogMethod } from '../../domain/food/foodLogMethod';
import {
  addMenuConfigFromPrefs,
  appendFlatAddMenuMethod,
  assignAddMenuMethod,
  hiddenAddMenuMethods,
  iosDefaultAddMenuConfig,
  moveAddMenuGroup,
  moveAddMenuMethod,
  removeAddMenuMethod,
  renameAddMenuGroup,
  serializeAddMenuConfig,
  setAddMenuGroupCount,
  usesFlatAddMenu,
  visibleAddMenuMethods,
} from '../../domain/prefs/addMenu';
import { setPreferences, usePreferences } from '../../state/appStores';
import { useTheme } from '../../theme';

/** Settings → App Settings → + Menu (`AddMenuSettingsView`). */
export function AddMenuSettingsScreen() {
  const theme = useTheme();
  const raw = usePreferences((p) => p.addMenuConfig);
  const config = useMemo(() => addMenuConfigFromPrefs(raw, { keepEmptyGroups: true }), [raw]);
  const hidden = hiddenAddMenuMethods(config);
  const [assignTo, setAssignTo] = useState<string | null>(null);

  const save = (next: typeof config) => setPreferences({ addMenuConfig: serializeAddMenuConfig(next, { keepEmptyGroups: true }) });
  const methodIcon = (method: FoodLogMethod): SFSymbolName => foodLogMethodSystemImage(method) as SFSymbolName;

  const methodActions = (list: 'flat' | string, methods: FoodLogMethod[], index: number): AlertButton[] => {
    const method = methods[index];
    if (!method) return [];
    const actions: AlertButton[] = [];
    if (index > 0) actions.push({ text: 'Move up', onPress: () => save(moveAddMenuMethod(config, list, index, index - 1)) });
    if (index < methods.length - 1) actions.push({ text: 'Move down', onPress: () => save(moveAddMenuMethod(config, list, index, index + 1)) });
    actions.push({ text: 'Hide', style: 'destructive', onPress: () => save(removeAddMenuMethod(config, list, method)) });
    actions.push({ text: 'Cancel', style: 'cancel' });
    return actions;
  };

  return (
    <Screen edges={['left', 'right']}>
      <ScrollView contentContainerStyle={{ padding: theme.spacing.lg, gap: theme.spacing.xl }}>
        <SettingsSection
          header="Food Add Menu"
          footer="Customize the Home + button food menu. Water and fasting stay separate when enabled. This does not change app-icon Quick Actions."
        >
          <View style={{ paddingHorizontal: theme.spacing.lg, paddingVertical: 10 }}>
            <StepperField
              label="Groups"
              value={String(config.groups.length)}
              onChange={(value) => {
                const parsed = Number.parseInt(value, 10);
                if (Number.isFinite(parsed)) save(setAddMenuGroupCount(config, parsed));
              }}
              step={1}
              unit=""
              accessibilityLabel="Groups"
              fractionDigits={0}
              min={0}
              max={3}
              integerOnly
            />
          </View>
        </SettingsSection>

        {usesFlatAddMenu(config) ? (
          <SettingsSection header="Flat Menu" footer="With no groups, enabled methods appear directly under +.">
            {config.flatMethods.map((method, index) => (
              <SettingsRow
                key={method}
                icon={methodIcon(method)}
                title={foodLogMethodTitle(method)}
                chevron={false}
                onPress={() => Alert.alert(foodLogMethodTitle(method), undefined, methodActions('flat', config.flatMethods, index))}
              />
            ))}
            {hidden.length > 0 ? (
              <SettingsRow icon="plus.circle.fill" title="Add Method" onPress={() => setAssignTo('flat')} />
            ) : null}
          </SettingsSection>
        ) : (
          <>
            <SettingsSection header="Group Order">
              {config.groups.map((group, index) => (
                <SettingsRow
                  key={group.id}
                  icon={methodIcon(group.methods[0] ?? 'camera')}
                  title={group.name}
                  chevron={false}
                  onPress={() => {
                    const actions: AlertButton[] = [];
                    if (index > 0) actions.push({ text: 'Move up', onPress: () => save(moveAddMenuGroup(config, index, index - 1)) });
                    if (index < config.groups.length - 1) actions.push({ text: 'Move down', onPress: () => save(moveAddMenuGroup(config, index, index + 1)) });
                    actions.push({ text: 'Cancel', style: 'cancel' });
                    Alert.alert(group.name, undefined, actions);
                  }}
                />
              ))}
            </SettingsSection>
            {config.groups.map((group) => (
              <SettingsSection key={group.id} header={group.name}>
                <View style={{ paddingHorizontal: theme.spacing.lg, paddingVertical: 8 }}>
                  <TextInput
                    value={group.name}
                    onChangeText={(name) => save(renameAddMenuGroup(config, group.id, name))}
                    placeholder="Group Name"
                    placeholderTextColor={theme.colors.placeholder}
                    style={[theme.text.body, { color: theme.colors.label, minHeight: 36 }]}
                  />
                </View>
                {group.methods.map((method, index) => (
                  <SettingsRow
                    key={method}
                    icon={methodIcon(method)}
                    title={foodLogMethodTitle(method)}
                    chevron={false}
                    onPress={() => Alert.alert(foodLogMethodTitle(method), undefined, methodActions(group.id, group.methods, index))}
                  />
                ))}
                {hidden.length > 0 ? (
                  <SettingsRow icon="plus.circle.fill" title="Add Method" onPress={() => setAssignTo(group.id)} />
                ) : null}
              </SettingsSection>
            ))}
          </>
        )}

        {hidden.length > 0 ? (
          <SettingsSection header="Hidden Methods" footer="These logging methods are not shown on the Home + menu.">
            {hidden.map((method) => (
              <SettingsRow key={method} icon={methodIcon(method)} title={foodLogMethodTitle(method)} chevron={false} />
            ))}
          </SettingsSection>
        ) : null}

        <SettingsSection>
          <SettingsRow
            title="Reset to Default"
            destructive
            chevron={false}
            onPress={() =>
              Alert.alert('Reset to Default?', 'Restore the iOS Home + food groups.', [
                { text: 'Cancel', style: 'cancel' },
                { text: 'Reset', style: 'destructive', onPress: () => save(iosDefaultAddMenuConfig) },
              ])
            }
          />
        </SettingsSection>

        <AppText variant="footnote" tone="tertiary">
          {visibleAddMenuMethods(config).length} methods on Home +
        </AppText>
      </ScrollView>

      <PickerSheet<FoodLogMethod>
        visible={assignTo !== null}
        title="Add Method"
        options={hidden.map((method) => ({ value: method, label: foodLogMethodTitle(method) }))}
        selected={undefined}
        onSelect={(method) => {
          if (assignTo === 'flat') save(appendFlatAddMenuMethod(config, method));
          else if (assignTo) save(assignAddMenuMethod(config, method, assignTo));
          setAssignTo(null);
        }}
        onDismiss={() => setAssignTo(null)}
      />
    </Screen>
  );
}
