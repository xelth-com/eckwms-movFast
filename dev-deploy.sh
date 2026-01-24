#!/bin/bash
# ========================================
# Android Dev Deployment Script (Linux/Mac)
# Builds, installs, and monitors the app
# Follows: .eck/WINDOWS_BUILD_SETUP.md conventions
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

# Using gradlew installDebug (matches Windows OPERATIONS.md)
if ! ./gradlew installDebug; then
    echo -e "${YELLOW}⚠️ Install failed. Trying to uninstall first...${NC}"
    ./gradlew uninstallDebug || true
    ./gradlew installDebug
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

# Filter logs (simple grep approach)
adb logcat | grep -E "eckwms|ScanRecoveryVM|ScanApiService|HybridSender|AndroidRuntime"
