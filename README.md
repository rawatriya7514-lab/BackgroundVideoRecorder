# Background Video Recorder (BVR) for Android (14 / 15 / 16 Compatible)

A complete, standalone Android application written in **Kotlin** designed for **reliable background and screen-off video recording** with explicit user-granted permissions and complete transparency.

---

## 🌟 Key Features

1. **Android 14, 15 & 16 (API 34/35/36) Full Compatibility**:
   - Implements Foreground Service with `FOREGROUND_SERVICE_TYPE_CAMERA` and `FOREGROUND_SERVICE_TYPE_MICROPHONE`.
   - Strictly conforms to Android 14+ FGS launch restrictions (user taps record while the app is foregrounded, service remains alive independently).
2. **Screen-Off & Background Persistence**:
   - Uses `PowerManager.PARTIAL_WAKE_LOCK` to keep the CPU and media encoding pipeline active when the power button is pressed or the screen turns off.
   - Powered by CameraX `LifecycleService` (`androidx.lifecycle.LifecycleService`) where `VideoCapture` runs without requiring an active visible `PreviewView` surface once recording begins.
3. **Transparency & Consent**:
   - Mandatory Consent/Disclaimer dialog before first recording explaining manual-only activation, background service behavior, and local storage.
   - Low-profile persistent notification with active duration counter and direct "Stop Recording" action.
4. **Scoped Storage Compliance (MediaStore)**:
   - Saves `.mp4` recordings directly to `Movies/BackgroundRecorder/` using `MediaStore.Video.Media.EXTERNAL_CONTENT_URI`.
   - Requires zero SD card or root permissions.
5. **Modern AMOLED Dark UI & Built-in Gallery**:
   - Dark theme (`#121212`) with live timer, status indicator (`IDLE`, `REC`, `SAVING`).
   - Camera lens selector (Back / Front).
   - Quality selector (1080p Full HD, 720p HD).
   - Audio recording toggle (Mic On / Muted).
   - In-app gallery to view video thumbnails, play, share via Android ShareSheet, or delete files.

---

## 📂 Project Directory Structure

```
BackgroundVideoRecorder/
├── app/
│   ├── build.gradle.kts
│   ├── proguard-rules.pro
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/antigravity/bvr/
│       │   ├── BvrApplication.kt
│       │   ├── data/
│       │   │   ├── VideoRecording.kt
│       │   │   └── VideoRepository.kt
│       │   ├── service/
│       │   │   └── BackgroundRecordingService.kt
│       │   ├── ui/
│       │   │   ├── MainActivity.kt
│       │   │   ├── GalleryActivity.kt
│       │   │   ├── VideoAdapter.kt
│       │   │   └── ConsentDialog.kt
│       │   └── util/
│       │       ├── PermissionUtils.kt
│       │       └── FormatUtils.kt
│       └── res/
│           ├── drawable/
│           ├── layout/
│           │   ├── activity_main.xml
│           │   ├── activity_gallery.xml
│           │   ├── dialog_consent.xml
│           │   └── item_video_record.xml
│           ├── values/
│           │   ├── colors.xml
│           │   ├── strings.xml
│           │   └── themes.xml
│           └── values-night/
│               └── themes.xml
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
└── README.md
```

---

## 🚀 How to Build & Run

### In Android Studio:
1. Open Android Studio -> **File > Open...** -> select `BackgroundVideoRecorder`.
2. Let Gradle sync dependencies.
3. Connect an Android device running Android 10, 11, 12, 13, 14, 15, or 16.
4. Tap **Run ('app')**.
