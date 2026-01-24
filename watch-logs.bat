@echo off
REM ========================================
REM Watch Android App Logs (Windows)
REM Monitors app-specific logs only
REM See: .eck/WINDOWS_BUILD_SETUP.md ADB Commands
REM ========================================

setlocal

echo ============================================
echo 👀 Watching ECKWMS MovFast Logs
echo ============================================
echo Press Ctrl+C to stop
echo.

REM Clear logcat buffer
adb logcat -c

REM Filter logs by app (as per WINDOWS_BUILD_SETUP.md)
adb logcat | findstr "eckwms ScanRecoveryVM ScanApiService HybridSender AndroidRuntime"

endlocal
