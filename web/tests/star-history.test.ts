import { afterEach, describe, expect, it, vi } from "vitest";
import worker from "../worker";

const cached = {
  repository: "apoorvdarshan/fud-ai",
  generatedAt: "2026-08-14T12:17:49.178Z",
  total: 338,
  points: [{ date: "2026-08-14", count: 338 }],
};

function environment(history: typeof cached | null = cached) {
  const put = vi.fn().mockResolvedValue(undefined);
  const env = { STAR_HISTORY: { get: vi.fn().mockResolvedValue(JSON.stringify(history)), put } } as unknown as Env;
  return { env, put };
}

const request = (path = "json") => new Request(`https://fud-ai.app/star-history.${path}`);

afterEach(() => { vi.unstubAllGlobals(); vi.restoreAllMocks(); });

describe("star history refresh", () => {
  it("refreshes stale data using every public history page and the current count", async () => {
    const { env, put } = environment();
    const fetch = vi.fn()
      .mockResolvedValueOnce(Response.json([{ week: 1788652800, total: 2, days: [2, 0, 0, 0, 0, 0, 0] }], { headers: { Link: '<https://api.github.com/next>; rel="next"' } }))
      .mockResolvedValueOnce(Response.json([{ week: 1788048000, total: 3, days: [3, 0, 0, 0, 0, 0, 0] }]))
      .mockResolvedValueOnce(Response.json({ count: 4 }));
    vi.stubGlobal("fetch", fetch);
    const response = await worker.fetch(request(), env);
    const history = await response.json() as typeof cached;
    expect(history.total).toBe(4);
    expect(history.points.map(point => point.count)).toEqual([3, 5, 4]);
    expect(history.points.at(-1)?.date).toBe(new Date().toISOString().slice(0, 10));
    expect(fetch.mock.calls[1][0]).toContain("page=2");
    expect(fetch.mock.calls[0][1].headers).not.toHaveProperty("Authorization");
    expect(put).toHaveBeenCalledOnce();
    expect(response.headers.get("Cache-Control")).toBe("public, max-age=300, s-maxage=300, must-revalidate");
  });

  it("serves fresh cached data without calling GitHub", async () => {
    const { env } = environment({ ...cached, generatedAt: new Date().toISOString() });
    const fetch = vi.fn();
    vi.stubGlobal("fetch", fetch);
    const response = await worker.fetch(request("svg?theme=dark"), env);
    expect(await response.text()).toContain("338 GitHub stars");
    expect(fetch).not.toHaveBeenCalled();
  });

  it("preserves the previous chart and timestamp on GitHub failure without writing partial data", async () => {
    const { env, put } = environment();
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response("rate limited", { status: 403 })));
    vi.spyOn(console, "error").mockImplementation(() => {});
    const response = await worker.fetch(request(), env);
    expect(await response.json()).toEqual(cached);
    expect(put).not.toHaveBeenCalled();
  });

  it("builds an empty-cache chart for a repo with no stars", async () => {
    const { env } = environment(null);
    vi.stubGlobal("fetch", vi.fn().mockResolvedValueOnce(Response.json([])).mockResolvedValueOnce(Response.json({ count: 0 })));
    const response = await worker.fetch(request(), env);
    expect(await response.json()).toMatchObject({ total: 0, points: [{ count: 0 }] });
  });
});
