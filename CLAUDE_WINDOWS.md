# Windows Android Development Environment Guide
**Project:** eckwms-movFast
**Target:** Claude Code on Windows

---

## Quick Start

Если Android Studio уже установлена, можно сразу собрать APK:

```batch
:: Из корня проекта
gradlew.bat assembleDebug
```

APK будет в: `app\build\outputs\apk\debug\app-debug.apk`

---

## Installation Guide

### Step 1: Install Android Studio

1. Скачать Android Studio с https://developer.android.com/studio
2. Установить с настройками по умолчанию
3. При первом запуске SDK Manager скачает необходимые компоненты

**Стандартные пути установки:**
```
Android Studio: C:\Program Files\Android\Android Studio
Android SDK:    C:\Users\<USERNAME>\AppData\Local\Android\Sdk
JBR (JDK):      C:\Program Files\Android\Android Studio\jbr
```

### Step 2: Verify SDK Components

Открыть Android Studio > Tools > SDK Manager и убедиться что установлены:

| Компонент | Версия | Обязательно |
|-----------|--------|-------------|
| Android SDK Platform 35 | API 35 | Да (compileSdk) |
| Android SDK Platform 33 | API 33 | Да (minSdk) |
| Android SDK Build-Tools | 35.x | Да |
| Android SDK Platform-Tools | Latest | Да (для adb) |
| Android SDK Command-line Tools | Latest | Опционально |

### Step 3: Configure Environment Variables

**Вариант A: Системные переменные (рекомендуется)**

1. Win+R > `sysdm.cpl` > Advanced > Environment Variables
2. Добавить в System variables:

```
JAVA_HOME = C:\Program Files\Android\Android Studio\jbr
ANDROID_HOME = C:\Users\<USERNAME>\AppData\Local\Android\Sdk
```

3. Добавить в PATH:
```
%JAVA_HOME%\bin
%ANDROID_HOME%\platform-tools
%ANDROID_HOME%\cmdline-tools\latest\bin
```

**Вариант B: Только для сессии (PowerShell)**

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
$env:PATH = "$env:JAVA_HOME\bin;$env:ANDROID_HOME\platform-tools;$env:PATH"
```

**Вариант B: Только для сессии (CMD)**

```batch
set JAVA_HOME=C:\Program Files\Android\Android Studio\jbr
set ANDROID_HOME=%LOCALAPPDATA%\Android\Sdk
set PATH=%JAVA_HOME%\bin;%ANDROID_HOME%\platform-tools;%PATH%
```

### Step 4: Verify local.properties

Файл `local.properties` должен содержать правильный путь к SDK:

```properties
sdk.dir=C\:\\Users\\<USERNAME>\\AppData\\Local\\Android\\Sdk
```

Или без экранирования (тоже работает):
```properties
sdk.dir=C:/Users/<USERNAME>/AppData/Local/Android/Sdk
```

---

## Build Commands Reference

### Basic Build Operations

```batch
:: Clean build artifacts
gradlew.bat clean

:: Build debug APK
gradlew.bat assembleDebug

:: Build release APK (signed)
gradlew.bat assembleRelease

:: Clean + Build
gradlew.bat clean assembleDebug

:: List all tasks
gradlew.bat tasks
```

### Device Operations

```batch
:: Install debug APK on connected device
gradlew.bat installDebug

:: Install release APK
gradlew.bat installRelease

:: Uninstall from device
gradlew.bat uninstallDebug
```

### ADB Commands

```batch
:: List connected devices
adb devices

:: Install APK manually
adb install app\build\outputs\apk\debug\app-debug.apk

:: Install with replacement
adb install -r app\build\outputs\apk\debug\app-debug.apk

:: View logs
adb logcat

:: Filter logs by app
adb logcat | findstr "eckwms"

:: Clear logs
adb logcat -c

:: Open device shell
adb shell

:: Start app
adb shell am start -n com.xelth.eckwms_movfast/.MainActivity

:: Restart ADB (if device not detected)
adb kill-server && adb start-server
```

### Testing

```batch
:: Run unit tests
gradlew.bat test

:: Run instrumented tests (requires device)
gradlew.bat connectedAndroidTest

:: Run specific test class
gradlew.bat test --tests "com.xelth.eckwms_movfast.ExampleUnitTest"
```

### Debug Options

```batch
:: Build with stacktrace
gradlew.bat assembleDebug --stacktrace

:: Build with info logging
gradlew.bat assembleDebug --info

:: Build with debug logging
gradlew.bat assembleDebug --debug

:: Check dependencies
gradlew.bat dependencies

:: Refresh dependencies
gradlew.bat build --refresh-dependencies
```

---

## Project Configuration Summary

### SDK Versions
```
compileSdk = 35      (Android 15)
targetSdk = 35       (Android 15)
minSdk = 33          (Android 13)
```

### Java/Kotlin
```
JDK Target: 11
Kotlin: 2.0.21
```

### Build System
```
Gradle: 8.13
Android Gradle Plugin: 8.13.0
```

### App Package
```
Namespace: com.xelth.eckwms_movfast
Application Class: com.xelth.eckwms_movfast.EckwmsApp
Main Activity: com.xelth.eckwms_movfast.MainActivity
```

---

## Signing Configuration

Release builds подписываются автоматически. Ключи хранятся в `gradle.properties`:

```properties
MYAPP_RELEASE_STORE_FILE=../keystore.jks
MYAPP_RELEASE_STORE_PASSWORD=<password>
MYAPP_RELEASE_KEY_ALIAS=eckwms-key
MYAPP_RELEASE_KEY_PASSWORD=<password>
```

**Keystore file:** `keystore.jks` в корне проекта

---

## Output Locations

| Build Type | APK Location |
|------------|--------------|
| Debug | `app\build\outputs\apk\debug\app-debug.apk` |
| Release | `app\build\outputs\apk\release\app-release.apk` |

---

## Quick Build Script

Используй готовый скрипт `build_apk.bat`:

```batch
@echo off
echo Building APK through Windows...
set JAVA_HOME=C:\Program Files\Android\Android Studio\jbr
set PATH=%JAVA_HOME%\bin;%PATH%
gradlew.bat :app:assembleDebug
echo Build complete!
pause
```

---

## Troubleshooting

### "JAVA_HOME is not set"
```batch
set JAVA_HOME=C:\Program Files\Android\Android Studio\jbr
```

### "SDK location not found"
Проверь `local.properties` содержит правильный путь к SDK.

### "Device not found"
```batch
adb kill-server
adb start-server
adb devices
```

### "Build failed - dependency issues"
```batch
gradlew.bat clean
gradlew.bat build --refresh-dependencies
```

### "Permission denied" для gradlew.bat
В PowerShell:
```powershell
Set-ExecutionPolicy -ExecutionPolicy RemoteSigned -Scope CurrentUser
```

### Gradle cache issues
```batch
:: Удалить кэш Gradle
rmdir /s /q %USERPROFILE%\.gradle\caches
gradlew.bat clean build
```

---

## Verification Checklist

После настройки выполни проверку:

```batch
:: Проверить Java
java --version
:: Expected: openjdk 21.x.x

:: Проверить переменные
echo %JAVA_HOME%
echo %ANDROID_HOME%

:: Проверить adb
adb --version
:: Expected: Android Debug Bridge version 1.0.x

:: Проверить сборку
gradlew.bat assembleDebug
:: Expected: BUILD SUCCESSFUL
```

---

## Special Libraries

Проект использует кастомную AAR библиотеку для сканера:
- File: `app\libs\xcscanner_qrcode_v1.3.56.1.7-release.aar`
- Purpose: QR/Barcode scanning на устройствах XC

---

## Key Dependencies

| Library | Purpose |
|---------|---------|
| Jetpack Compose | UI framework |
| Room | Local database |
| CameraX | Camera operations |
| ML Kit Barcode | Barcode scanning |
| WorkManager | Background sync |
| Lazysodium | Ed25519 cryptography |
| Java-WebSocket | Real-time communication |

---

## Notes for Claude Code on Windows

1. **Working Directory:** Всегда выполняй команды из корня проекта
2. **Path Separators:** Windows использует `\`, но Gradle понимает и `/`
3. **local.properties:** Не коммитится в git, может потребоваться создать вручную
4. **USB Debugging:** Включить на телефоне Developer Options > USB Debugging
5. **Драйверы:** Для некоторых устройств нужны USB драйверы производителя

---

**Last Updated:** 2026-01-15
**Tested on:** Windows 10/11 with Android Studio
