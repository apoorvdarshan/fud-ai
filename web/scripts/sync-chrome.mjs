import { readFile, writeFile } from "node:fs/promises";

const main = new URL("../index.html", import.meta.url);
const pages = [
  "404.html",
  "ai-food-scanner.html",
  "free-ai-calorie-tracker.html",
  "open-source-calorie-tracker.html",
  "privacy.html",
  "terms.html",
  "vs-cal-ai.html",
];
const chrome = [
  /<link rel="stylesheet" href="\/flash\.css\?v=\d+">/,
  / {2}<header class="site-header"[\s\S]+? {2}<\/header>/,
  / {2}<footer class="shell site-footer"[\s\S]+? {2}<\/footer>/,
];

// Keep the shell in the HTML (no JavaScript or network needed for navigation).
// After changing the homepage header, footer, or theme version, run:
//   node web/scripts/sync-chrome.mjs --write
export async function syncChrome(write = false) {
  const homepage = await readFile(main, "utf8");
  const source = chrome.map((pattern) => homepage.match(pattern)?.[0]);
  if (source.some((part) => !part)) throw new Error("Shared site chrome is missing from index.html");
  for (const name of pages) {
    const url = new URL(`../${name}`, import.meta.url);
    const html = await readFile(url, "utf8");
    let updated = html;
    chrome.forEach((pattern, index) => {
      if (!pattern.test(html)) throw new Error(`Shared site chrome is missing from ${name}`);
      updated = updated.replace(pattern, () => source[index]);
    });
    if (html === updated) continue;
    if (!write) throw new Error(`Shared site chrome differs in ${name}; run node web/scripts/sync-chrome.mjs --write`);
    await writeFile(url, updated);
  }
}

if (process.argv.includes("--write")) await syncChrome(true);
