@echo off
REM ========================================
REM Watch Android App Logs (Windows)
REM Monitors app-specific logs only
REM ========================================

setlocal

REM Set ADB path
set ADB=C:\Users\Dmytro\AppData\Local\Android\Sdk\platform-tools\adb.exe

echo ============================================
echo 👀 Watching ECKWMS MovFast Logs
echo ============================================
echo Press Ctrl+C to stop
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
