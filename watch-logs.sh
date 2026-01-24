#!/bin/bash
# ========================================
# Watch Android App Logs (Linux/Mac)
# Monitors app-specific logs only
# ========================================

# Colors for output
BLUE='\033[0;34m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

echo -e "${BLUE}============================================${NC}"
echo -e "${BLUE}👀 Watching ECKWMS MovFast Logs${NC}"
echo -e "${BLUE}============================================${NC}"
echo -e "${YELLOW}Press Ctrl+C to stop${NC}\n"

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
