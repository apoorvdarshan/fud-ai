import { access, readFile } from "node:fs/promises";

const requiredFiles = [
  "404.html",
  "_headers",
  "add-meal.html",
  "ai-food-scanner.html",
  "free-ai-calorie-tracker.html",
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

// Verify the landing page's local assets and navigation, including legal section links.
const homepage = await readFile("index.html", "utf8");
for (const [, path] of homepage.matchAll(/\b(?:href|src)="((?:\/(?!\/)|#)[^"]*)"/g)) {
  const url = new URL(path, "https://www.fud-ai.app/");
  const pathname = url.pathname.slice(1);
  const file = pathname ? pathname.includes(".") ? pathname : `${pathname}.html` : "index.html";
  if (url.hash) {
    const document = file === "index.html" ? homepage : await readFile(file, "utf8");
    if (!document.includes(`id="${url.hash.slice(1)}"`)) throw new Error(`Missing landing page destination ${path}`);
  } else {
    await access(file);
  }
}

for (const file of requiredFiles.filter((name) => name.startsWith(".well-known/"))) {
  JSON.parse(await readFile(file, "utf8"));
}

console.log(`Validated ${requiredFiles.length} static deployment files.`);
