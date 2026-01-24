#!/bin/bash
# ========================================
# Android Dev Deployment Script (Linux/Mac)
# Builds, installs, and monitors the app
# ========================================

set -e  # Exit on error

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

echo -e "${BLUE}============================================${NC}"
echo -e "${BLUE}🧹 Step 1/4: Cleaning and Building APK...${NC}"
echo -e "${BLUE}============================================${NC}"
./gradlew clean assembleDebug

echo -e "\n${BLUE}============================================${NC}"
echo -e "${BLUE}📲 Step 2/4: Installing on device...${NC}"
echo -e "${BLUE}============================================${NC}"

# Try install with -r flag (reinstall preserving data)
if ! adb install -r app/build/outputs/apk/debug/app-debug.apk; then
    echo -e "${YELLOW}⚠️ Install failed. Trying to uninstall first...${NC}"
    adb uninstall com.xelth.eckwms_movfast
    adb install app/build/outputs/apk/debug/app-debug.apk
fi

echo -e "\n${BLUE}============================================${NC}"
echo -e "${BLUE}🚀 Step 3/4: Launching app...${NC}"
echo -e "${BLUE}============================================${NC}"
adb shell am start -n com.xelth.eckwms_movfast/.MainActivity

echo -e "\n${BLUE}============================================${NC}"
echo -e "${BLUE}👀 Step 4/4: Monitoring logs...${NC}"
echo -e "${BLUE}============================================${NC}"
echo -e "${YELLOW}Press Ctrl+C to stop watching logs${NC}\n"

# Clear logcat buffer
adb logcat -c

# Watch filtered logs with color
adb logcat -v color -s \
    "ScanRecoveryVM" \
    "ScanApiService" \
    "HybridSender" \
    "ScannerManager" \
    "XCScannerWrapper" \
    "AUTO_PAIR" \
    "System.out" \
    "AndroidRuntime"
