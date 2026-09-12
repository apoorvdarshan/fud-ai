// Secrets are not emitted by `wrangler types`; only their names belong here.
interface Env {
  GITHUB_TOKEN: string;
  GEMINI_API_KEY?: string;
  DEEPGRAM_API_KEY?: string;
  FUD_HOSTED_AI_APP_SECRET?: string;
}
