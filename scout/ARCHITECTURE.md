# PaidIn Scout architecture

```text
                    daily cron / Run Scout
                             |
                             v
                    Cloudflare Worker
                      /      |      \
                     /       |       \
          OpenAI Responses   |      Web portal
            + web_search     |      static assets
                     \       |       /
                      \      v      /
                       Cloudflare D1
                             |
                  authenticated JSON API
                       /             \
                      v               v
               PaidIn Android    Any web browser
               (offline cache)   (phone/tablet/PC)
```

The cloud service owns scheduled discovery and shared review state. User devices are clients and do not need to remain online for scheduled scans.

The OpenAI API key exists only as a Worker secret. The browser and Android clients authenticate using a separate `SCOUT_TOKEN` and never receive the OpenAI key.
