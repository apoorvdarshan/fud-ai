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
trap 'rm -f "$work"/{probe,probe.cpp,ask.aiff,ask.wav,reply.wav,reply-16k.wav,agent.txt}; rmdir "$work"' EXIT

cat > "$work/probe.cpp" <<'EOF'
#include "fud_offline_voice.h"
#include <fstream>
#include <iterator>
#include <string>
#include <unistd.h>

int main(int argc, char **argv) {
    if (argc != 6 && argc != 7) return 64;
    auto turn = fud_voice_create();
    if (!turn) return 65;
    fud_voice_cancel(turn);
    if (fud_voice_reply(turn, argv[1], argv[2], argv[3], argv[4], false) != 2 ||
            access(argv[4], F_OK) == 0) return 66;
    fud_voice_destroy(turn);

    turn = fud_voice_create();
    if (!turn) return 67;
    const auto gpu = argv[5][0] == '1';
    int result;
    if (argc == 7) {
        const char *tools = R"({"name":"log_water","description":"Log water. Give an amount and unit.","parameters":{"type":"object","properties":{"amount":{"type":"number"},"unit":{"type":"string"}},"required":["amount","unit"]}})";
        result = fud_voice_agent_reply(turn, argv[1], argv[2], argv[3], argv[4], gpu, tools, nullptr, argv[6]);
        std::ifstream reply(argv[6]);
        const std::string call((std::istreambuf_iterator<char>(reply)), {});
        if (result != 0 || call.find("<function=log_water>") == std::string::npos ||
            call.find("<parameter=amount>\n200\n") == std::string::npos ||
            access(argv[4], F_OK) == 0) return 68;
        const std::string continuation = "<|im_start|>assistant\n" + call +
            "<|im_end|>\n<|im_start|>tool\n<tool_response>\n"
            R"({"ok":false,"error":"confirmation_required","message":"Nothing was saved. The user must tap Confirm on the card.","summary":"Log 200 mL of water"})"
            "\n</tool_response><|im_end|>\n";
        result = fud_voice_agent_reply(turn, argv[1], argv[2], argv[3], argv[4], gpu, "",
                                       continuation.c_str(), argv[6]);
    } else {
        result = fud_voice_reply(turn, argv[1], argv[2], argv[3], argv[4], gpu);
    }
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

if [[ "${OFFLINE_VOICE_SMOKE_AGENT:-0}" == 1 ]]; then
  say -v Samantha -o "$work/ask.aiff" 'Log two hundred milliliters of water.'
  ffmpeg -nostdin -v error -y -i "$work/ask.aiff" -ar 16000 -ac 1 "$work/ask.wav"
  "$work/probe" "$models/LFM3-Audio-2B-Q4_K_M.gguf" "$models/mmproj-LFM3-Audio-2B-Q8_0.gguf" \
    "$work/ask.wav" "$work/reply.wav" 1 "$work/agent.txt"
  test -s "$work/reply.wav"
  grep -Ei 'confirm|tap' "$work/agent.txt"
fi
