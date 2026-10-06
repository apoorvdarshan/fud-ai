/** Shared Discord posting used by the store release announcers. */

export const ANNOUNCEMENTS_CHANNEL_ID = "1548481417728495678";

/** Posts one message to #announcements, or throws with the HTTP status. */
export async function postAnnouncement(
  token: string,
  content: string,
  fetchImpl: typeof fetch,
): Promise<void> {
  const response = await fetchImpl(
    `https://discord.com/api/v10/channels/${ANNOUNCEMENTS_CHANNEL_ID}/messages`,
    {
      method: "POST",
      headers: {
        Authorization: `Bot ${token}`,
        "Content-Type": "application/json",
        "User-Agent": "fud-ai-store-announce",
      },
      body: JSON.stringify({ content, allowed_mentions: { parse: [] } }),
    },
  );
  if (!response.ok) {
    throw new Error(`discord_announce_failed_${response.status}`);
  }
}
