#!/usr/bin/env bash
# Local Mac smoke test. Uses a synthetic fixture, no real recordings or network.
set -euo pipefail

if [[ $# != 4 ]]; then
  echo "Usage: bash $0 <llama.cpp b11960 source> <macOS arm64 b11960 binaries> <verified GGUF directory> <scratch directory>" >&2
  exit 64
fi
src="$(cd "$1" && pwd)"
lib="$(cd "$2" && pwd)"
models="$(cd "$3" && pwd)"
scratch="$(cd "$4" && pwd)"
bridge="$(cd "$(dirname "$0")/../shared/offline-voice" && pwd)"
work="$(mktemp -d "$scratch/fud-voice-smoke.XXXXXXXX")"
trap 'rm -f "$work"/{probe,probe.cpp,ask.aiff,ask.wav,reply.wav,reply-16k.wav}; rmdir "$work"' EXIT

cat > "$work/probe.cpp" <<'EOF'
#include "fud_offline_voice.h"
#include <unistd.h>

int main(int argc, char **argv) {
    if (argc != 6) return 64;
    auto turn = fud_voice_create();
    if (!turn) return 65;
    fud_voice_cancel(turn);
    if (fud_voice_reply(turn, argv[1], argv[2], argv[3], argv[4], false) != 2 ||
            access(argv[4], F_OK) == 0) return 66;
    fud_voice_destroy(turn);

    turn = fud_voice_create();
    if (!turn) return 67;
    const int result = fud_voice_reply(turn, argv[1], argv[2], argv[3], argv[4], argv[5][0] == '1');
    fud_voice_destroy(turn);
    return result;
}
EOF
clang++ -std=c++17 -O2 -I"$bridge" -I"$src/include" -I"$src/ggml/include" -I"$src/tools/mtmd" \
  "$work/probe.cpp" "$bridge/OfflineVoice.cpp" -L"$lib" -Wl,-rpath,"$lib" -lmtmd -lllama -o "$work/probe"
say -v Samantha -o "$work/ask.aiff" 'What is two plus two? Answer in one short sentence.'
ffmpeg -nostdin -v error -i "$work/ask.aiff" -ar 16000 -ac 1 "$work/ask.wav"
for gpu in ${OFFLINE_VOICE_SMOKE_BACKENDS:-1 0}; do
  /usr/bin/time -p "$work/probe" "$models/LFM3-Audio-2B-Q4_K_M.gguf" "$models/mmproj-LFM3-Audio-2B-Q8_0.gguf" \
    "$work/ask.wav" "$work/reply.wav" "$gpu"
  python3 - "$work/reply.wav" <<'PY'
import array
import sys
import wave

with wave.open(sys.argv[1], "rb") as reply:
    assert (reply.getnchannels(), reply.getsampwidth(), reply.getframerate()) == (1, 2, 24000)
    pcm = array.array("h", reply.readframes(reply.getnframes()))
    assert len(pcm) > 12000 and max(map(abs, pcm)) > 500
    print(f"Verified {len(pcm) / 24000:.2f} s of non-silent 24 kHz speech output")
PY
  if [[ -n "${OFFLINE_VOICE_WHISPER_MODEL:-}" ]]; then
    ffmpeg -nostdin -v error -y -i "$work/reply.wav" -ar 16000 -ac 1 "$work/reply-16k.wav"
    transcript="$(whisper-cli -m "$OFFLINE_VOICE_WHISPER_MODEL" -f "$work/reply-16k.wav" -l en -nt -np)"
    printf 'Synthetic reply: %s\n' "$transcript"
    grep -Eiq '\bfour\b|(^|[^0-9])4([^0-9]|$)' <<<"$transcript"
  fi
  rm -f "$work/reply.wav"
done
