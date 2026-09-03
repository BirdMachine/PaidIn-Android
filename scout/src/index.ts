interface Env {
  DB: D1Database;
  ASSETS: Fetcher;
  OPENAI_API_KEY: string;
  SCOUT_TOKEN: string;
  SCOUT_MODEL?: string;
}

type ScoutJob = {
  title: string;
  company: string;
  location: string;
  remote_status: string;
  salary_min: number | null;
  salary_max: number | null;
  source: string;
  source_url: string;
  description: string;
  skills: string[];
  score: number;
  fit_summary: string;
};

const JSON_HEADERS = {
  "content-type": "application/json; charset=utf-8",
  "access-control-allow-origin": "*",
  "access-control-allow-headers": "authorization, content-type, x-scout-token",
  "access-control-allow-methods": "GET, POST, PUT, PATCH, OPTIONS",
};

export default {
  async fetch(request: Request, env: Env, ctx: ExecutionContext): Promise<Response> {
    const url = new URL(request.url);

    if (request.method === "OPTIONS") return new Response(null, { status: 204, headers: JSON_HEADERS });

    if (url.pathname === "/api/health") {
      return json({ ok: true, service: "paidin-scout" });
    }

    if (url.pathname.startsWith("/api/")) {
      if (!authorized(request, env)) return json({ error: "Unauthorized" }, 401);

      try {
        if (url.pathname === "/api/jobs" && request.method === "GET") return getJobs(env);
        if (url.pathname === "/api/settings" && request.method === "GET") return getSettings(env);
        if (url.pathname === "/api/settings" && request.method === "PUT") return putSettings(request, env);
        if (url.pathname === "/api/scan" && request.method === "POST") return runScan(env, "manual");
        if (url.pathname === "/api/scan-runs" && request.method === "GET") return getScanRuns(env);

        const statusMatch = url.pathname.match(/^\/api\/jobs\/([^/]+)\/status$/);
        if (statusMatch && request.method === "PATCH") {
          return setJobStatus(decodeURIComponent(statusMatch[1]), request, env);
        }

        return json({ error: "Not found" }, 404);
      } catch (error) {
        console.error(error);
        return json({ error: errorMessage(error) }, 500);
      }
    }

    return env.ASSETS.fetch(request);
  },

  async scheduled(_controller: ScheduledController, env: Env, ctx: ExecutionContext): Promise<void> {
    ctx.waitUntil(
      runScan(env, "scheduled").then(async response => {
        if (!response.ok) console.error("Scheduled Scout scan failed:", await response.text());
      }),
    );
  },
};

function authorized(request: Request, env: Env): boolean {
  if (!env.SCOUT_TOKEN) return false;
  const bearer = request.headers.get("authorization")?.replace(/^Bearer\s+/i, "");
  const alternate = request.headers.get("x-scout-token");
  return bearer === env.SCOUT_TOKEN || alternate === env.SCOUT_TOKEN;
}

async function getJobs(env: Env): Promise<Response> {
  const result = await env.DB.prepare(`
    SELECT
      id, title, company, location,
      remote_status AS remoteStatus,
      salary_min AS salaryMin,
      salary_max AS salaryMax,
      source,
      source_url AS sourceUrl,
      description,
      skills_json AS skillsJson,
      score,
      fit_summary AS fitSummary,
      status,
      discovered_at AS discoveredAt,
      updated_at AS updatedAt
    FROM jobs
    ORDER BY
      CASE status WHEN 'NEW' THEN 0 WHEN 'SAVED' THEN 1 WHEN 'APPROVED' THEN 2 ELSE 3 END,
      score DESC,
      discovered_at DESC
    LIMIT 250
  `).all<Record<string, unknown>>();

  const jobs = result.results.map(row => ({
    ...row,
    skills: safeJsonArray(row.skillsJson),
    skillsJson: undefined,
  }));
  return json({ jobs });
}

async function getSettings(env: Env): Promise<Response> {
  const rows = await env.DB.prepare("SELECT key, value FROM settings").all<{ key: string; value: string }>();
  const settings = Object.fromEntries(rows.results.map(row => [row.key, row.value]));
  return json({
    searchBrief: settings.search_brief ?? "",
    resultCount: Number(settings.result_count ?? "15"),
  });
}

async function putSettings(request: Request, env: Env): Promise<Response> {
  const body = await request.json<{ searchBrief?: string; resultCount?: number }>();
  const searchBrief = (body.searchBrief ?? "").trim();
  const resultCount = Math.max(3, Math.min(30, Number(body.resultCount ?? 15)));
  const now = new Date().toISOString();

  await env.DB.batch([
    env.DB.prepare(`
      INSERT INTO settings(key, value, updated_at) VALUES('search_brief', ?, ?)
      ON CONFLICT(key) DO UPDATE SET value = excluded.value, updated_at = excluded.updated_at
    `).bind(searchBrief, now),
    env.DB.prepare(`
      INSERT INTO settings(key, value, updated_at) VALUES('result_count', ?, ?)
      ON CONFLICT(key) DO UPDATE SET value = excluded.value, updated_at = excluded.updated_at
    `).bind(String(resultCount), now),
  ]);

  return json({ ok: true, searchBrief, resultCount });
}

async function getScanRuns(env: Env): Promise<Response> {
  const result = await env.DB.prepare(`
    SELECT id, started_at AS startedAt, completed_at AS completedAt,
           status, jobs_found AS jobsFound, error
    FROM scan_runs ORDER BY id DESC LIMIT 20
  `).all();
  return json({ runs: result.results });
}

async function setJobStatus(id: string, request: Request, env: Env): Promise<Response> {
  const body = await request.json<{ status?: string }>();
  const status = String(body.status ?? "").toUpperCase();
  if (!["NEW", "SAVED", "APPROVED", "REJECTED"].includes(status)) {
    return json({ error: "Invalid status" }, 400);
  }

  const result = await env.DB.prepare("UPDATE jobs SET status = ?, updated_at = ? WHERE id = ?")
    .bind(status, new Date().toISOString(), id)
    .run();
  if (!result.meta.changes) return json({ error: "Job not found" }, 404);
  return json({ ok: true, id, status });
}

async function runScan(env: Env, trigger: "manual" | "scheduled"): Promise<Response> {
  const startedAt = new Date().toISOString();
  const run = await env.DB.prepare(
    "INSERT INTO scan_runs(started_at, status) VALUES(?, 'RUNNING') RETURNING id",
  ).bind(startedAt).first<{ id: number }>();
  const runId = run?.id;

  try {
    const settings = await env.DB.prepare("SELECT key, value FROM settings").all<{ key: string; value: string }>();
    const map = Object.fromEntries(settings.results.map(row => [row.key, row.value]));
    const searchBrief = (map.search_brief ?? "").trim();
    const resultCount = Math.max(3, Math.min(30, Number(map.result_count ?? 15)));

    if (!searchBrief) {
      throw new Error("Scout needs a search brief before it can scan. Open Settings in the web portal and save one.");
    }
    if (!env.OPENAI_API_KEY) throw new Error("OPENAI_API_KEY secret is not configured.");

    const jobs = await discoverJobs(env, searchBrief, resultCount);
    const now = new Date().toISOString();
    const statements: D1PreparedStatement[] = [];

    for (const job of jobs) {
      const id = await stableJobId(job.source_url);
      statements.push(
        env.DB.prepare(`
          INSERT INTO jobs(
            id, title, company, location, remote_status, salary_min, salary_max,
            source, source_url, description, skills_json, score, fit_summary,
            status, discovered_at, updated_at
          ) VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'NEW', ?, ?)
          ON CONFLICT(id) DO UPDATE SET
            title = excluded.title,
            company = excluded.company,
            location = excluded.location,
            remote_status = excluded.remote_status,
            salary_min = excluded.salary_min,
            salary_max = excluded.salary_max,
            source = excluded.source,
            source_url = excluded.source_url,
            description = excluded.description,
            skills_json = excluded.skills_json,
            score = excluded.score,
            fit_summary = excluded.fit_summary,
            updated_at = excluded.updated_at
        `).bind(
          id,
          job.title,
          job.company,
          job.location,
          job.remote_status,
          job.salary_min,
          job.salary_max,
          job.source,
          job.source_url,
          job.description,
          JSON.stringify(job.skills),
          Math.max(0, Math.min(100, Math.round(job.score))),
          job.fit_summary,
          now,
          now,
        ),
      );
    }

    if (statements.length) await env.DB.batch(statements);
    if (runId != null) {
      await env.DB.prepare(`
        UPDATE scan_runs SET completed_at = ?, status = 'COMPLETED', jobs_found = ? WHERE id = ?
      `).bind(new Date().toISOString(), jobs.length, runId).run();
    }

    return json({ ok: true, trigger, jobsFound: jobs.length });
  } catch (error) {
    const message = errorMessage(error);
    if (runId != null) {
      await env.DB.prepare(`
        UPDATE scan_runs SET completed_at = ?, status = 'FAILED', error = ? WHERE id = ?
      `).bind(new Date().toISOString(), message.slice(0, 1500), runId).run();
    }
    return json({ error: message }, 500);
  }
}

async function discoverJobs(env: Env, searchBrief: string, resultCount: number): Promise<ScoutJob[]> {
  const schema = {
    type: "object",
    additionalProperties: false,
    required: ["jobs"],
    properties: {
      jobs: {
        type: "array",
        maxItems: resultCount,
        items: {
          type: "object",
          additionalProperties: false,
          required: [
            "title", "company", "location", "remote_status", "salary_min", "salary_max",
            "source", "source_url", "description", "skills", "score", "fit_summary",
          ],
          properties: {
            title: { type: "string" },
            company: { type: "string" },
            location: { type: "string" },
            remote_status: { type: "string" },
            salary_min: { type: ["integer", "null"] },
            salary_max: { type: ["integer", "null"] },
            source: { type: "string" },
            source_url: { type: "string" },
            description: { type: "string" },
            skills: { type: "array", items: { type: "string" } },
            score: { type: "integer", minimum: 0, maximum: 100 },
            fit_summary: { type: "string" },
          },
        },
      },
    },
  };

  const prompt = `You are PaidIn Scout, a job-discovery agent. Search the live web for currently actionable job listings that match the user's search brief below.\n\nSEARCH BRIEF:\n${searchBrief}\n\nReturn up to ${resultCount} strong, non-duplicate listings. Prefer direct employer/ATS application pages when available. Use only concrete openings you can support from the live search. Do not invent salary, location, remote status, requirements, or URLs: use null for unknown salary values and the literal string "Unknown" for unknown text fields. Score each role from 0-100 against the brief, and make fit_summary a concise explanation of the match. description should be a compact role summary, not copied posting text. source should name the employer/ATS/job-board source associated with source_url.`;

  const response = await fetch("https://api.openai.com/v1/responses", {
    method: "POST",
    headers: {
      authorization: `Bearer ${env.OPENAI_API_KEY}`,
      "content-type": "application/json",
    },
    body: JSON.stringify({
      model: env.SCOUT_MODEL || "gpt-5.6-luna",
      tools: [{ type: "web_search" }],
      input: prompt,
      text: {
        format: {
          type: "json_schema",
          name: "paidin_job_feed",
          strict: true,
          schema,
        },
      },
    }),
  });

  if (!response.ok) {
    const body = await response.text();
    throw new Error(`OpenAI Responses API ${response.status}: ${body.slice(0, 1200)}`);
  }

  const payload = await response.json<any>();
  const outputText = extractOutputText(payload);
  if (!outputText) throw new Error("OpenAI returned no structured job feed.");
  const parsed = JSON.parse(outputText) as { jobs?: ScoutJob[] };
  return (parsed.jobs ?? []).filter(job => /^https?:\/\//i.test(job.source_url));
}

function extractOutputText(payload: any): string | null {
  for (const item of payload?.output ?? []) {
    for (const content of item?.content ?? []) {
      if (content?.type === "output_text" && typeof content.text === "string") return content.text;
    }
  }
  return typeof payload?.output_text === "string" ? payload.output_text : null;
}

async function stableJobId(url: string): Promise<string> {
  const bytes = new TextEncoder().encode(url.trim().toLowerCase());
  const digest = await crypto.subtle.digest("SHA-256", bytes);
  return `web-${Array.from(new Uint8Array(digest)).slice(0, 12).map(byte => byte.toString(16).padStart(2, "0")).join("")}`;
}

function safeJsonArray(value: unknown): string[] {
  if (typeof value !== "string") return [];
  try {
    const parsed = JSON.parse(value);
    return Array.isArray(parsed) ? parsed.map(String) : [];
  } catch {
    return [];
  }
}

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: JSON_HEADERS });
}

function errorMessage(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}
