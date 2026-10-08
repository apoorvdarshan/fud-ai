/**
 * Duration probing for hosted `/transcribe` uploads.
 *
 * Deepgram bills per audio minute, while the hosted ledger charges one action
 * per request. A byte cap alone does not bound cost: 12 MiB of low-bitrate
 * compressed audio is well over an hour of speech. The Worker therefore reads
 * the real duration from the container header before charging, and only
 * accepts the containers the apps actually record:
 *
 *   - Android: 16 kHz mono PCM WAV (RIFF `fmt ` + `data` chunks)
 *   - iOS: AAC in an MPEG-4 container (`.m4a`, duration in `moov/mvhd`)
 *
 * Anything that cannot be measured is rejected rather than guessed.
 */

export type AudioContainer = "wav" | "mp4";

const WAV_MIME_TYPES = new Set(["audio/wav", "audio/x-wav", "audio/wave"]);
const MP4_MIME_TYPES = new Set(["audio/mp4", "audio/m4a", "audio/x-m4a"]);

/** Maps an upload mime type to a container whose duration can be measured. */
export function audioContainerForMimeType(mimeType: string): AudioContainer | null {
  if (WAV_MIME_TYPES.has(mimeType)) return "wav";
  if (MP4_MIME_TYPES.has(mimeType)) return "mp4";
  return null;
}

/** Returns the audio duration in seconds, or `null` when the header cannot be read. */
export function measureAudioDurationSeconds(bytes: Uint8Array, container: AudioContainer): number | null {
  const seconds = container === "wav" ? wavDurationSeconds(bytes) : mp4DurationSeconds(bytes);
  return seconds !== null && Number.isFinite(seconds) && seconds >= 0 ? seconds : null;
}

function ascii(bytes: Uint8Array, offset: number, length: number): string {
  if (offset < 0 || offset + length > bytes.byteLength) return "";
  return String.fromCharCode(...bytes.subarray(offset, offset + length));
}

function wavDurationSeconds(bytes: Uint8Array): number | null {
  if (bytes.byteLength < 12 || ascii(bytes, 0, 4) !== "RIFF" || ascii(bytes, 8, 4) !== "WAVE") return null;
  const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);

  let byteRate: number | null = null;
  let offset = 12;
  while (offset + 8 <= bytes.byteLength) {
    const id = ascii(bytes, offset, 4);
    const size = view.getUint32(offset + 4, true);
    const dataStart = offset + 8;
    if (id === "fmt ") {
      if (size < 16 || dataStart + 16 > bytes.byteLength) return null;
      byteRate = view.getUint32(dataStart + 8, true);
    } else if (id === "data") {
      if (!byteRate) return null;
      // Streaming writers can leave the size unset (0 or 0xFFFFFFFF); fall back
      // to the bytes actually present, which is an upper bound either way.
      const available = bytes.byteLength - dataStart;
      const dataSize = size === 0 || size > available ? available : size;
      return dataSize / byteRate;
    }
    offset = dataStart + size + (size % 2);
  }
  return null;
}

interface Mp4Box {
  type: string;
  start: number;
  end: number;
}

/** Iterates the boxes between `start` and `end`, handling 64-bit and to-end sizes. */
function* mp4Boxes(view: DataView, bytes: Uint8Array, start: number, end: number): Generator<Mp4Box> {
  let offset = start;
  while (offset + 8 <= end) {
    let size = view.getUint32(offset);
    const type = ascii(bytes, offset + 4, 4);
    let header = 8;
    if (size === 1) {
      if (offset + 16 > end) return;
      size = view.getUint32(offset + 8) * 2 ** 32 + view.getUint32(offset + 12);
      header = 16;
    } else if (size === 0) {
      size = end - offset;
    }
    if (size < header || offset + size > end) return;
    yield { type, start: offset + header, end: offset + size };
    offset += size;
  }
}

function mp4DurationSeconds(bytes: Uint8Array): number | null {
  const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
  for (const box of mp4Boxes(view, bytes, 0, bytes.byteLength)) {
    if (box.type !== "moov") continue;
    for (const child of mp4Boxes(view, bytes, box.start, box.end)) {
      if (child.type !== "mvhd") continue;
      const version = view.getUint8(child.start);
      let timescale: number;
      let duration: number;
      if (version === 0) {
        if (child.start + 20 > child.end) return null;
        timescale = view.getUint32(child.start + 12);
        duration = view.getUint32(child.start + 16);
      } else if (version === 1) {
        if (child.start + 32 > child.end) return null;
        timescale = view.getUint32(child.start + 20);
        duration = view.getUint32(child.start + 24) * 2 ** 32 + view.getUint32(child.start + 28);
      } else {
        return null;
      }
      return timescale > 0 ? duration / timescale : null;
    }
    return null;
  }
  return null;
}
