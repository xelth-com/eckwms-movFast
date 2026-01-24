@echo off
REM ========================================
REM Android Dev Deployment Script (Windows)
REM Builds, installs, and monitors the app
REM ========================================

setlocal

REM Set Java path for Gradle
set JAVA_HOME=C:\Program Files\Android\Android Studio\jbr
set PATH=%JAVA_HOME%\bin;%PATH%

REM Set ADB path
set ADB=C:\Users\Dmytro\AppData\Local\Android\Sdk\platform-tools\adb.exe

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
"%ADB%" install -r app\build\outputs\apk\debug\app-debug.apk
if %errorlevel% neq 0 (
    echo ⚠️ Install failed. Trying to uninstall first...
    "%ADB%" uninstall com.xelth.eckwms_movfast
    "%ADB%" install app\build\outputs\apk\debug\app-debug.apk
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
"%ADB%" shell am start -n com.xelth.eckwms_movfast/.MainActivity

echo.
echo ============================================
echo 👀 Step 4/4: Monitoring logs...
echo ============================================
echo Press Ctrl+C to stop watching logs
echo.

REM Clear logcat buffer
"%ADB%" logcat -c

REM Watch filtered logs
"%ADB%" logcat -s ^
    ScanRecoveryVM:* ^
    ScanApiService:* ^
    HybridSender:* ^
    ScannerManager:* ^
    XCScannerWrapper:* ^
    AUTO_PAIR:* ^
    System.out:I ^
    AndroidRuntime:E

endlocal
