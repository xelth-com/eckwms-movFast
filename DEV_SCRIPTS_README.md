# Development Scripts

Quick reference for development scripts in this project.

## 🚀 All-in-One Deploy

**Windows:**
```batch
dev-deploy.bat
```

**Linux/Mac:**
```bash
./dev-deploy.sh
```

**What it does:**
1. 🧹 Clean build cache
2. 🔨 Build debug APK
3. 📲 Install on connected device
4. 🚀 Launch the app
5. 👀 Watch filtered logs

## 👀 Log Monitoring Only

If app is already installed and you just want to watch logs:

**Windows:**
```batch
watch-logs.bat
```

**Linux/Mac:**
```bash
./watch-logs.sh
```

**Monitored tags:**
- `ScanRecoveryVM` - ViewModel logic
- `ScanApiService` - API calls
- `HybridSender` - WebSocket/HTTP
- `ScannerManager` - Barcode scanning
- `XCScannerWrapper` - XC hardware
- `AUTO_PAIR` - Auto-pairing
- `AndroidRuntime` - Crashes

## 📝 Manual Commands

### Build Only
```bash
# Windows
gradlew.bat assembleDebug

# Linux/Mac
./gradlew assembleDebug
```

### Install Only
```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### Launch Only
```bash
adb shell am start -n com.xelth.eckwms_movfast/.MainActivity
```

### Clear Logs
```bash
adb logcat -c
```

### View All Logs (unfiltered)
```bash
adb logcat
```

## 🔧 Troubleshooting

### Device Not Found
```bash
adb devices
adb kill-server
adb start-server
```

### Install Failed (Signature Mismatch)
```bash
adb uninstall com.xelth.eckwms_movfast
adb install app/build/outputs/apk/debug/app-debug.apk
```

### Build Failed
```bash
# Clean everything
gradlew.bat clean
rm -rf .gradle app/build

# Rebuild
gradlew.bat assembleDebug
```

## 📂 Output Locations

- **Debug APK:** `app/build/outputs/apk/debug/app-debug.apk`
- **Release APK:** `app/build/outputs/apk/release/app-release.apk`
- **Build logs:** Check console output
- **Logcat:** Real-time via `adb logcat`
