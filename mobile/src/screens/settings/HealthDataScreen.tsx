/**
 * Settings → Health & Data. HealthKit / Health Connect toggle actually requests permission
 * and writes. Companions report connected / available / denied when the bridge works.
 * Siri stays native-only. Diary export / import and a portable ZIP backup live here.
 */

import { useCallback, useEffect, useState } from 'react';
import { Alert, Linking, Platform, ScrollView, View } from 'react-native';

import { BottomSheet } from '../../components/BottomSheet';
import { PickerSheet } from '../../components/PickerSheet';
import { AppText, Card, PrimaryButton } from '../../components/primitives';
import { SettingsRow, SettingsSection, SettingsToggleRow } from '../../components/SettingsRow';
import {
  companionAvailabilityLabel,
  companionStatuses,
  healthServiceName,
  type CompanionAvailability,
  type HealthAuthorizationStatus,
} from '../../domain/integrations/companions';
import {
  diaryExportFormatLabel,
  diaryExportFormats,
  diaryExportRangeLabel,
  diaryExportRanges,
  resolveDiaryExportRange,
  type DiaryExportFormat,
  type DiaryExportRange,
} from '../../domain/diary/diaryExport';
import type { DiaryImportMode, DiaryImportPreview } from '../../domain/diary/diaryImport';
import { snapshotWriter } from '../../services/companionSnapshot';
import {
  commitDiaryImport,
  pickDiaryImportFile,
  previewDiaryImport,
  restoreLocalBackup,
  shareDiaryExport,
  shareLocalBackup,
} from '../../services/diaryShare';
import { healthSync } from '../../services/health';
import { deleteAllFoodImages } from '../../services/foodImageStore';
import { bodyStore, chatStore, diaryStore, setPreferences, useDiary, usePreferences, workoutsStore } from '../../state/appStores';
import { useTheme } from '../../theme';

const platform = Platform.OS === 'ios' ? 'ios' : 'android';

function healthAvailability(status: HealthAuthorizationStatus, enabled: boolean): CompanionAvailability {
  if (!healthSync.isAvailable || status === 'unavailable') return 'nativeOnly';
  if (status === 'denied') return 'denied';
  if (status === 'authorized' && enabled) return 'connected';
  return 'available';
}

export function HealthDataScreen() {
  const theme = useTheme();
  const prefs = usePreferences((p) => p);
  const diary = useDiary((s) => s);
  const health = healthServiceName(platform);
  const [auth, setAuth] = useState<HealthAuthorizationStatus>(healthSync.isAvailable ? 'notDetermined' : 'unavailable');
  const [busy, setBusy] = useState(false);
  const [exportSheet, setExportSheet] = useState(false);
  const [exportFormat, setExportFormat] = useState<DiaryExportFormat>('json');
  const [exportRange, setExportRange] = useState<DiaryExportRange>('thisWeek');
  const [formatPicker, setFormatPicker] = useState(false);
  const [rangePicker, setRangePicker] = useState(false);
  const [importPreview, setImportPreview] = useState<DiaryImportPreview | null>(null);

  const refreshAuth = useCallback(async () => {
    setAuth(await healthSync.authorizationStatus());
  }, []);

  useEffect(() => {
    void refreshAuth();
  }, [refreshAuth]);

  const healthState = healthAvailability(auth, prefs.healthKitEnabled);
  const statuses = companionStatuses(platform, {
    health: healthState,
    widgets: snapshotWriter.isAvailable ? 'available' : 'nativeOnly',
    watch: snapshotWriter.isAvailable ? 'available' : 'nativeOnly',
    siri: 'nativeOnly',
  });

  const toggleHealth = async (value: boolean) => {
    if (busy) return;
    setBusy(true);
    try {
      if (!value) {
        setPreferences({ healthKitEnabled: false });
        return;
      }
      if (!healthSync.isAvailable) {
        Alert.alert(health, `${health} is not available in this build. Use a development build, not Expo Go.`);
        setPreferences({ healthKitEnabled: false });
        return;
      }
      const granted = await healthSync.requestAuthorization();
      await refreshAuth();
      const status = await healthSync.authorizationStatus();
      if (!granted && status !== 'authorized') {
        setPreferences({ healthKitEnabled: false });
        Alert.alert(health, `${health} permission was not granted.`, [
          { text: 'Not now', style: 'cancel' },
          { text: 'Open Settings', onPress: () => void Linking.openSettings() },
        ]);
        return;
      }
      setPreferences({ healthKitEnabled: true });
    } finally {
      setBusy(false);
    }
  };

  const exportDiary = async () => {
    const { start, end } = resolveDiaryExportRange(exportRange, new Date(), diary.foodEntries, diary.waterEntries);
    try {
      const shared = await shareDiaryExport(exportFormat, start, end);
      if (!shared) Alert.alert('Export Diary', 'Nothing to export in that range, or sharing is unavailable.');
      else setExportSheet(false);
    } catch (error) {
      Alert.alert('Export Diary', error instanceof Error ? error.message : 'Could not share the diary.');
    }
  };

  const importDiary = async () => {
    try {
      const uri = await pickDiaryImportFile();
      if (!uri) return;
      setImportPreview(await previewDiaryImport(uri));
    } catch (error) {
      Alert.alert('Import Diary', error instanceof Error ? error.message : 'Could not read that file.');
    }
  };

  const applyImport = async (mode: DiaryImportMode) => {
    if (!importPreview || busy) return;
    setBusy(true);
    try {
      await commitDiaryImport(importPreview, mode);
      setImportPreview(null);
      Alert.alert('Import Diary', `Imported ${importPreview.entries.length} food ${importPreview.entries.length === 1 ? 'entry' : 'entries'}.`);
    } catch (error) {
      Alert.alert('Import Diary', error instanceof Error ? error.message : 'Could not import that diary.');
    } finally {
      setBusy(false);
    }
  };

  const backupOut = async () => {
    try {
      await shareLocalBackup();
    } catch (error) {
      Alert.alert('Backup', error instanceof Error ? error.message : 'Could not create a backup file.');
    }
  };

  const backupIn = async () => {
    try {
      const uri = await pickDiaryImportFile();
      if (!uri) return;
      Alert.alert('Restore Backup?', 'This replaces preferences, profile, diary, and meal photos on this device from the file. Missing collections are left as they are.', [
        { text: 'Cancel', style: 'cancel' },
        {
          text: 'Restore',
          style: 'destructive',
          onPress: () => {
            void restoreLocalBackup(uri)
              .then(() => Alert.alert('Backup', 'Restored from the selected file.'))
              .catch((error: unknown) => Alert.alert('Backup', error instanceof Error ? error.message : 'Could not restore that backup.'));
          },
        },
      ]);
    } catch (error) {
      Alert.alert('Backup', error instanceof Error ? error.message : 'Could not open that file.');
    }
  };

  const clearAll = () =>
    Alert.alert('Clear all data?', 'Deletes your food, water, fasting, weight, body-fat, workout and Coach history on this device. Your profile and settings stay.', [
      { text: 'Cancel', style: 'cancel' },
      {
        text: 'Delete',
        style: 'destructive',
        onPress: () => {
          diaryStore.dispatch({ type: 'clearAll' });
          bodyStore.dispatch({ type: 'clearAll' });
          workoutsStore.dispatch({ type: 'clearAll' });
          chatStore.dispatch({ type: 'reset' });
          deleteAllFoodImages();
        },
      },
    ]);

  return (
    <>
      <ScrollView contentContainerStyle={{ padding: theme.spacing.lg, gap: theme.spacing.xl, backgroundColor: theme.colors.appBackground }}>
        <SettingsSection
          header={health}
          footer={
            healthState === 'nativeOnly'
              ? `${health} needs a development build with the HealthSync module. Expo Go cannot sync.`
              : healthState === 'denied'
                ? `${health} permission was denied. Enable it in system settings, then turn the switch on again.`
                : `When this is on, Fud AI writes nutrition, weight and body measurements to ${health}.`
          }
        >
          <SettingsToggleRow
            icon="heart.fill"
            title={`Sync with ${health}`}
            value={prefs.healthKitEnabled}
            onValueChange={(v) => void toggleHealth(v)}
          />
          <SettingsRow icon="heart" title="Status" value={companionAvailabilityLabel(healthState)} chevron={false} />
        </SettingsSection>

        <SettingsSection header="Companions">
          {statuses
            .filter((s) => s.id !== 'health')
            .map((status) => (
              <View key={status.id}>
                <SettingsRow
                  icon={status.id === 'watch' ? 'applewatch' : status.id === 'widgets' ? 'apps.iphone' : 'waveform'}
                  title={status.title}
                  value={companionAvailabilityLabel(status.availability)}
                  chevron={false}
                />
                <AppText variant="caption" tone="secondary" style={{ paddingHorizontal: theme.spacing.lg, paddingBottom: 10, marginTop: -4 }}>
                  {status.detail}
                </AppText>
              </View>
            ))}
        </SettingsSection>

        <SettingsSection header="Diary" footer="JSON matches the native DiaryExporter 1.5 format so files can move between apps.">
          <SettingsRow icon="square.and.arrow.up" title="Export Diary" onPress={() => setExportSheet(true)} />
          <SettingsRow icon="square.and.arrow.down" title="Import Diary" onPress={() => void importDiary()} />
        </SettingsSection>

        <SettingsSection
          header="Backup"
          footer="A ZIP of backup.json you can AirDrop or save. Automatic iCloud Drive backup stays in the native iOS app — no secrets are stored in git."
        >
          <SettingsRow icon="icloud.and.arrow.up" title="Export Backup File" onPress={() => void backupOut()} />
          <SettingsRow icon="icloud.and.arrow.down" title="Restore Backup File" onPress={() => void backupIn()} />
        </SettingsSection>

        <SettingsSection header="Data Management">
          <SettingsRow icon="trash" title="Clear All Data" destructive onPress={clearAll} chevron={false} />
        </SettingsSection>

        <Card>
          <AppText variant="caption" tone="secondary">
            Everything you log stays on this device unless you share a file. AI providers only receive what you send for analysis or transcription.
          </AppText>
        </Card>
      </ScrollView>

      <BottomSheet visible={exportSheet} title="Export Diary" onDismiss={() => setExportSheet(false)}>
        <SettingsRow title="Format" value={diaryExportFormatLabel[exportFormat]} onPress={() => setFormatPicker(true)} />
        <SettingsRow title="Range" value={diaryExportRangeLabel[exportRange]} onPress={() => setRangePicker(true)} />
        <PrimaryButton title="Share" onPress={() => void exportDiary()} />
      </BottomSheet>
      <PickerSheet<DiaryExportFormat>
        visible={formatPicker}
        title="Format"
        options={diaryExportFormats.map((format) => ({ value: format, label: diaryExportFormatLabel[format] }))}
        selected={exportFormat}
        onSelect={setExportFormat}
        onDismiss={() => setFormatPicker(false)}
      />
      <PickerSheet<DiaryExportRange>
        visible={rangePicker}
        title="Range"
        options={diaryExportRanges.filter((range) => range !== 'custom').map((range) => ({ value: range, label: diaryExportRangeLabel[range] }))}
        selected={exportRange}
        onSelect={setExportRange}
        onDismiss={() => setRangePicker(false)}
      />
      <BottomSheet visible={importPreview !== null} title="Import Diary" onDismiss={() => setImportPreview(null)}>
        <AppText variant="subheadline" tone="secondary">
          {importPreview
            ? `${importPreview.entries.length} food · ${importPreview.waterEntries.length} water · ${importPreview.startDate.toLocaleDateString()} – ${importPreview.endDate.toLocaleDateString()}`
            : ''}
        </AppText>
        <PrimaryButton title="Replace dates in file" onPress={() => applyImport('replaceDateRange')} />
        <PrimaryButton title="Add as new entries" onPress={() => applyImport('addAsNew')} />
      </BottomSheet>
    </>
  );
}
