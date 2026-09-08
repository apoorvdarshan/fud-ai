import {
  CHALLENGE_API_PREFIX,
  cleanupChallengeData,
  handleChallengeRequest,
} from "./challenge-api";

const REPOSITORY = "apoorvdarshan/fud-ai";
const HISTORY_KEY = "github-star-history-v1";
const HISTORY_MAX_AGE_MS = 60 * 60 * 1000;
const GITHUB_REPOSITORY_URL = `https://api.github.com/repos/${REPOSITORY}`;

interface GitHubStarWeek {
  week: number;
  total: number;
  days: number[];
}

interface StarPoint {
  date: string;
  count: number;
}

interface StarHistory {
  repository: string;
  generatedAt: string;
  total: number;
  points: StarPoint[];
}

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    const url = new URL(request.url);

    if (
      url.pathname === CHALLENGE_API_PREFIX ||
      url.pathname.startsWith(`${CHALLENGE_API_PREFIX}/`)
    ) {
      return handleChallengeRequest(request, env);
    }

    if (url.pathname === "/star-history.json") {
      const history = await getHistory(env);
      return Response.json(history, {
        headers: publicCacheHeaders("application/json; charset=utf-8"),
      });
    }

    if (url.pathname === "/star-history.svg") {
      const history = await getHistory(env);
      const theme = url.searchParams.get("theme") === "dark" ? "dark" : "light";
      return new Response(renderChart(history, theme), {
        headers: publicCacheHeaders("image/svg+xml; charset=utf-8"),
      });
    }

    return env.ASSETS.fetch(request);
  },

  scheduled(_controller: ScheduledController, env: Env, context: ExecutionContext): void {
    context.waitUntil(runScheduledMaintenance(env));
  },
} satisfies ExportedHandler<Env>;

async function runScheduledMaintenance(env: Env): Promise<void> {
  const tasks = [
    { name: "star_history", promise: refreshHistory(env) },
    { name: "challenge_cleanup", promise: cleanupChallengeData(env.CHALLENGE_DB) },
  ];
  const results = await Promise.allSettled(tasks.map((task) => task.promise));
  for (const [index, result] of results.entries()) {
    if (result.status === "rejected") {
      console.error(
        JSON.stringify({
          event: "scheduled_task_error",
          task: tasks[index]?.name ?? "unknown",
          errorType: result.reason instanceof Error ? result.reason.name : typeof result.reason,
        }),
      );
    }
  }
}

async function getHistory(env: Env): Promise<StarHistory> {
  const stored = await env.STAR_HISTORY.get(HISTORY_KEY);
  const cached = stored ? JSON.parse(stored) as StarHistory : null;
  if (cached && Date.now() - Date.parse(cached.generatedAt) < HISTORY_MAX_AGE_MS) {
    return cached;
  }

  try {
    return await refreshHistory(env);
  } catch (error) {
    console.error(JSON.stringify({ event: "star_history_refresh_failed", error: String(error) }));
    // Preserve the last successful chart during GitHub outages; keep its original
    // timestamp so subsequent requests still attempt to refresh it.
    if (cached) return cached;
    throw error;
  }
}

async function fetchGitHub(path: string): Promise<Response> {
  const response = await fetch(`${GITHUB_REPOSITORY_URL}${path}`, {
    headers: {
      Accept: "application/vnd.github+json",
      "User-Agent": "fud-ai-star-history",
      "X-GitHub-Api-Version": "2026-03-10",
    },
    signal: AbortSignal.timeout(10_000),
  });
  if (!response.ok) {
    throw new Error(`GitHub star history request failed with status ${response.status}`);
  }
  return response;
}

async function refreshHistory(env: Env): Promise<StarHistory> {
  // Aggregate history is public and does not depend on a personal access token
  // or permission to enumerate individual stargazers.
  const weeks: GitHubStarWeek[] = [];
  for (let page = 1; ; page += 1) {
    if (page > 100) throw new Error("GitHub star history pagination limit exceeded");
    const response = await fetchGitHub(`/stargazers/history?per_page=30&page=${page}`);
    weeks.push(...await response.json() as GitHubStarWeek[]);
    if (!response.headers.get("Link")?.includes('rel="next"')) break;
  }
  const response = await fetchGitHub("/stargazers/count");
  const { count } = await response.json() as { count: number };
  const points: StarPoint[] = [];
  let runningTotal = 0;
  for (const week of weeks.sort((left, right) => left.week - right.week)) {
    for (const [day, stars] of week.days.entries()) {
      if (stars === 0) continue;
      runningTotal += stars;
      points.push({
        date: new Date((week.week + day * 86_400) * 1000).toISOString().slice(0, 10),
        count: runningTotal,
      });
    }
  }
  // The count excludes removed stars. Anchor the chart to today's actual total
  // without rewriting the date or value of the last historical observation.
  const generatedAt = new Date().toISOString();
  const today = generatedAt.slice(0, 10);
  if (points.at(-1)?.date === today) points[points.length - 1].count = count;
  else points.push({ date: today, count });

  const history: StarHistory = { repository: REPOSITORY, generatedAt, total: count, points };
  await env.STAR_HISTORY.put(HISTORY_KEY, JSON.stringify(history));
  return history;
}

function publicCacheHeaders(contentType: string): HeadersInit {
  return {
    "Access-Control-Allow-Origin": "*",
    "Cache-Control": "public, max-age=300, s-maxage=300, must-revalidate",
    "Content-Type": contentType,
    "X-Content-Type-Options": "nosniff",
  };
}

function renderChart(history: StarHistory, theme: "dark" | "light"): string {
  const width = 900;
  const height = 520;
  const padding = { top: 122, right: 62, bottom: 78, left: 76 };
  const plotWidth = width - padding.left - padding.right;
  const plotHeight = height - padding.top - padding.bottom;
  const dark = theme === "dark";
  const colors = {
    background: dark ? "#0b0f13" : "#fff9fb",
    panel: dark ? "#151b20" : "#fffdf8",
    panelEdge: dark ? "#334049" : "#e4d7dc",
    grid: dark ? "#34414a" : "#eadde1",
    muted: dark ? "#9aa7ae" : "#756b70",
    text: dark ? "#fffaf5" : "#302630",
    accent: "#ff3764",
    accentSoft: dark ? "#ff91aa" : "#d91f4e",
    accentFill: dark ? "#ff37643d" : "#ff376429",
    leaf: dark ? "#9bd37e" : "#5ea862",
    sunshine: "#ffd166",
    aqua: dark ? "#66d9d0" : "#24aeb6",
  };

  if (history.points.length === 0) {
    return emptyChart(width, height, colors);
  }

  const firstDate = Date.parse(history.points[0].date);
  const lastDate = Date.parse(history.points.at(-1)!.date);
  const dateSpan = Math.max(lastDate - firstDate, 86_400_000);
  const yMax = Math.max(10, Math.ceil(Math.max(history.total, ...history.points.map((point) => point.count)) / 10) * 10);
  const x = (date: string) =>
    padding.left + ((Date.parse(date) - firstDate) / dateSpan) * plotWidth;
  const y = (count: number) =>
    padding.top + plotHeight - (count / yMax) * plotHeight;

  const linePoints = history.points.map((point) => `${x(point.date)},${y(point.count)}`);
  const linePath = `M ${linePoints.join(" L ")}`;
  const areaPath = `${linePath} L ${x(history.points.at(-1)!.date)},${padding.top + plotHeight} L ${x(history.points[0].date)},${padding.top + plotHeight} Z`;
  const yTicks = Array.from({ length: 5 }, (_, index) => Math.round((yMax / 4) * index));
  const xTicks = Array.from({ length: 5 }, (_, index) => {
    const value = new Date(firstDate + (dateSpan / 4) * index);
    return {
      date: value.toISOString().slice(0, 10),
      label: value.toLocaleDateString("en", {
        day: "numeric",
        month: "short",
        timeZone: "UTC",
      }),
    };
  });

  const gridLines = yTicks
    .map((tick) => {
      const tickY = y(tick);
      return `<path d="M ${padding.left} ${tickY} C ${padding.left + plotWidth * 0.32} ${tickY - 1}, ${padding.left + plotWidth * 0.68} ${tickY + 1}, ${padding.left + plotWidth} ${tickY}" fill="none" stroke="${colors.grid}" stroke-width="1.2" stroke-dasharray="4 7" stroke-linecap="round"/><text x="${padding.left - 16}" y="${tickY + 5}" text-anchor="end" fill="${colors.muted}" font-size="13">${tick}</text>`;
    })
    .join("");
  const dateLabels = xTicks
    .map(
      (tick) =>
        `<text x="${x(tick.date)}" y="${height - 37}" text-anchor="middle" fill="${colors.muted}" font-size="13" font-weight="600">${escapeXml(tick.label)}</text>`,
    )
    .join("");
  const leafSprouts = [0.27, 0.52, 0.76]
    .map((ratio, index) => {
      const point = history.points[Math.round((history.points.length - 1) * ratio)];
      const pointX = x(point.date);
      const pointY = y(point.count);
      const direction = index % 2 === 0 ? -1 : 1;
      return `<g transform="translate(${pointX} ${pointY}) rotate(${direction * 8})" filter="url(#softSketch)">
        <path d="M 0 1 Q ${direction * 2} -11 ${direction * 10} -16" fill="none" stroke="${colors.leaf}" stroke-width="2.2" stroke-linecap="round"/>
        <ellipse cx="${direction * 11}" cy="-17" rx="7" ry="3.8" transform="rotate(${direction * -28} ${direction * 11} -17)" fill="${colors.leaf}"/>
        <ellipse cx="${direction * 3}" cy="-10" rx="5.5" ry="3" transform="rotate(${direction * 30} ${direction * 3} -10)" fill="${colors.leaf}" opacity="0.82"/>
      </g>`;
    })
    .join("");
  const endX = x(history.points.at(-1)!.date);
  const endY = y(history.total);

  return `<svg xmlns="http://www.w3.org/2000/svg" width="${width}" height="${height}" viewBox="0 0 ${width} ${height}" role="img" aria-labelledby="title description">
  <title id="title">${escapeXml(REPOSITORY)} star history</title>
  <desc id="description">${history.total} GitHub stars as of ${escapeXml(history.generatedAt)}</desc>
  <defs>
    <linearGradient id="growthWash" x1="0" y1="0" x2="0" y2="1">
      <stop offset="0%" stop-color="${colors.accent}" stop-opacity="0.34"/>
      <stop offset="100%" stop-color="${colors.accent}" stop-opacity="0.03"/>
    </linearGradient>
    <pattern id="paperDots" width="24" height="24" patternUnits="userSpaceOnUse">
      <circle cx="3" cy="3" r="0.8" fill="${colors.grid}" opacity="0.34"/>
    </pattern>
    <filter id="softSketch" x="-20%" y="-20%" width="140%" height="140%">
      <feTurbulence type="fractalNoise" baseFrequency="0.035" numOctaves="2" seed="8" result="noise"/>
      <feDisplacementMap in="SourceGraphic" in2="noise" scale="1.15" xChannelSelector="R" yChannelSelector="G"/>
    </filter>
    <filter id="warmGlow" x="-80%" y="-80%" width="260%" height="260%">
      <feGaussianBlur stdDeviation="5" result="blur"/>
      <feMerge><feMergeNode in="blur"/><feMergeNode in="SourceGraphic"/></feMerge>
    </filter>
  </defs>
  <rect width="${width}" height="${height}" rx="28" fill="${colors.background}"/>
  <rect x="16" y="16" width="${width - 32}" height="${height - 32}" rx="26" fill="${colors.panel}" stroke="${colors.panelEdge}" stroke-width="1.5"/>
  <rect x="26" y="26" width="${width - 52}" height="${height - 52}" rx="20" fill="url(#paperDots)" stroke="${colors.panelEdge}" stroke-width="1" stroke-dasharray="7 8" opacity="0.8"/>

  <g transform="translate(48 37)" filter="url(#softSketch)">
    <path d="M 10 18 C 2 10, 5 1, 14 3 C 19 -1, 29 1, 30 11 C 31 22, 22 30, 17 30 C 11 30, 4 25, 4 18 Z" fill="${colors.accent}"/>
    <path d="M 17 4 C 17 -2, 20 -6, 24 -8" fill="none" stroke="${colors.leaf}" stroke-width="3" stroke-linecap="round"/>
    <path d="M 23 -7 C 31 -8, 33 -3, 27 1 C 23 2, 21 -1, 23 -7 Z" fill="${colors.leaf}"/>
    <path d="M 9 11 Q 16 7 24 11" fill="none" stroke="${colors.accentSoft}" stroke-width="1.8" stroke-linecap="round" opacity="0.8"/>
  </g>
  <text x="94" y="53" fill="${colors.text}" font-family="ui-rounded,'Arial Rounded MT Bold','Trebuchet MS',sans-serif" font-size="25" font-weight="800">Fud AI is growing!</text>
  <text x="95" y="77" fill="${colors.muted}" font-family="'Trebuchet MS',sans-serif" font-size="13.5" font-weight="600">Every star helps healthier habits reach a little farther.</text>

  <g transform="translate(717 34)">
    <path d="M 18 0 H 112 Q 128 0 128 16 V 38 Q 128 52 112 52 H 18 Q 0 52 0 35 V 17 Q 0 0 18 0 Z" fill="${colors.accentFill}" stroke="${colors.accent}" stroke-width="1.5" stroke-dasharray="6 4"/>
    <path d="M 25 13 L 29 22 L 39 23 L 31 30 L 33 40 L 25 35 L 16 40 L 19 30 L 11 23 L 21 22 Z" fill="${colors.sunshine}" filter="url(#warmGlow)"/>
    <text x="48" y="34" fill="${colors.text}" font-family="ui-rounded,'Arial Rounded MT Bold','Trebuchet MS',sans-serif" font-size="21" font-weight="800">${history.total}</text>
    <text x="91" y="32" fill="${colors.muted}" font-family="'Trebuchet MS',sans-serif" font-size="11" font-weight="700">STARS</text>
  </g>

  <g font-family="'Trebuchet MS',sans-serif">${gridLines}${dateLabels}</g>
  <path d="${areaPath}" fill="url(#growthWash)"/>
  <path d="${linePath}" fill="none" stroke="${colors.accentSoft}" stroke-width="7" stroke-linecap="round" stroke-linejoin="round" opacity="0.16" transform="translate(1 -1)"/>
  <path d="${linePath}" fill="none" stroke="${colors.accent}" stroke-width="4.5" stroke-linecap="round" stroke-linejoin="round" filter="url(#softSketch)"/>
  ${leafSprouts}

  <g transform="translate(${endX} ${endY})" filter="url(#softSketch)">
    <circle r="7.5" fill="${colors.accent}" stroke="${colors.panel}" stroke-width="3"/>
    <path d="M 1 -7 Q 2 -14 7 -17" fill="none" stroke="${colors.leaf}" stroke-width="2.3" stroke-linecap="round"/>
    <ellipse cx="9" cy="-17" rx="5.8" ry="3.2" transform="rotate(-24 9 -17)" fill="${colors.leaf}"/>
  </g>

  <g transform="translate(164 458)" fill="none" stroke="${colors.aqua}" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round" filter="url(#softSketch)" opacity="0.8">
    <path d="M 10 0 C 10 0, 2 10, 2 16 C 2 22, 6 25, 11 25 C 17 25, 21 21, 21 16 C 21 10, 10 0, 10 0 Z"/>
    <path d="M 28 19 C 35 14, 40 15, 45 19"/>
  </g>
  <g transform="translate(728 451) rotate(-8)" fill="none" stroke-linecap="round" stroke-linejoin="round" filter="url(#softSketch)" opacity="0.78">
    <path d="M 10 8 C 1 10, 1 20, 10 33 C 19 20, 20 10, 10 8 Z" fill="${colors.sunshine}" stroke="${colors.sunshine}" stroke-width="2"/>
    <path d="M 9 8 C 4 2, 6 -3, 10 4 C 12 -3, 18 -1, 13 8" stroke="${colors.leaf}" stroke-width="3"/>
    <path d="M 6 18 L 13 20 M 7 24 L 11 25" stroke="${colors.accentSoft}" stroke-width="1.5"/>
  </g>
</svg>`;
}

function emptyChart(
  width: number,
  height: number,
  colors: Record<string, string>,
): string {
  return `<svg xmlns="http://www.w3.org/2000/svg" width="${width}" height="${height}" viewBox="0 0 ${width} ${height}">
  <rect width="${width}" height="${height}" rx="28" fill="${colors.background}"/>
  <rect x="16" y="16" width="${width - 32}" height="${height - 32}" rx="26" fill="${colors.panel}" stroke="${colors.panelEdge}"/>
  <text x="${width / 2}" y="${height / 2 - 5}" text-anchor="middle" fill="${colors.text}" font-family="ui-rounded,'Arial Rounded MT Bold','Trebuchet MS',sans-serif" font-size="25" font-weight="800">Plant the first star ★</text>
  <text x="${width / 2}" y="${height / 2 + 27}" text-anchor="middle" fill="${colors.muted}" font-family="'Trebuchet MS',sans-serif" font-size="15">The growth doodle will begin here.</text>
</svg>`;
}

function escapeXml(value: string): string {
  return value.replace(/[<>&"']/g, (character) => {
    const entities: Record<string, string> = {
      "<": "&lt;",
      ">": "&gt;",
      "&": "&amp;",
      '"': "&quot;",
      "'": "&apos;",
    };
    return entities[character];
  });
}
