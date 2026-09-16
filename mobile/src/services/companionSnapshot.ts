/**
 * Writes today's Home snapshot for widgets / Apple Watch. Uses the local companion-snapshot
 * module (App Group + WidgetKit + WatchConnectivity on iOS).
 */

import { Platform } from 'react-native';

import type { CompanionSnapshotWriter, WidgetSnapshotPayload } from '../domain/integrations/companions';
import { noopSnapshotWriter } from '../domain/integrations/companions';
import { widgetSnapshotPayload } from '../domain/integrations/snapshot';
import { bodyStore, diaryStore, preferencesStore, profileStore } from '../state/appStores';

interface NativeSnapshotModule {
  isAvailable: boolean;
  write(json: string): Promise<void>;
  clear(): Promise<void>;
}

function loadNative(): NativeSnapshotModule | undefined {
  if (Platform.OS !== 'ios') return undefined;
  try {
    // eslint-disable-next-line @typescript-eslint/no-require-imports
    const { requireNativeModule } = require('expo-modules-core') as typeof import('expo-modules-core');
    return requireNativeModule<NativeSnapshotModule>('CompanionSnapshot');
  } catch {
    return undefined;
  }
}

const native = loadNative();

export const snapshotWriter: CompanionSnapshotWriter = native?.isAvailable
  ? {
      isAvailable: true,
      async write(snapshot: WidgetSnapshotPayload) {
        await native.write(JSON.stringify(snapshot));
      },
      async clear() {
        await native.clear();
      },
    }
  : noopSnapshotWriter;

let lastJson = '';

export async function publishCompanionSnapshot(): Promise<void> {
  if (!snapshotWriter.isAvailable) return;
  const payload = widgetSnapshotPayload({
    diary: diaryStore.getState(),
    profile: profileStore.getState(),
    prefs: preferencesStore.getState(),
  });
  const json = JSON.stringify(payload);
  if (json === lastJson) return;
  lastJson = json;
  await snapshotWriter.write(payload);
}

export function subscribeCompanionSnapshot(): () => void {
  const publish = () => {
    void publishCompanionSnapshot();
  };
  const unsubs = [
    diaryStore.subscribe(publish),
    profileStore.subscribe(publish),
    preferencesStore.subscribe(publish),
    bodyStore.subscribe(publish),
  ];
  publish();
  return () => unsubs.forEach((fn) => fn());
}

export { snapshotWriter as companionSnapshotWriter };
