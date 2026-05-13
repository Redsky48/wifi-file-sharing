#!/usr/bin/env bash
# Build WiFi Share end-to-end and push the artifacts to the phone over
# WiFi Share itself (PUT /api/files). Same pattern as the vocal monitor
# build script — this is the WiFi Share repo eating its own dog food.
#
# What runs:
#   1. dotnet publish → windows-client/bin/Release/dist/WiFiShareTray.exe
#   2. Copy fresh .exe into app/src/main/assets/win/
#   3. ./gradlew.bat assembleDebug
#      Output filename: WiFiShare-<versionName>-debug-v<versionCode>.apk
#   4. git auto-commit if the working tree is dirty (so every green build
#      is a guaranteed rollback point).
#   5. Push APK to the phone via /api/files.
#      Old artifacts with the same prefix are deleted first.
#      (The .exe is bundled inside the APK assets — no separate upload.)
#
# Config (env vars):
#   PHONE_HOST   e.g. http://192.168.1.179:8080  (default below)
#   PHONE_PIN    Basic-auth PIN if the phone has one set
#   PHONE_DEST   destination subfolder (default: root)
#   NO_UPLOAD=1  build only, don't push (also via --no-upload flag)
set -e
cd "$(dirname "$0")"

export JAVA_HOME="${JAVA_HOME:-/c/Program Files/Microsoft/jdk-17.0.19.10-hotspot}"
DOTNET="${DOTNET:-/c/Program Files/dotnet/dotnet.exe}"

# ── Parse flags ────────────────────────────────────────────────────────
GRADLE_ARGS=()
for arg in "$@"; do
    case "$arg" in
        --no-upload) NO_UPLOAD=1 ;;
        *) GRADLE_ARGS+=("$arg") ;;
    esac
done

# ── 1. Windows .exe ────────────────────────────────────────────────────
echo "=== Building Windows .exe ==="
# Kill any running tray so we can overwrite the .exe
(tasklist //FI "IMAGENAME eq WiFiShareTray.exe" //NH 2>/dev/null | grep -qi WiFiShareTray) && \
    taskkill //IM WiFiShareTray.exe //F >/dev/null 2>&1 || true

"$DOTNET" publish windows-client/WiFiShareTray.csproj -c Release --nologo --verbosity quiet

EXE_OUT="windows-client/bin/Release/net8.0-windows/win-x64/publish/WiFiShareTray.exe"
[[ -f "$EXE_OUT" ]] || { echo "ERROR: $EXE_OUT not built"; exit 1; }
cp "$EXE_OUT" windows-client/bin/Release/dist/WiFiShareTray.exe
cp "$EXE_OUT" app/src/main/assets/win/WiFiShareTray.exe
echo "  bundled into app/src/main/assets/win/"

# ── 2. Android APK ─────────────────────────────────────────────────────
echo
echo "=== Building Android APK ==="
./gradlew.bat assembleDebug "${GRADLE_ARGS[@]}"

APK=$(ls -t app/build/outputs/apk/debug/WiFiShare-*.apk 2>/dev/null | head -1)
if [[ -z "$APK" || ! -f "$APK" ]]; then
    echo "ERROR: APK not found after build."
    exit 1
fi
APK_NAME=$(basename "$APK")
echo "  built: $APK_NAME"

# ── 3. Auto-commit on green build ──────────────────────────────────────
if [[ -n "$(git status --porcelain 2>/dev/null)" ]]; then
    git add -A
    git commit -m "build OK $(date '+%Y-%m-%d %H:%M')

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>" \
        >/dev/null 2>&1 || echo "  (commit skipped)"
fi

# ── 4. Push to phone ───────────────────────────────────────────────────
if [[ "${NO_UPLOAD:-}" == "1" ]]; then
    echo
    echo "Upload skipped (NO_UPLOAD=1)"
    exit 0
fi

PHONE_HOST="${PHONE_HOST:-http://192.168.1.179:8080}"
PHONE_DEST="${PHONE_DEST:-}"

CURL_AUTH=()
[[ -n "${PHONE_PIN:-}" ]] && CURL_AUTH=(-u "user:${PHONE_PIN}")

encode_path() { printf %s "$1" | sed 's/ /%20/g'; }

# Probe — gives a fast clear error if the phone is offline / wrong PIN.
# `curl -w '%{http_code}'` writes the code (or "000" on connect failure)
# to stdout regardless of exit status — no `|| echo` fallback needed,
# adding one would duplicate "000" into "000000".
HTTP=$(curl -sS -o /dev/null -w '%{http_code}' --max-time 5 \
    "${CURL_AUTH[@]}" "${PHONE_HOST}/api/files" 2>/dev/null) || true
HTTP="${HTTP:-000}"
case "$HTTP" in
    200) ;;
    401) echo "Phone needs a PIN — set PHONE_PIN=<pin>"; exit 1 ;;
    000) echo "Phone unreachable at $PHONE_HOST"; exit 1 ;;
    *)   echo "Phone returned HTTP $HTTP"; exit 1 ;;
esac

# Wipe previous APKs that share this build's prefix (everything before -v<N>)
APK_PREFIX=$(echo "$APK_NAME" | sed -E 's/-v[0-9]+\.apk$/-v/')
LIST_PATH_PARAM=$(encode_path "${PHONE_DEST:-}")
LIST_RESP=$(curl -sS "${CURL_AUTH[@]}" "${PHONE_HOST}/api/files?path=${LIST_PATH_PARAM}" 2>/dev/null || echo "")
OLD_FILES=$(printf %s "$LIST_RESP" \
    | grep -oE '"name":"[^"]+\.(apk|exe)"' \
    | sed 's/"name":"//;s/"$//' \
    | sort -u \
    || true)
# Note: still match .exe so we clean up any WiFiShareTray.exe pushed by
# earlier versions of this script. The .exe ships inside the APK assets
# now, so we don't upload a fresh copy — just remove stale ones.

delete_phone_file() {
    local rawname="$1"
    local full=$([ -n "$PHONE_DEST" ] && echo "${PHONE_DEST}/${rawname}" || echo "${rawname}")
    local enc=$(encode_path "$full")
    local code=$(curl -sS -X DELETE -o /dev/null -w '%{http_code}' \
        "${CURL_AUTH[@]}" "${PHONE_HOST}/api/delete?path=${enc}" 2>/dev/null || echo "000")
    case "$code" in
        200|204) echo "  removed stale: $rawname" ;;
        403)     echo "  WARN: 'Allow delete' off — can't remove $rawname"; return 1 ;;
        *)       echo "  WARN: delete $rawname → HTTP $code" ;;
    esac
}

# Returns 0 (true) if the filename is something this build owns and
# should clean up. We're careful NOT to match other projects' artifacts
# (e.g. VocalMonitor-*.apk) — only WiFi Share's own outputs:
#   - WiFiShare-<ver>-debug-v<n>.apk     (current versioned naming)
#   - app-debug.apk / app-debug (N).apk  (legacy pre-versioning name)
#   - WiFiShareTray.exe / WiFiShareTray (N).exe (current windows exe)
is_ours() {
    local name="$1"
    [[ "$name" == "$APK_PREFIX"* ]] && return 0
    [[ "$name" =~ ^app-debug(\ \([0-9]+\))?\.apk$ ]] && return 0
    [[ "$name" =~ ^WiFiShareTray(\ \([0-9]+\))?\.exe$ ]] && return 0
    return 1
}

if [[ -n "$OLD_FILES" ]]; then
    DELETE_BLOCKED=0
    while IFS= read -r old; do
        if is_ours "$old"; then
            delete_phone_file "$old" || DELETE_BLOCKED=1
            [[ "$DELETE_BLOCKED" == "1" ]] && break
        fi
    done <<< "$OLD_FILES"
fi

upload_file() {
    local local_path="$1"
    local remote_name="$2"
    local full=$([ -n "$PHONE_DEST" ] && echo "${PHONE_DEST}/${remote_name}" || echo "${remote_name}")
    local enc=$(encode_path "$full")
    echo "  uploading $remote_name → $PHONE_HOST/$full"
    local code=$(curl -sS -o /tmp/wifishare-upload.json -w '%{http_code}' \
        "${CURL_AUTH[@]}" --upload-file "$local_path" \
        "${PHONE_HOST}/api/files?path=${enc}" 2>/dev/null || echo "000")
    case "$code" in
        200|201) echo "  ok: $(cat /tmp/wifishare-upload.json 2>/dev/null)" ;;
        *)       echo "  FAILED HTTP $code: $(cat /tmp/wifishare-upload.json 2>/dev/null)"; return 1 ;;
    esac
}

upload_file "$APK" "$APK_NAME"

# Friendly system notification on the phone
curl -sS -o /dev/null "${CURL_AUTH[@]}" \
    -H "Content-Type: application/json" \
    -d "{\"title\":\"Build pushed\",\"text\":\"${APK_NAME} ready\"}" \
    "${PHONE_HOST}/api/notify" 2>/dev/null || true

echo
echo "Build pushed. Open the file browser on the phone to install the APK."
