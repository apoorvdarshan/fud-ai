import { readFileSync } from "node:fs";
import { runInNewContext } from "node:vm";
import { expect, it, vi } from "vitest";

const source = readFileSync(new URL("../flash-demo.js", import.meta.url), "utf8");

function page({ reduceMotion = false, supported = true } = {}) {
  class Element extends EventTarget {
    hidden = true;
    dataset = {};
    textContent = "";
  }
  const ids = ["#play-demo", "#hear-demo", "#demo-audio", ".voice-demo", "#demo-status",
    "#demo-caption", "#demo-result", "#demo-note", ".voice-mascot", ".mascot-speaking"];
  const elements = Object.fromEntries(ids.map((id) => [id, new Element()]));
  const [silent, sound, audio, demo, status, caption, result, note, mascot, mouth] = ids.map((id) => elements[id]);
  mouth.setAttribute = vi.fn();
  let nextFrame;
  Object.assign(audio, {
    canPlayType: () => supported ? "probably" : "",
    play: vi.fn().mockResolvedValue(undefined),
    pause: vi.fn(),
    currentTime: 0,
  });
  caption.textContent = "A fictional meal.";
  note.textContent = "No microphone or AI requests.";
  runInNewContext(source, {
    document: { querySelector: (id) => elements[id] },
    matchMedia: () => ({ matches: reduceMotion }),
    setTimeout,
    clearTimeout,
    requestAnimationFrame(callback) { nextFrame = callback; return 1; },
    cancelAnimationFrame() { nextFrame = undefined; },
  });
  const press = (el) => el.dispatchEvent(new Event("click"));
  return { silent, sound, audio, demo, status, caption, result, note, mascot, mouth, press, frame: () => nextFrame?.() };
}

it("plays only on request and follows the recording through the draft and replay", () => {
  const { sound, audio, status, caption, result, press } = page();
  expect(audio.play).not.toHaveBeenCalled();
  press(sound);
  expect(audio.play).toHaveBeenCalledTimes(1);
  expect(status.textContent).toBe("Listening…");
  audio.currentTime = 3.1;
  audio.dispatchEvent(new Event("timeupdate"));
  expect(status.textContent).toBe("Preparing draft…");
  audio.currentTime = 3.8;
  audio.dispatchEvent(new Event("timeupdate"));
  expect(result.hidden).toBe(false);
  expect(caption.textContent).toContain("375 calories");
  audio.dispatchEvent(new Event("ended"));
  expect(sound.textContent).toBe("Play with sound");
  press(sound);
  press(sound);
  expect(audio.currentTime).toBe(0);
  expect(audio.play).toHaveBeenCalledTimes(2);
  expect(result.hidden).toBe(false);
});

it("preserves a silent option without audio support and respects reduced motion", () => {
  const { silent, sound, audio, result, press } = page({ reduceMotion: true, supported: false });
  expect(sound.hidden).toBe(true);
  expect(silent.hidden).toBe(false);
  press(silent);
  expect(audio.play).not.toHaveBeenCalled();
  expect(result.hidden).toBe(false);
});

it("still shows the sample result if playback is blocked", async () => {
  const { sound, audio, result, note, press } = page();
  audio.play.mockRejectedValueOnce(new Error("Playback blocked"));
  press(sound);
  await Promise.resolve();
  expect(result.hidden).toBe(false);
  expect(note.textContent).toContain("Audio couldn’t play");
});

it("opens the mouth on speech, closes it on pauses and stops when playback stops", () => {
  const { sound, audio, mascot, mouth, press, frame } = page();
  press(sound);
  audio.currentTime = 1.5;
  frame();
  expect(mascot.dataset.mouth).toBeUndefined();
  audio.currentTime = 3.75;
  frame();
  expect(mascot.dataset.mouth).toBe("open");
  expect(mouth.setAttribute).toHaveBeenCalledWith("d", expect.stringMatching(/^M.*Z$/));
  audio.currentTime = 4.65;
  frame();
  expect(mascot.dataset.mouth).toBeUndefined();
  press(sound);
  audio.currentTime = 3.75;
  frame();
  expect(mascot.dataset.mouth).toBeUndefined();
});

it("plays audio without lip motion when reduced motion is enabled", () => {
  const { sound, audio, mouth, press, frame } = page({ reduceMotion: true });
  press(sound);
  audio.currentTime = 3.75;
  frame();
  expect(audio.play).toHaveBeenCalled();
  expect(mouth.setAttribute).not.toHaveBeenCalled();
});
