# Finding-her-eyes
Sort the files with image

## FaceFiles app

Private, offline file manager with on-device face filtering for **Android** and **Windows**.
Kotlin + Compose Multiplatform, Room (SQLite), ONNX Runtime. No cloud APIs.
No local IDE needed — every build runs on GitHub Actions.

## Build it (no Android Studio)

1. Create a new, empty GitHub repository.
2. Upload **every file and folder** of this project to the repo root — including the hidden
   `.github` folder and `facefiles-debug.keystore`.
   (If drag-and-drop skips `.github`: *Add file → Create new file*, type
   `.github/workflows/build.yml`, paste the contents.)
3. Open the **Actions** tab. A build starts automatically (or press **Run workflow**).
4. When it is green, open the run → **Artifacts**:
   - `FaceFiles-Android-APK` → unzip, install `composeApp-debug.apk`
   - `FaceFiles-Windows-Portable` → unzip, run `FaceFiles\FaceFiles.exe`
   - `FaceFiles-Windows-Installer` → `.exe` installer (only if the runner has WiX)

## Layout

- `composeApp/src/commonMain`  shared UI, Room database, domain types
- `composeApp/src/jvmShared`   code compiled into BOTH targets (file scanner, later: ONNX inference)
- `composeApp/src/androidMain` Activity, manifest, storage permission, DB path
- `composeApp/src/desktopMain` `main()`, Windows folders, DB path

## Notes

- The debug keystore is committed on purpose so each new APK installs over the previous one.
  Replace it with a private keystore + GitHub Secrets before publishing to the Play Store.
- Android needs "All files access" (granted from a system settings screen on first launch).
