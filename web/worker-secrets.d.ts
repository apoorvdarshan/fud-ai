// Secrets are not emitted by `wrangler types`; only their names belong here.
// Values are set with `wrangler secret put <NAME>` (never committed).
interface Env {
  /** HMAC secret for the repository issues webhook; never committed. */
  DISCORD_GITHUB_WEBHOOK_SECRET?: string;
  /**
   * GitHub token for star-history refresh and Discord `/bug` / `/feature` issues.
   * Set with `npx wrangler secret put GITHUB_TOKEN` (never commit the value).
   * `/bug` and `/feature` need `issues:write` on `apoorvdarshan/fud-ai`.
   */
  GITHUB_TOKEN: string;
  /**
   * Google AI Studio key for the hosted Gemini path, including the live voice
   * relay (sent to Gemini Live in the `x-goog-api-key` header). Set at production time.
   */
  GEMINI_API_KEY?: string;
  /**
   * Optional plain variable (not a secret): the Gemini Live model the voice
   * relay pins, without the `models/` prefix. Defaults to `gemini-3.8-live`;
   * override under `[vars]` in wrangler.toml.
   */
  HOSTED_AI_LIVE_MODEL?: string;
  /** Deepgram key for hosted voice STT. Set at production time. */
  DEEPGRAM_API_KEY?: string;
  /**
   * RevenueCat v1 secret API key (`sk_…`) used to verify subscriber
   * entitlements server-side. Never shipped to clients.
   */
  REVENUECAT_API_KEY?: string;
  /**
   * Free-tier Gemini key for Discord `/ask` only. Never fall back to
   * GEMINI_API_KEY (that secret is billed hosted AI).
   */
  DISCORD_GEMINI_API_KEY?: string;
  /** Discord application public key (also set via wrangler `[vars]`). */
  DISCORD_PUBLIC_KEY: string;
  /** Discord application id (also set via wrangler `[vars]`). */
  DISCORD_APPLICATION_ID?: string;
  /**
   * Bot token for release announcements and issue-closure replies.
   * Set with `npx wrangler secret put DISCORD_BOT_TOKEN`.
   */
  DISCORD_BOT_TOKEN?: string;
  /**
   * Play service-account JSON. The hourly job reads production.
   * Set with `npx wrangler secret put PLAY_SERVICE_ACCOUNT_JSON`.
   */
  PLAY_SERVICE_ACCOUNT_JSON?: string;
  /**
   * App Store Connect API key (Customer Support role) the hourly job uses to
   * read the live iOS version: its key ID, issuer ID, and `.p8` PEM contents.
   * Set each with `npx wrangler secret put <NAME>`.
   */
  ASC_KEY_ID?: string;
  ASC_ISSUER_ID?: string;
  ASC_PRIVATE_KEY?: string;
}
