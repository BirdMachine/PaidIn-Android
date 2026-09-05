# Scout deployment checklist

- Create Cloudflare D1 database `paidin-scout`.
- Replace `REPLACE_WITH_D1_DATABASE_ID` in `wrangler.jsonc`.
- Run `npm run db:init:remote`.
- Add Wrangler secret `OPENAI_API_KEY`.
- Add Wrangler secret `SCOUT_TOKEN` using a long random value.
- Run `npm run deploy`.
- Open the Worker URL and save the private search brief.
- Run one manual Scout scan and confirm jobs appear.
- Put the Worker URL + Scout token into PaidIn Android Settings.
- Confirm Android Refresh reads the same queue.
- Confirm Save / Approve / Reject propagates between web and Android.
- Leave the default daily cron enabled, or adjust it in `wrangler.jsonc`.
