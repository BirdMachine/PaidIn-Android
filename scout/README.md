# PaidIn Scout 🐬🌊

PaidIn Scout is the always-on cloud companion for PaidIn Android. It discovers live job listings with OpenAI Responses API web search, stores the normalized review queue in Cloudflare D1, exposes the same queue to Android, and serves a responsive browser cockpit.

Mallard, Kestrel, and the Android app are clients. None of them need to stay powered on for the morning scan to happen.

## Architecture

- **Cloudflare Worker** — authenticated JSON API, static web portal, and scheduled scan handler
- **Cloudflare D1** — jobs, review status, Scout settings, and scan history
- **OpenAI Responses API** — live web search + structured job-feed output; no site-specific scraper code
- **Android app / browser** — interchangeable review clients

The default schedule stays at **9:00 AM America/New_York year-round**. Cloudflare Cron Triggers execute in UTC, so Wrangler registers both `0 13 * * *` and `0 14 * * *`; the scheduled handler checks the configured local timezone/hour and only the matching invocation actually runs a scan. Change `SCOUT_TIMEZONE` / `SCOUT_HOUR` if desired.

## First deployment

From this directory:

```bash
npm install
npx wrangler login
npx wrangler d1 create paidin-scout
```

Copy the returned D1 database ID into `wrangler.jsonc`, replacing `REPLACE_WITH_D1_DATABASE_ID`.

Initialize the remote database:

```bash
npm run db:init:remote
```

Add secrets. Neither secret belongs in Git:

```bash
npx wrangler secret put OPENAI_API_KEY
npx wrangler secret put SCOUT_TOKEN
```

`SCOUT_TOKEN` is simply a long random access token shared by your browser and Android app. The OpenAI key never leaves the Worker.

Deploy:

```bash
npm run deploy
```

Wrangler will print the HTTPS Worker URL. Open that URL in any browser, enter the same Scout access token, save your job-search brief, and press **Run Scout now** once to test it.

Then put that Worker URL and token in **PaidIn Android → Settings → Cloud Scout**.

## Local development

```bash
npm install
npm run db:init:local
npm run dev
```

For local calls that actually use OpenAI, provide local Worker secrets through Wrangler's supported local-secret mechanism rather than committing them.

## API

All `/api/*` routes except `/api/health` require `Authorization: Bearer <SCOUT_TOKEN>` (or `X-Scout-Token`).

- `GET /api/health`
- `GET /api/jobs`
- `POST /api/scan`
- `GET /api/settings`
- `PUT /api/settings`
- `PATCH /api/jobs/:id/status` (web client)
- `POST /api/jobs/:id/status` (Android-compatible equivalent)
- `GET /api/scan-runs`

## Cost shape

The Worker/D1 workload is intentionally tiny and suitable for Cloudflare's free tier at personal scale. OpenAI API usage is separate: each Scout run incurs model tokens and built-in web-search usage, so the app defaults to one scheduled scan per day plus manual scans when requested.
