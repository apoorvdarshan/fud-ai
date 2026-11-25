"use strict";

const control = document.querySelector("#play-demo");
const demo = document.querySelector(".voice-demo");
const demoStatus = document.querySelector("#demo-status");
const caption = document.querySelector("#demo-caption");
const result = document.querySelector("#demo-result");

if (control && demo && demoStatus && caption && result) {
  const example = caption.textContent;
  const motionPreference = matchMedia("(prefers-reduced-motion: reduce)");
  let timers = [];

  function showResult() {
    demo.dataset.step = "complete";
    demoStatus.textContent = "Draft ready";
    caption.textContent = "Review this sample entry before logging it.";
    result.hidden = false;
    control.textContent = "Run example again";
    timers = [];
  }

  control.hidden = false;
  control.addEventListener("click", () => {
    if (timers.length) {
      timers.forEach(clearTimeout);
      showResult();
      return;
    }
    if (motionPreference.matches) {
      showResult();
      return;
    }
    demo.dataset.step = "listening";
    demoStatus.textContent = "Listening to the example";
    caption.textContent = example;
    result.hidden = true;
    control.textContent = "Show result";

    timers = [
      setTimeout(() => {
        demo.dataset.step = "estimating";
        demoStatus.textContent = "Estimating";
      }, 950),
      setTimeout(showResult, 1700),
    ];
  });
}
