import { access, readFile, readdir, writeFile } from "node:fs/promises";

const root = new URL("../", import.meta.url);
const origin = "https://www.fud-ai.app";
const xmlFile = new URL("sitemap.xml", root);
const escapeXml = (text) => text.replace(/[&<>"']/g, (char) =>
  ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&apos;" })[char]);

// Index the public HTML pages, not error pages, meal shares, or .html aliases.
// Source lastmod from the page's WebPage schema rather than the build clock.
export async function renderSitemap() {
  const entries = [];
  const titles = new Set();
  const descriptions = new Set();
  const files = (await readdir(root, { withFileTypes: true }))
    .filter((file) => file.isFile() && file.name.endsWith(".html"))
    .map((file) => file.name).sort();
  for (const name of files) {
    const html = await readFile(new URL(name, root), "utf8");
    if (/<meta name="robots" content="[^"]*\bnoindex\b/.test(html)) continue;

    const path = name === "index.html" ? "/" : `/${name.slice(0, -5)}`;
    const canonical = `${origin}${path}`;
    if (html.match(/<link rel="canonical" href="([^"]+)">/)?.[1] !== canonical) {
      throw new Error(`${name}: expected canonical ${canonical}`);
    }
    for (const [selector, seen] of [[/<title>([^<]+)<\/title>/, titles], [/<meta name="description" content="([^"]+)">/, descriptions]]) {
      const value = html.match(selector)?.[1];
      if (!value || seen.has(value)) throw new Error(`${name}: missing or duplicate title/description`);
      seen.add(value);
    }

    const schemas = [...html.matchAll(/<script type="application\/ld\+json">([\s\S]*?)<\/script>/g)]
      .flatMap((match) => {
        const value = JSON.parse(match[1]);
        return value["@graph"] ?? [value];
      });
    const date = schemas.find((value) => value["@type"] === "WebPage" && value.url === canonical)?.dateModified;
    if (!date || !/^\d{4}-\d{2}-\d{2}$/.test(date) || new Date(date).toISOString().slice(0, 10) !== date) {
      throw new Error(`${name}: missing or invalid WebPage dateModified`);
    }

    const screenshots = schemas.find((value) => value["@type"] === "SoftwareApplication")?.screenshot ?? [];
    const images = path === "/" ? [
      html.match(/<meta property="og:image" content="([^"]+)">/)?.[1],
      ...screenshots,
    ] : [];
    for (const image of images) {
      const url = new URL(image);
      if (url.origin !== origin) throw new Error(`${name}: noncanonical sitemap image`);
      await access(new URL(url.pathname.slice(1), root));
    }
    entries.push({ path, xml: [
      "  <url>",
      `    <loc>${escapeXml(canonical)}</loc>`,
      `    <lastmod>${date}</lastmod>`,
      ...images.map((url) => `    <image:image><image:loc>${escapeXml(url)}</image:loc></image:image>`),
      "  </url>",
    ].join("\n") });
  }
  entries.sort((a, b) => a.path.localeCompare(b.path, "en"));
  return [
    '<?xml version="1.0" encoding="UTF-8"?>',
    '<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9" xmlns:image="http://www.google.com/schemas/sitemap-image/1.1">',
    ...entries.map(({ xml }) => xml),
    "</urlset>",
    "",
  ].join("\n");
}

export async function checkSitemap() {
  if (await readFile(xmlFile, "utf8") !== await renderSitemap()) {
    throw new Error("Stale sitemap.xml; run npm --prefix web run sitemap");
  }
  const robots = await readFile(new URL("robots.txt", root), "utf8");
  if (!robots.split(/\r?\n/).includes(`Sitemap: ${origin}/sitemap.xml`)) {
    throw new Error("robots.txt does not reference the canonical sitemap");
  }
}

if (process.argv.includes("--write")) await writeFile(xmlFile, await renderSitemap());
