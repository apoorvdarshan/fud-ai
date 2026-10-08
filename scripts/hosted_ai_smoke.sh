#!/bin/bash
# Live end-to-end check of the deployed Hosted AI Worker: plan lookup, D1 ledger,
# text/photo logging, Coach tool calls, and voice (Android WAV + iOS m4a).
#
# Creates a throwaway anonymous RevenueCat customer, grants it Plus for one hour,
# and spends ~6 of its actions. Exits non-zero on the first failed check.
#
#   RC_V2_SECRET_KEY=sk_... scripts/hosted_ai_smoke.sh
#
# Needs: macOS (`say`/`afconvert` for the WAV clip), curl, python3.
# Optional: HOSTED_AI_BASE_URL, RC_PROJECT_ID, RC_PLUS_ENTITLEMENT_ID.
set -euo pipefail

: "${RC_V2_SECRET_KEY:?set RC_V2_SECRET_KEY to a RevenueCat v2 secret key with customer write access}"
BASE="${HOSTED_AI_BASE_URL:-https://fud-ai.app/api/hosted-ai/v1}"
PROJECT="${RC_PROJECT_ID:-proj6e5bf10f}"
PLUS_ENTITLEMENT="${RC_PLUS_ENTITLEMENT_ID:-entl97f52e1667}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PUBLIC_KEY="$(grep -o 'goog_[A-Za-z0-9]*' "$ROOT/android/app/build.gradle.kts" | head -1)"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

fail() { echo "FAIL: $*" >&2; exit 1; }
urlencode() { python3 -c 'import sys, urllib.parse; print(urllib.parse.quote(sys.argv[1], safe=""))' "$1"; }

# --- Test customer with Plus -------------------------------------------------
USER_ID="\$RCAnonymousID:$(uuidgen | tr -d - | tr 'A-F' 'a-f')"
ENCODED="$(urlencode "$USER_ID")"
# The v2 API cannot create `$RCAnonymousID:` customers; a public-key lookup does.
curl -sf -o /dev/null -H "Authorization: Bearer $PUBLIC_KEY" -H "X-Platform: android" \
  "https://api.revenuecat.com/v1/subscribers/$ENCODED" || fail "could not create RevenueCat test customer"
EXPIRES=$(( ($(date +%s) + 3600) * 1000 ))
curl -sf -o /dev/null -X POST -H "Authorization: Bearer $RC_V2_SECRET_KEY" -H "Content-Type: application/json" \
  "https://api.revenuecat.com/v2/projects/$PROJECT/customers/$ENCODED/actions/grant_entitlement" \
  -d "{\"entitlement_id\":\"$PLUS_ENTITLEMENT\",\"expires_at\":$EXPIRES}" || fail "could not grant Plus to the test customer"
echo "test customer: $USER_ID (Plus for 1h)"

# --- Fixtures ----------------------------------------------------------------
UA="FudAI-hosted-smoke/1.0 (apoorv@fud-ai.app)"
curl -sfL -A "$UA" -o "$WORK/banana.jpg" "https://commons.wikimedia.org/wiki/Special:FilePath/Banana-Single.jpg?width=800" \
  || fail "could not download the banana photo"
curl -sfL -A "$UA" -o "$WORK/pizza.jpg" "https://commons.wikimedia.org/wiki/Special:FilePath/Eq_it-na_pizza-margherita_sep2005_sml.jpg?width=800" \
  || fail "could not download the pizza photo"
say -o "$WORK/speech.aiff" "I had two scrambled eggs and a slice of toast with butter for breakfast"
afconvert -f WAVE -d LEI16@16000 -c 1 "$WORK/speech.aiff" "$WORK/speech.wav"   # what Android records

python3 - "$WORK" "$ROOT/web/tests/fixtures/speech.m4a" <<'PY'
import base64, json, os, sys
work, m4a = sys.argv[1], sys.argv[2]
b64 = lambda p: base64.b64encode(open(p, "rb").read()).decode()
bodies = {
    "text": {"prompt": "Estimate nutrition for: 2 scrambled eggs and 1 slice buttered toast. "
                       "Return JSON {\"name\":str,\"calories\":int,\"protein\":num,\"carbs\":num,\"fat\":num}",
             "images": [], "responseMimeType": "application/json"},
    "photo": {"prompt": "Identify the food in this photo. Return JSON {\"name\":str,\"calories\":int}",
              "images": [b64(f"{work}/banana.jpg")], "systemInstruction": "You are a nutrition expert.",
              "responseMimeType": "application/json"},
    "photos": {"prompt": "These photos show one meal. Return JSON {\"items\":[{\"name\":str,\"calories\":int}]}",
               "images": [b64(f"{work}/banana.jpg"), b64(f"{work}/pizza.jpg")], "responseMimeType": "application/json"},
    "coach": {"requestBody": {
        "contents": [{"role": "user", "parts": [{"text": "How much protein did I eat today?"}]}],
        "tools": [{"functionDeclarations": [{"name": "get_food_log", "description": "Get the user's food log for a date",
                   "parameters": {"type": "OBJECT", "properties": {"date": {"type": "STRING"}}, "required": ["date"]}}]}],
        "systemInstruction": {"parts": [{"text": "You are Fud AI Coach. Use tools to read the user's data."}]}}},
    "wav": {"audio": b64(f"{work}/speech.wav"), "mimeType": "audio/wav", "language": "en"},
    "m4a": {"audio": b64(m4a), "mimeType": "audio/mp4"},
}
for name, body in bodies.items():
    json.dump(body, open(os.path.join(work, f"{name}.json"), "w"))
PY

# --- Checks ------------------------------------------------------------------
# check <label> <path> <body-file|-> <python predicate on the parsed response `r`>
check() {
  local label="$1" path="$2" body="$3" predicate="$4" code
  if [ "$body" = "-" ]; then
    code=$(curl -s -o "$WORK/out.json" -w "%{http_code}" -H "X-Fud-User-Id: $USER_ID" "$BASE/$path")
  else
    code=$(curl -s -o "$WORK/out.json" -w "%{http_code}" -H "X-Fud-User-Id: $USER_ID" \
      -H "Content-Type: application/json" --data-binary @"$body" "$BASE/$path")
  fi
  [ "$code" = "200" ] || fail "$label: HTTP $code $(head -c 300 "$WORK/out.json")"
  python3 - "$WORK/out.json" "$predicate" <<'PY' || fail "$label: unexpected response $(head -c 300 "$WORK/out.json")"
import json, sys
r = json.load(open(sys.argv[1]))
text = r.get("text", "") if isinstance(r, dict) else ""
sys.exit(0 if eval(sys.argv[2]) else 1)
PY
  echo "ok   $label"
}

check "quota (plan lookup + D1 ledger)" "quota?refresh=1" - 'r["quota"]["plan"] == "plus" and r["quota"]["dailyLimit"] == 30'
check "text log"                         generate "$WORK/text.json"   'json.loads(text)["calories"] > 0'
check "photo log"                        generate "$WORK/photo.json"  '"banana" in text.lower()'
check "two photos"                       generate "$WORK/photos.json" 'len(json.loads(text)["items"]) >= 2'
check "coach tool call"                  gemini   "$WORK/coach.json"  '"functionCall" in json.dumps(r)'
check "voice (Android WAV)"              transcribe "$WORK/wav.json"  '"egg" in text.lower()'
check "voice (iOS m4a)"                  transcribe "$WORK/m4a.json"  'len(text) > 0'
check "metering"                         quota    -                   'r["quota"]["dailyUsed"] == 6'
echo "All hosted AI checks passed."
