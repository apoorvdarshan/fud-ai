// Secrets are not emitted by `wrangler types`; only their names belong here.
// Values are set with `wrangler secret put <NAME>` (never committed).
interface Env {
  GITHUB_TOKEN: string;
  /** Google AI Studio key for the hosted Gemini path. Set at production time. */
  GEMINI_API_KEY?: string;
  /** Deepgram key for hosted voice STT. Set at production time. */
  DEEPGRAM_API_KEY?: string;
  /**
   * RevenueCat v1 secret API key (`sk_…`) used to verify subscriber
   * entitlements server-side. Never shipped to clients.
   */
  REVENUECAT_API_KEY?: string;
}
