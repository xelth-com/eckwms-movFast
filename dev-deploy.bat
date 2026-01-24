@echo off
REM ========================================
REM Android Dev Deployment Script (Windows)
REM Builds, installs, and monitors the app
REM See: .eck/WINDOWS_BUILD_SETUP.md for details
REM ========================================

setlocal

REM Set Java path for Gradle (as per WINDOWS_BUILD_SETUP.md)
set JAVA_HOME=C:\Program Files\Android\Android Studio\jbr
set PATH=%JAVA_HOME%\bin;%PATH%

echo.
echo ============================================
echo 🧹 Step 1/4: Cleaning and Building APK...
echo ============================================
call gradlew.bat clean assembleDebug
if %errorlevel% neq 0 (
    echo ❌ Build failed!
    pause
    exit /b %errorlevel%
)

echo.
echo ============================================
echo 📲 Step 2/4: Installing on device...
echo ============================================
REM Using gradlew installDebug (as per WINDOWS_BUILD_SETUP.md Device Operations)
call gradlew.bat installDebug
if %errorlevel% neq 0 (
    echo ⚠️ Install failed. Trying to uninstall first...
    call gradlew.bat uninstallDebug
    call gradlew.bat installDebug
    if %errorlevel% neq 0 (
        echo ❌ Installation failed!
        pause
        exit /b %errorlevel%
    )
)

echo.
echo ============================================
echo 🚀 Step 3/4: Launching app...
echo ============================================
REM Using adb from PATH (as per WINDOWS_BUILD_SETUP.md ADB Commands)
adb shell am start -n com.xelth.eckwms_movfast/.MainActivity

echo.
echo ============================================
echo 👀 Step 4/4: Monitoring logs...
echo ============================================
echo Press Ctrl+C to stop watching logs
echo.

REM Clear logcat buffer
adb logcat -c

REM Filter logs by app (as per WINDOWS_BUILD_SETUP.md - simple filter)
adb logcat | findstr "eckwms ScanRecoveryVM ScanApiService HybridSender AndroidRuntime"

endlocal
