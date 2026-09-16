/**
 * Entitlements and usage strings for HealthKit / Health Connect, the App Group snapshot,
 * camera barcode scan, and microphone transcription.
 */
const { withAndroidManifest, withEntitlementsPlist, withInfoPlist } = require('@expo/config-plugins');

const APP_GROUP = 'group.com.apoorvdarshan.calorietracker';

function withCompanionCapabilities(config) {
  config = withInfoPlist(config, (cfg) => {
    const plist = cfg.modResults;
    plist.NSCameraUsageDescription =
      plist.NSCameraUsageDescription ??
      'Fud AI uses the camera to scan meal barcodes and photograph food.';
    plist.NSMicrophoneUsageDescription =
      plist.NSMicrophoneUsageDescription ??
      'Fud AI records a short voice clip so Whisper can transcribe your meal.';
    plist.NSHealthShareUsageDescription =
      plist.NSHealthShareUsageDescription ??
      'Fud AI reads steps, weight and nutrition you choose to sync with Apple Health.';
    plist.NSHealthUpdateUsageDescription =
      plist.NSHealthUpdateUsageDescription ??
      'Fud AI writes nutrition, weight and body measurements you log to Apple Health.';
    plist.AppGroupIdentifier = APP_GROUP;
    return cfg;
  });

  config = withEntitlementsPlist(config, (cfg) => {
    const entitlements = cfg.modResults;
    const groups = new Set([...(entitlements['com.apple.security.application-groups'] ?? []), APP_GROUP]);
    entitlements['com.apple.security.application-groups'] = [...groups];
    entitlements['com.apple.developer.healthkit'] = true;
    return cfg;
  });

  config = withAndroidManifest(config, (cfg) => {
    const manifest = cfg.modResults.manifest;
    manifest['uses-permission'] = manifest['uses-permission'] ?? [];
    const add = (name) => {
      if (!manifest['uses-permission'].some((p) => p.$['android:name'] === name)) {
        manifest['uses-permission'].push({ $: { 'android:name': name } });
      }
    };
    add('android.permission.CAMERA');
    add('android.permission.RECORD_AUDIO');
    add('android.permission.health.READ_STEPS');
    add('android.permission.health.WRITE_NUTRITION');
    add('android.permission.health.WRITE_WEIGHT');
    add('android.permission.health.WRITE_BODY_FAT');
    return cfg;
  });

  return config;
}

module.exports = withCompanionCapabilities;
