import { access, readFile, readdir } from "node:fs/promises";
import { syncChrome } from "./sync-chrome.mjs";
import { checkSitemap } from "./sitemap.mjs";

const requiredFiles = [
  "404.html",
  "_headers",
  "add-meal.html",
  "ai-food-scanner.html",
  "assets/voice-demo/conversation.mp3",
  "free-ai-calorie-tracker.html",
  "guides.html",
  "flash.css",
  "flash-demo.js",
  "index.html",
  "open-source-calorie-tracker.html",
  "privacy.html",
  "robots.txt",
  "sitemap.xml",
  "terms.html",
  "vs-cal-ai.html",
  ".well-known/apple-app-site-association",
  ".well-known/assetlinks.json",
];

await Promise.all(requiredFiles.map((file) => access(file)));
await syncChrome();
await checkSitemap();

// Check internal assets and anchors on each public HTML page, not just home.
const htmlFiles = requiredFiles.filter((name) => name.endsWith(".html"));
htmlFiles.push(...(await readdir("guides")).filter((name) => name.endsWith(".html")).map((name) => `guides/${name}`));
const documents = new Map(await Promise.all(htmlFiles.map(async (name) => [name, await readFile(name, "utf8")])));
for (const [name, html] of documents) {
  if (!html.includes('href="/flash.css?')) throw new Error(`${name} does not load the Flash theme`);
  if (/<a\b[^>]*href="(?:https:\/\/(?:www\.)?fud-ai\.app\/|\/)?[\w/-]+\.html(?:[?#][^"]*)?"/.test(html)) {
    throw new Error(`${name}: link to a redirected .html URL; use its canonical path`);
  }
  for (const [, path] of html.matchAll(/\b(?:href|src)="((?:\/(?!\/)|#)[^"]*)"/g)) {
    const url = new URL(path, `https://www.fud-ai.app/${name}`);
    const pathname = url.pathname.slice(1);
    const file = pathname ? pathname.includes(".") ? pathname : `${pathname}.html` : "index.html";
    if (url.hash) {
      const target = documents.get(file) ?? await readFile(file, "utf8");
      if (!target.includes(`id="${url.hash.slice(1)}"`)) throw new Error(`${name}: missing destination ${path}`);
    } else {
      await access(file);
    }
  }
}

for (const file of requiredFiles.filter((name) => name.startsWith(".well-known/"))) {
  JSON.parse(await readFile(file, "utf8"));
}

console.log(`Validated ${htmlFiles.length} HTML pages, canonical links, and sitemap.`);
