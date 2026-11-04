# Automatic community moderation

The `DiscordModeration` Durable Object runs in the existing website Worker. A
one-minute cron calls it through a private binding; there is no public scan or
moderation endpoint and no process running on a laptop. The same implementation
is kept in Fuddy's `web/discord-moderation.mjs` and Vercie's
`web/worker/discord-moderation.mjs`; keep the matching regression suites in sync.
Each deployment uses its own bot token, namespace, server ID and private log.

## Policy

- First qualifying incident: delete the triggering message, warn in its channel,
  and apply a 10-minute timeout.
- Second incident after that timeout: delete, warn, and apply a 24-hour timeout.
- Third incident after that timeout: delete and automatically ban. No approval
  queue. Ban requests do not bulk-delete unrelated message history.
- Thirty days without a new strike resets the count. A ban remains until the
  owner removes it in Discord; resetting a counter does not unban anyone.
- Additional detected spam sent before the current timeout ends is removed but
  does not add another strike or extend the timeout. Existing longer timeouts
  are never shortened. Server history predating initial activation is ignored.
- Apoorv's verified user ID `823100527675965480` and the verified server owner
  are exempt before any deletion, warning, timeout or ban. Bots, webhooks,
  system messages, administrators and members at/above the bot's role are
  excluded too. Ordinary GitHub identities/links and support discussions are
  allowed, including “This is me on github @candzon”.

The deliberately narrow automatic triggers are:

1. Six distinct non-bot user mentions or three role mentions in a message.
2. Five identical messages of at least 20 characters within 60 seconds.
3. Three identical promotional messages containing a link and an explicit
   solicitation (for example “join my”, “buy now”, “hire me”) within five minutes.
4. A message beginning with a free-Nitro claim/get solicitation and linking to
   a host outside `discord.com`, `www.discord.com`, `discord.gift`, or
   `www.discord.gift`.

Matching repetitions ignore case and whitespace. Quoted/code-block content
does not trigger the text rules. Potential single-message promotions only
produce a private flag, at most once per member per day; no strike or warning.
There is no AI judgment, arbitrary banned-word list, or following submitted
links. This is not comprehensive scam detection.

## Configuration and deployment

Worker vars: `DISCORD_MODERATION_ENABLED` must equal `true` (anything else stops
monitoring), `DISCORD_GUILD_ID`, `DISCORD_APPLICATION_ID`, and
`DISCORD_MODERATION_LOG_CHANNEL_ID`. The existing `DISCORD_BOT_TOKEN` is reused
only inside its own deployment. `DISCORD_MODERATION` binds to a SQLite-backed
Durable Object introduced by migration `discord-moderation-v1`.

Bot permissions: View Channel, Read Message History, Send Messages, Manage
Messages, Moderate Members/Timeout Members, Ban Members, and Send Messages in
Threads where applicable. Its role must be above ordinary members. The
application's Message Content intent must be enabled. No Administrator grant
is needed. The log must deny View Channel to @everyone and only explicitly
allow the owner and this bot. Discord administrators can also view it.

The scanner verifies the bot identity, owner, guild, intent, permissions and
private log on each run. Wrong/missing configuration fails closed. Unreadable
channels are skipped with an operational warning. A new deployment does not
reset existing strikes. To disable, set `DISCORD_MODERATION_ENABLED` to `false`
and redeploy; this does not remove existing Discord timeouts or bans.

Deployment is through the project's existing `web` Wrangler configuration,
preserving its website domains, assets, secrets and unrelated schedules. Run
the web checks before `wrangler deploy`. Verceltics additionally requires its
static Next.js build before deploy. Confirm the one-minute cron in Cloudflare
and `discord_moderation_scan_ok` in live logs before announcing activation.
Never manufacture a real user's violation to test enforcement.

## State, delivery and limitations

One active scan per object serializes operations. A transaction atomically saves
the message cursor, strike reservation and pending action before Discord writes.
Successful steps are checkpointed, so retries do not add strikes or restart
timeouts. Deletes and ban PUTs are idempotent; warnings/logs use deterministic
nonces with Discord's limited deduplication window. Exactly-once notification
delivery across a long outage is not guaranteed.

HTTP 429 saves the retry-after deadline. Other action failures retry up to five
times, then save a failure marker and attempt a private failure notice. Unconfirmed
punishments roll back their strike reservation; completed punishments retain it.
An expired timeout reservation is abandoned rather than issuing a stale penalty.
Configuration failures remain stopped until fixed. Neither logs nor warnings
claim that a failed punishment succeeded.

Each scan reads up to 100 new messages per accessible text/announcement channel
and active thread, using ascending snowflake cursors, with a 45-second work
budget and channel rotation. Backlogs resume on later runs. This means moderation
is normally about a minute later, not immediate blocking. Edited messages,
deleted-before-scan messages, archived/inaccessible threads, DMs, voice chat,
and image/attachment contents are not monitored. Busy-server backlogs can delay
enforcement. The first copies in a repetition burst are not retroactively removed.

No raw message text, bot token or interaction token is persisted in moderation
state. Member records hold IDs, up to 30 content hashes/timestamps, counters and
cooldown metadata. Fingerprints are only matched in a five-minute window;
records become eligible for cleanup after 30 days without a scanned message.
Cursor/activation state persists; the most recent failure marker persists until
replaced. Private Discord logs contain IDs, reasons and message links, not copied
text; Discord messages persist until removed by the owner.

For an appeal/reset, disable monitoring and use a reviewed, one-off code migration inside the Durable Object to remove the matching
`user:<Discord user ID>` record and any matching `pending` action, then remove the migration and re-enable. There is no public reset endpoint or built-in remote storage editor.
Remove a timeout or ban separately in Discord. Do not remove `started` or cursor
keys unless intentionally resetting the activation boundary. The websites'
Discord privacy sections disclose this processing.

Tests cover the reported GitHub identity, exemptions, false positives, burst
handling, escalation/reset, longer timeouts, pagination, private flags, disabled
mode, missing permissions/intent, storage failure, retries and nonces. Live
verification should inspect cron/log health and permissions without targeting
real members with synthetic spam or bans.
