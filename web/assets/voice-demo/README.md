# Live Voice website demo

`conversation.mp3` is fictional English dialogue synthesized offline with
[Kokoro-82M v1.0](https://huggingface.co/hexgrad/Kokoro-82M) (Apache 2.0),
using `am_michael` and `af_heart`. It is not a recording of the live AI service
or a real user's voice. No model weights or voice packs ship with the website.

The first line runs for about 2.97 seconds, followed by a 0.60 second pause
before Brulee's reply. Update the caption timing and reply amplitude envelope
in `flash-demo.js` if the recording changes. Playback starts only by request.

`brulee-blink.webp` is the app’s existing transparent eyelid sprite
(`VoiceMascotBlink.imageset`) resized to align with the 360 px website mascot.
