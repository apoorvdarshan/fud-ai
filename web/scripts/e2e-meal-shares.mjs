#!/usr/bin/env node
/** Production smoke test for POST /api/meal-shares and GET /m/:id preview. */
const origin = "https://www.fud-ai.app";
const ua = "FudAI/1.0 (e2e; MealShare)";

const payload = {
  v: 1,
  meals: [{ name: "E2E Oatmeal", calories: 350, protein: 12, carbs: 55, fat: 8 }],
};

const create = await fetch(`${origin}/api/meal-shares`, {
  method: "POST",
  headers: { "Content-Type": "application/json", "User-Agent": ua },
  body: JSON.stringify(payload),
});
if (create.status !== 201) {
  console.error("create failed", create.status, await create.text());
  process.exit(1);
}
const { url } = await create.json();
if (!/^https:\/\/www\.fud-ai\.app\/m\/[A-Za-z0-9_-]{22}$/.test(url)) {
  console.error("unexpected url", url);
  process.exit(1);
}

const preview = await fetch(url, { headers: { "User-Agent": ua } });
if (preview.status !== 200) {
  console.error("preview failed", preview.status, await preview.text());
  process.exit(1);
}
const html = await preview.text();
if (!html.includes("E2E Oatmeal") || !html.includes("fudai://add-meal?d=")) {
  console.error("preview missing expected content");
  process.exit(1);
}
console.log(JSON.stringify({ ok: true, url }, null, 2));
