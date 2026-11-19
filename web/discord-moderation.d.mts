export function runDiscordModeration(env: {
  DISCORD_MODERATION_ENABLED?: string;
  DISCORD_GUILD_ID?: string;
  DISCORD_MODERATION?: DurableObjectNamespace;
}): Promise<void>;
export class DiscordModeration implements DurableObject {
  constructor(ctx: DurableObjectState, env: Env);
  fetch(request: Request): Promise<Response>;
}
