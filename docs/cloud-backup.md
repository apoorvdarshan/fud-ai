# Optional iCloud / Google Drive backup

Off by default in Settings → Data Management. Sign-in runs only when the user turns the toggle on.

## iOS

1. Enable the iCloud CloudKit container `iCloud.com.apoorvdarshan.calorietracker` in the Apple Developer portal and Xcode.
2. Deploy the `FudAIBackup` record type to the CloudKit **Production** schema before shipping.
3. The phone’s iCloud account is enough. There is no Sign in with Apple.

## Android

1. Create a Google Cloud project and enable the Drive API.
2. OAuth consent screen, External. Scopes: `drive.appdata` and `userinfo.email` only. Do not request full Drive.
3. Create Android OAuth clients for release and debug package names / SHA-1s, plus a Web client ID.
4. Copy `android/oauth.properties.example` to `android/oauth.properties` and set `cloud.backup.web.client.id`.
5. Add test users until **brand verification** (name, logo, homepage, privacy policy). No demo video for `drive.appdata`.
6. Public Play users cannot use Drive backup until brand verification is approved.

## Health

Restore writes the original food / weight / workout UUIDs and marks Health food-restore done. Apple Health / Health Connect upsert by those IDs and do not create duplicates.
