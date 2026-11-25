# ChatGPT plan access in Fud AI

**Status: waiting on OpenAI access.** The existing **Settings → AI Providers & Fallbacks → OpenAI** route uses an OpenAI API key and separate API billing. Signing into ChatGPT or buying ChatGPT Plus or Pro does not activate that API route.

Fud AI is open source, but it also offers paid Hosted AI. OpenAI's [integration guide](https://developers.openai.com/siwc/token-sharing-open-source) directs paid or remotely hosted apps to its [interest form](https://openai.com/form/sign-in-with-chatgpt-interest/) before offering this feature. Confirm eligibility and the supported native-app callback with OpenAI for both Android and iOS. Do not register the commercial app as a different project or reuse a client from another app.

## Intended experience after approval

- Under **Settings → AI Providers & Fallbacks**, offer **ChatGPT plan** next to the API-key-based **OpenAI** provider, with **Continue with ChatGPT**. Do not require a ChatGPT connection to use other providers or log food.
- Separate identity sign-in from authorization to use plan allowance. Only enable the route after the connection has returned a verified grant and shows eligible models. Show **Using ChatGPT plan**, **Manage usage**, and **Disconnect**. Don't charge a Fud AI subscription or require an upgrade to use this route.
- Use the [official OAuth flow](https://developers.openai.com/siwc/token-sharing-open-source/sign-in), with PKCE, state, nonce, and ID token validation. Keep credentials device-local in Keychain or Keystore-backed storage, out of backups, logs, and the Fud AI Worker. Requests go directly from the user's device to OpenAI.
- Discover available models for that connection. Route photos only to a model that accepts images. Follow OpenAI's [Responses API requirements](https://developers.openai.com/siwc/token-sharing-open-source/models-and-inference) (`store: false`, streaming, explicit completion). Treat identity-only grants, revoked permissions, and exhausted usage as distinct states. Don't spend plan allowance in the background or switch to a paid API route silently.

See the [Sign in with ChatGPT terms](https://openai.com/policies/sign-in-with-chatgpt-terms/) and [preview limitations](https://developers.openai.com/siwc/token-sharing-open-source/preview-limitations). This route is not a replacement for Live Voice or transcription.
