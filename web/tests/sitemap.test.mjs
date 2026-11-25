import { mkdtemp, mkdir, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join, sep } from "node:path";
import { pathToFileURL } from "node:url";
import { afterEach, beforeEach, expect, it } from "vitest";
import { checkSitemap, renderSitemap } from "../scripts/sitemap.mjs";

const origin = "https://www.fud-ai.app";
let dir, root;

beforeEach(async () => {
  dir = await mkdtemp(join(tmpdir(), "fud-sitemap-"));
  root = pathToFileURL(dir + sep);
  await mkdir(new URL("guides/", root));
});
afterEach(async () => { await rm(dir, { recursive: true, force: true }); });

const page = (file, { path = `/${file.slice(0, -5)}`, date = "2026-11-26", title = file,
  description = `${file} description`, noindex = false } = {}) => writeFile(new URL(file, root), `
    <title>${title}</title><meta name="description" content="${description}">
    ${noindex ? '<meta name="robots" content="noindex,follow">' : ""}
    <link rel="canonical" href="${origin}${path}">
    <script type="application/ld+json">${JSON.stringify({
      "@graph": [{ "@type": "WebPage", url: origin + path, dateModified: date }],
    })}</script>`);

it("includes canonical nested guides and skips noindex utility pages", async () => {
  await page("index.html", { path: "/" });
  await page("guides/meal.html");
  await page("add-meal.html", { noindex: true });
  const xml = await renderSitemap(root);
  expect(xml).toContain(`<loc>${origin}/</loc>`);
  expect(xml).toContain(`<loc>${origin}/guides/meal</loc>`);
  expect(xml).not.toContain("add-meal");
  expect(xml).toContain("<lastmod>2026-11-26</lastmod>");
});

it.each(["2026-02-31", "2026-99-04"])("rejects invalid calendar date %s", async (date) => {
  await page("guides/meal.html", { date });
  await expect(renderSitemap(root)).rejects.toThrow("invalid WebPage dateModified");
});

it.each(["title", "description"])("rejects a duplicate %s", async (field) => {
  await page("guides/first.html", { [field]: "Duplicate" });
  await page("guides/second.html", { [field]: "Duplicate" });
  await expect(renderSitemap(root)).rejects.toThrow("duplicate title/description");
});

it("rejects an alias as the canonical URL", async () => {
  await page("guides/meal.html", { path: "/guides/meal.html" });
  await expect(renderSitemap(root)).rejects.toThrow("expected canonical");
});

it("rejects stale XML or an incorrect robots sitemap URL", async () => {
  await page("guides/meal.html");
  const xml = new URL("sitemap.xml", root);
  await writeFile(xml, "<urlset/>");
  await expect(checkSitemap(root)).rejects.toThrow("Stale sitemap");
  await writeFile(xml, await renderSitemap(root));
  const robots = new URL("robots.txt", root);
  await writeFile(robots, "User-agent: *\n");
  await expect(checkSitemap(root)).rejects.toThrow("canonical sitemap");
  await writeFile(robots, `Sitemap: ${origin}/sitemap.xml\n`);
  await expect(checkSitemap(root)).resolves.toBeUndefined();
});
