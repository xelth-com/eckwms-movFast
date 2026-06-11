#!/bin/bash
set -e

RUST_DIR="C:/Users/Dmytro/eckwmsr"
PG_BIN="C:/Users/Dmytro/.theseus/postgresql/18.2.0/bin"
PG_DATA="C:/Users/Dmytro/eckwmsr/data/pg"
PORT=3210
BASE="http://localhost:$PORT/E"

echo "======================================"
echo "  ECKWMS SMOKE TEST (POST-MIGRATION)"
echo "======================================"

# Helper: parse JSON field via python3
json_field() {
    python3 -c "import sys, json; print(json.load(sys.stdin).get('$1', ''))"
}
json_nested() {
    python3 -c "import sys, json; d=json.load(sys.stdin); print(d.get('$1',{}).get('$2',''))"
}

# Ensure PostgreSQL on port 5433 is running
echo "[1/7] Ensuring PostgreSQL on port 5433..."
if "$PG_BIN/pg_isready.exe" -h localhost -p 5433 > /dev/null 2>&1; then
    echo "  PostgreSQL already running on 5433."
else
    echo "  Starting PostgreSQL on 5433..."
    "$PG_BIN/pg_ctl.exe" -D "$PG_DATA" -o "-p 5433" -l "$PG_DATA/logfile" start 2>&1
    sleep 2
    if ! "$PG_BIN/pg_isready.exe" -h localhost -p 5433 > /dev/null 2>&1; then
        echo "FAIL: Could not start PostgreSQL on 5433."
        exit 1
    fi
    echo "  PostgreSQL started."
fi

# Ensure eckwms database exists
echo "  Checking eckwms database..."
PGPASSWORD=eckwms "$PG_BIN/psql.exe" -h localhost -p 5433 -U eckwms -d eckwms -c "SELECT 1;" > /dev/null 2>&1 || {
    echo "  Creating eckwms database..."
    PGPASSWORD=eckwms "$PG_BIN/createdb.exe" -h localhost -p 5433 -U eckwms eckwms 2>&1 || true
}

# Kill any existing server on port 3210
echo "[2/7] Checking for existing server on port $PORT..."
existing_pid=$(netstat -ano 2>/dev/null | grep ":$PORT " | grep LISTEN | awk '{print $NF}' | head -1)
if [ -n "$existing_pid" ] && [ "$existing_pid" != "0" ]; then
    echo "  Found existing process $existing_pid on port $PORT, killing..."
    taskkill //F //PID "$existing_pid" 2>/dev/null || true
    sleep 2
fi

# Build
echo "[3/7] Building server..."
cd "$RUST_DIR"
cargo build 2>&1 | tail -3
echo "  Build complete."

# Start server
echo "[4/7] Starting server..."
cargo run > /tmp/eckwmsr_smoke.log 2>&1 &
SERVER_PID=$!
echo "  Server PID: $SERVER_PID"

# Wait for server to be ready
echo "  Waiting for server to initialize..."
for i in $(seq 1 60); do
    if curl -s "$BASE/auth/setup-status" > /dev/null 2>&1; then
        echo "  Server ready after ${i}s."
        break
    fi
    if [ $i -eq 60 ]; then
        echo "FAIL: Server did not start within 60s."
        tail -50 /tmp/eckwmsr_smoke.log
        kill $SERVER_PID 2>/dev/null || true
        exit 1
    fi
    sleep 1
done

# Check setup status
echo "[5/7] Checking setup status..."
SETUP_JSON=$(curl -s "$BASE/auth/setup-status")
echo "  Response: $SETUP_JSON"

NEEDS_SETUP=$(echo "$SETUP_JSON" | python3 -c "import sys, json; print(json.load(sys.stdin).get('needsSetup', False))")
EMAIL=$(echo "$SETUP_JSON" | json_field email)
PASSWORD=$(echo "$SETUP_JSON" | json_field password)

if [ "$NEEDS_SETUP" != "True" ]; then
    echo "  Server not in setup mode (already has users). Attempting login with known creds..."
    # Try logging in directly — maybe users already exist from prior run
    LOGIN_JSON=$(curl -s -X POST -H "Content-Type: application/json" \
        -d '{"email":"smoke@test.local","password":"test1234"}' \
        "$BASE/auth/login")
    TOKEN=$(echo "$LOGIN_JSON" | json_nested tokens accessToken)
    if [ -n "$TOKEN" ]; then
        echo "  OK: Logged in with existing smoke@test.local account."
        NEW_TOKEN="$TOKEN"
        # Skip to QR test
        echo "[6/7] Skipped (user already exists)."
    else
        echo "FAIL: Server not in setup mode and cannot login with known credentials."
        echo "  Login response: $LOGIN_JSON"
        kill $SERVER_PID 2>/dev/null || true
        exit 1
    fi
else
    echo "  OK: Setup mode active, credentials: $EMAIL / $PASSWORD"

    # Login with setup account
    echo "[6/7] Logging in with setup account and creating admin user..."
    LOGIN_JSON=$(curl -s -X POST -H "Content-Type: application/json" \
        -d "{\"email\":\"$EMAIL\", \"password\":\"$PASSWORD\"}" \
        "$BASE/auth/login")
    TOKEN=$(echo "$LOGIN_JSON" | json_nested tokens accessToken)

    if [ -z "$TOKEN" ]; then
        echo "FAIL: Could not get access token."
        echo "  Response: $LOGIN_JSON"
        kill $SERVER_PID 2>/dev/null || true
        tail -30 /tmp/eckwmsr_smoke.log
        exit 1
    fi
    echo "  OK: Got setup JWT (${#TOKEN} chars)"

    # Create admin user
    CREATE_JSON=$(curl -s -X POST \
        -H "Authorization: Bearer $TOKEN" \
        -H "Content-Type: application/json" \
        -d '{"username":"smoketest","email":"smoke@test.local","password":"test1234","role":"admin","name":"Smoke Test"}' \
        "$BASE/api/admin/users")
    echo "  Create user response: $CREATE_JSON"

    # Login with new admin
    LOGIN2_JSON=$(curl -s -X POST -H "Content-Type: application/json" \
        -d '{"email":"smoke@test.local","password":"test1234"}' \
        "$BASE/auth/login")
    NEW_TOKEN=$(echo "$LOGIN2_JSON" | json_nested tokens accessToken)

    if [ -z "$NEW_TOKEN" ]; then
        echo "FAIL: Could not get token for new admin."
        echo "  Response: $LOGIN2_JSON"
        kill $SERVER_PID 2>/dev/null || true
        tail -30 /tmp/eckwmsr_smoke.log
        exit 1
    fi
    echo "  OK: New admin JWT acquired (${#NEW_TOKEN} chars)"
fi

# Test pairing QR
echo "[7/7] Testing pairing QR endpoint..."
QR_STATUS=$(curl -s -o /dev/null -w "%{http_code}" \
    -H "Authorization: Bearer $NEW_TOKEN" \
    "$BASE/api/internal/pairing-qr")
QR_CT=$(curl -s -I -H "Authorization: Bearer $NEW_TOKEN" \
    "$BASE/api/internal/pairing-qr" 2>&1 | grep -i "content-type" || true)

if [ "$QR_STATUS" = "200" ]; then
    echo "  OK: QR endpoint returned 200 ($QR_CT)"
    RESULT=0
else
    echo "FAIL: QR endpoint returned HTTP $QR_STATUS"
    echo "  $QR_CT"
    # Show response body for debugging
    curl -s -H "Authorization: Bearer $NEW_TOKEN" "$BASE/api/internal/pairing-qr" 2>&1 | head -5
    RESULT=1
fi

# Cleanup
echo ""
echo "Cleaning up (killing server PID $SERVER_PID)..."
kill $SERVER_PID 2>/dev/null || true

if [ $RESULT -eq 0 ]; then
    echo ""
    echo "======================================"
    echo "  ALL SMOKE TESTS PASSED"
    echo "======================================"
else
    echo ""
    echo "======================================"
    echo "  SMOKE TEST FAILED"
    echo "======================================"
    echo "Server log tail:"
    tail -30 /tmp/eckwmsr_smoke.log
fi

exit $RESULT
