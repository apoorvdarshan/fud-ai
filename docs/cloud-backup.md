# Optional iCloud / Google Drive backup

Off by default in Settings → Data Management. Sign-in runs only when the user turns the toggle on, or taps Restore on the onboarding Welcome back screen ("Used Fud AI before?"). The app never checks iCloud or Drive by itself at install. On iOS, picking Restore from iCloud there fetches only the `current` record's hash field, never the asset, to show the backup's date; the restore runs when the user taps Continue. Like any restore, this turns backup on. When Apple Health is picked too, the restore runs first and the Health import then adds only what the backup is missing. Delete All Data turns backup off, so the emptied phone never uploads over the cloud copy.

## iOS

1. Enable the iCloud CloudKit container `iCloud.com.apoorvdarshan.calorietracker` in the Apple Developer portal and Xcode.
2. Deploy the `FudAIBackup` record type to the CloudKit **Production** schema before shipping (see below).
3. The phone’s iCloud account is enough. There is no Sign in with Apple.

### CloudKit schema

**Container:** `iCloud.com.apoorvdarshan.calorietracker`

**Private Database — Development (done):**

| Record type | Field | Type |
|-------------|-------|------|
| `FudAIBackup` | `backupAsset` | Asset |
| `FudAIBackup` | `contentSha256` | String |

The app writes a single record named `current` in the user’s private database (`CloudBackupService.swift`). The smoke test uses a separate record named `smoke-test` so it never overwrites or deletes the user’s real backup.

**Production — deploy before App Store release:**

1. Open [CloudKit Console](https://icloud.developer.apple.com/) and select container `iCloud.com.apoorvdarshan.calorietracker`.
2. Choose **Schema** in the left sidebar.
3. Confirm **Development** shows record type `FudAIBackup` with fields `backupAsset` (Asset) and `contentSha256` (String). If Development is missing them, add the record type and fields there first, then test on a Development build.
4. Open the **Deploy Schema Changes…** action (top of the Schema page).
5. Review the diff — it should add `FudAIBackup` with the two fields above to **Production**.
6. Click **Deploy to Production** and confirm. Production schema changes are irreversible; double-check field types before confirming.
7. After deploy, run the smoke test below on a **Release** device build signed with the production profile.

### CloudKit smoke test (device)

Automated upload → download/validate → delete check against the dedicated `smoke-test` record. Safe for Release builds; runs once per launch when the argument is present. Does not call production restore (no local prefs, photos, or HealthKit recovery flags are changed). If upload succeeds but a later step fails, the smoke-test record is still deleted before exit.

**Launch argument:** `-fudai.cloudBackup.smokeTest`

**Requirements:** iPhone signed into iCloud; Development schema for debug builds, Production schema deployed for Release builds.

**Run from Xcode (Release configuration recommended for pre-ship validation):**

1. Product → Scheme → Edit Scheme → **Run** → **Arguments**.
2. Under **Arguments Passed On Launch**, add `-fudai.cloudBackup.smokeTest`.
3. Select a physical iPhone (simulator iCloud is unreliable for CloudKit private DB).
4. Run. On first launch the app uploads to `smoke-test`, validates the downloaded archive hash, deletes the smoke-test record, and leaves the user’s `current` backup and local data untouched.

**Run on an installed Release build:**

```bash
# Replace DEVICE_UDID with the connected iPhone UDID from Xcode or `xcrun xctrace list devices`
xcrun devicectl device process launch --device DEVICE_UDID \
  com.apoorvdarshan.calorietracker \
  -fudai.cloudBackup.smokeTest
```

Or attach the same argument in an Xcode **Test** or **Profile** scheme when validating a Release archive.

**Expected log line** (Xcode console or Console.app, filter `CloudBackupSmoke` or `FudAICloudBackupSmokeTest`):

```
FudAICloudBackupSmokeTest: PASS
```

On failure:

```
FudAICloudBackupSmokeTest: FAIL: <reason>
```

Common failures: not signed into iCloud, Production schema not deployed (Release builds), or network/CloudKit outage.

### UI automation identifiers

Settings → Data Management → iCloud Backup controls:

| Control | Accessibility identifier |
|---------|-------------------------|
| Toggle | `settings.cloudBackup.toggle` |
| Last backup label | `settings.cloudBackup.lastBackup` |
| Back up now | `settings.cloudBackup.backupNow` |
| Restore now | `settings.cloudBackup.restoreNow` |
| Delete cloud backup | `settings.cloudBackup.delete` |

## Android

1. Create a Google Cloud project and enable the Drive API.
2. OAuth consent screen, External. Scopes: `drive.appdata` and `userinfo.email` only. Do not request full Drive.
3. In that project, register an **Android** OAuth client for
   `com.apoorvdarshan.calorietracker` with the **Play App Signing** certificate SHA-1
   from Play Console → App integrity. The upload certificate signs what you send
   to Play, not what users install. If multiple Play signing certificates are in
   use (for example after a signing-key upgrade), register each applicable SHA-1.
4. Register the Debug package `com.apoorvdarshan.calorietracker.debug` separately
   with the developer build's actual signing certificate. A working Debug sign-in
   does not validate the Play release registration.
5. Google Identity authorizes Drive access on-device. No web client ID,
   server authorization code, or OAuth credential is needed in the app build
   or release workflow. Keep the consent screen published for normal Play users.
6. Verify on a Play-installed build: open the Drive Backup account picker,
   choose a test account, accept consent and confirm that a test backup can be
   read. Use a dedicated test account; don't overwrite an existing user backup.
   `UNREGISTERED_ON_API_CONSOLE` points to the Android package/certificate
   registration, not a missing web client ID. The `FudAIDriveAuth` log records
   the status code and failure category without account or token details.

## Health

Restore writes the original food / weight / workout UUIDs and marks Health food-restore done. Apple Health / Health Connect upsert by those IDs and do not create duplicates.
