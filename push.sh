#!/usr/bin/env bash
# Push the latest already-built APK to the phone without re-running gradle.
# Same upload flow as build.sh — same auth probe, same stale-cleanup, just
# skips the dotnet publish + gradle assemble steps.
#
# Usage:
#   PHONE_PIN=484848 ./push.sh                       # picks newest APK
#   PHONE_PIN=484848 ./push.sh path/to/specific.apk  # explicit file
#
# Config (env vars):
#   PHONE_HOST   e.g. http://192.168.1.179:8080  (default below)
#   PHONE_PIN    Basic-auth PIN if the phone has one set
#   PHONE_DEST   destination subfolder (default: root)
set -e
cd "$(dirname "$0")"

# ── Locate APK ─────────────────────────────────────────────────────
if [[ -n "$1" ]]; then
    APK="$1"
else
    APK=$(ls -t app/build/outputs/apk/debug/WiFiShare-*.apk 2>/dev/null | head -1)
fi
if [[ -z "$APK" || ! -f "$APK" ]]; then
    echo "ERROR: no APK found. Run ./build.sh first, or pass a path."
    exit 1
fi
APK_NAME=$(basename "$APK")
APK_SIZE=$(stat -c%s "$APK")
echo "  using: $APK_NAME ($((APK_SIZE / 1024 / 1024)) MB)"

# ── Push to phone (same logic as build.sh) ─────────────────────────
PHONE_HOST="${PHONE_HOST:-http://192.168.1.179:8080}"
PHONE_DEST="${PHONE_DEST:-}"

CURL_AUTH=()
[[ -n "${PHONE_PIN:-}" ]] && CURL_AUTH=(-u "user:${PHONE_PIN}")

encode_path() { printf %s "$1" | sed 's/ /%20/g'; }

# Probe — fast clear error if phone is offline / wrong PIN.
HTTP=$(curl -sS -o /dev/null -w '%{http_code}' --max-time 5 \
    "${CURL_AUTH[@]}" "${PHONE_HOST}/api/files" 2>/dev/null) || true
HTTP="${HTTP:-000}"
case "$HTTP" in
    200) ;;
    401) echo "Phone needs a PIN — set PHONE_PIN=<pin>"; exit 1 ;;
    000) echo "Phone unreachable at $PHONE_HOST"; exit 1 ;;
    *)   echo "Phone returned HTTP $HTTP"; exit 1 ;;
esac

# Wipe previously pushed WiFi Share artifacts so the file browser stays
# tidy. Same matcher as build.sh — only files we own get touched.
LIST_PATH_PARAM=$(encode_path "${PHONE_DEST:-}")
LIST_RESP=$(curl -sS "${CURL_AUTH[@]}" "${PHONE_HOST}/api/files?path=${LIST_PATH_PARAM}" 2>/dev/null || echo "")
OLD_FILES=$(printf %s "$LIST_RESP" \
    | grep -oE '"name":"[^"]+\.(apk|exe)"' \
    | sed 's/"name":"//;s/"$//' \
    | sort -u \
    || true)

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

is_ours() {
    local name="$1"
    [[ "$name" =~ ^WiFiShare-.*\.apk$ ]] && return 0
    [[ "$name" =~ ^app-debug(\ \([0-9]+\))?\.apk$ ]] && return 0
    [[ "$name" =~ ^WiFiShareTray(\ \([0-9]+\))?\.exe$ ]] && return 0
    return 1
}

if [[ -n "$OLD_FILES" ]]; then
    while IFS= read -r old; do
        if is_ours "$old"; then
            delete_phone_file "$old" || break
        fi
    done <<< "$OLD_FILES"
fi

# Upload
full=$([ -n "$PHONE_DEST" ] && echo "${PHONE_DEST}/${APK_NAME}" || echo "${APK_NAME}")
enc=$(encode_path "$full")
echo "  uploading $APK_NAME → $PHONE_HOST/$full"
code=$(curl -sS -o /tmp/wifishare-upload.json -w '%{http_code}' \
    "${CURL_AUTH[@]}" --upload-file "$APK" \
    "${PHONE_HOST}/api/files?path=${enc}" 2>/dev/null || echo "000")
case "$code" in
    200|201) echo "  ok: $(cat /tmp/wifishare-upload.json 2>/dev/null)" ;;
    *)       echo "  FAILED HTTP $code: $(cat /tmp/wifishare-upload.json 2>/dev/null)"; exit 1 ;;
esac

# Friendly system notification
curl -sS -o /dev/null "${CURL_AUTH[@]}" \
    -H "Content-Type: application/json" \
    -d "{\"title\":\"Build pushed\",\"text\":\"${APK_NAME} ready\"}" \
    "${PHONE_HOST}/api/notify" 2>/dev/null || true

echo
echo "Push done. Open the file browser on the phone to install."
