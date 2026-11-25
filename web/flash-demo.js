"use strict";

const silent = document.querySelector("#play-demo");
const sound = document.querySelector("#hear-demo");
const audio = document.querySelector("#demo-audio");
const demo = document.querySelector(".voice-demo");
const demoStatus = document.querySelector("#demo-status");
const caption = document.querySelector("#demo-caption");
const result = document.querySelector("#demo-result");
const note = document.querySelector("#demo-note");
const mascot = document.querySelector(".voice-mascot");
const mouth = document.querySelector(".mascot-speaking");

const replyStart = 3.55;
// 20 RMS samples/sec from the bundled reply, encoded as base-36 levels.
// Index by media time to stay in sync through buffering and make pauses close the mouth.
const replyLevels = "006rsfbla4mle930050000000mmavtub6ni40loa8hgikjlljh36d8776000jeqn50007ihf3gooamcgla0hedfc09gea002edb54213220000000007zkasson0a70egbcgg80400000";

if (silent && sound && audio && demo && demoStatus && caption && result && note) {
  const example = caption.textContent;
  const response = "Here’s your draft: a yogurt and granola bowl, about 375 calories. Review it before logging.";
  const defaultNote = note.textContent;
  const motion = matchMedia("(prefers-reduced-motion: reduce)");
  let timer;
  let frame;
  let mode;
  let attempt = 0;

  function show(step, label, text, ready = false) {
    if (demo.dataset.step === step) return;
    demo.dataset.step = step;
    demoStatus.textContent = label;
    caption.textContent = text;
    result.hidden = !ready;
  }

  function stop() {
    attempt += 1;
    mode = undefined;
    clearTimeout(timer);
    cancelAnimationFrame(frame);
    if (mascot) delete mascot.dataset.mouth;
    audio.pause();
    audio.currentTime = 0;
    sound.textContent = "Play with sound";
    silent.textContent = "Run silently";
  }

  function showDraft() {
    show("complete", "Draft ready", response, true);
  }

  function animateFace() {
    if (!mascot || !mouth || mode !== "audio" || motion.matches) {
      if (mascot) delete mascot.dataset.mouth;
      return;
    }
    const position = (audio.currentTime - replyStart) * 20;
    const i = Math.floor(position);
    const a = parseInt(replyLevels[i] ?? "0", 36);
    const b = parseInt(replyLevels[i + 1] ?? "0", 36);
    const level = i >= 0 ? (a + (b - a) * (position - i)) / 35 : 0;
    if (level > .08) {
      const w = 11 + 8 * level;
      const h = 4 + 16 * level;
      mouth.setAttribute("d", `M${172 - w} 283 Q172 ${283 - .68 * h} ${172 + w} 283 Q172 ${283 + 1.55 * h} ${172 - w} 283Z`);
      mascot.dataset.mouth = "open";
    } else {
      delete mascot.dataset.mouth;
    }
    frame = requestAnimationFrame(animateFace);
  }

  function audioUnavailable() {
    stop();
    showDraft();
    note.textContent = "Audio couldn’t play. The sample result is shown above.";
  }

  const listen = () => show("listening", "Listening…", example);
  silent.hidden = false;
  silent.addEventListener("click", () => {
    const skip = mode === "silent";
    stop();
    note.textContent = defaultNote;
    if (skip || motion.matches) {
      showDraft();
      return;
    }
    mode = "silent";
    listen();
    silent.textContent = "Show result";
    timer = setTimeout(() => {
      stop();
      showDraft();
    }, 2800);
  });

  // A single same-origin recording lets one user gesture start both voices.
  if (audio.canPlayType("audio/mpeg")) {
    sound.hidden = false;
    sound.addEventListener("click", () => {
      const wasPlaying = mode === "audio";
      stop();
      note.textContent = defaultNote;
      if (wasPlaying) {
        showDraft();
        return;
      }
      mode = "audio";
      listen();
      sound.textContent = "Stop sound";
      frame = requestAnimationFrame(animateFace);
      const current = attempt;
      audio.play().catch(() => {
        if (current === attempt) audioUnavailable();
      });
    });
    audio.addEventListener("timeupdate", () => {
      if (mode !== "audio") return;
      // Include the timed pause between the visitor and Brulee's reply.
      if (audio.currentTime >= replyStart) show("speaking", "Draft ready", response, true);
      else if (audio.currentTime >= 2.97) show("estimating", "Preparing draft…", example);
    });
    audio.addEventListener("ended", () => {
      if (mode !== "audio") return;
      stop();
      showDraft();
    });
    audio.addEventListener("error", () => {
      if (mode === "audio") audioUnavailable();
    });
  }
}
