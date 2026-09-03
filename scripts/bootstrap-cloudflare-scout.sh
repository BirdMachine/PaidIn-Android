#!/usr/bin/env bash
set -Eeuo pipefail

# PaidIn Scout one-shot Cloudflare bootstrap for Linux (Mallard/Kubuntu).
# Run from the PaidIn-Android repository checkout on branch scout-feed.
# Secrets are prompted locally and never written into the repository.

WORKER_NAME="paidin-scout"
DB_NAME="paidin-scout"
MIN_NODE_MAJOR=20
MCP_NAMES=(cloudflare cloudflare-docs cloudflare-bindings cloudflare-builds cloudflare-observability)
MCP_URLS=(
  "https://mcp.cloudflare.com/mcp"
  "https://docs.mcp.cloudflare.com/mcp"
  "https://bindings.mcp.cloudflare.com/mcp"
  "https://builds.mcp.cloudflare.com/mcp"
  "https://observability.mcp.cloudflare.com/mcp"
)

say() { printf '\n\033[1;36m==> %s\033[0m\n' "$*"; }
warn() { printf '\n\033[1;33m!! %s\033[0m\n' "$*" >&2; }
die() { printf '\n\033[1;31mERROR: %s\033[0m\n' "$*" >&2; exit 1; }

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd -- "$SCRIPT_DIR/.." && pwd)"
SCOUT_DIR="$REPO_ROOT/scout"
VSCODE_MCP="$REPO_ROOT/.vscode/mcp.json"
CLIENT_DIR="$HOME/.config/paidin-scout"
CLIENT_ENV="$CLIENT_DIR/client.env"

[[ -f "$SCOUT_DIR/wrangler.jsonc" ]] || die "Could not find scout/wrangler.jsonc. Run this script from the PaidIn-Android scout-feed checkout."
[[ -f "$SCOUT_DIR/schema.sql" ]] || die "Could not find scout/schema.sql."

ensure_apt_tools() {
  local packages=()
  command -v git >/dev/null 2>&1 || packages+=(git)
  command -v curl >/dev/null 2>&1 || packages+=(curl)
  command -v openssl >/dev/null 2>&1 || packages+=(openssl)
  command -v secret-tool >/dev/null 2>&1 || packages+=(libsecret-tools)
  command -v node >/dev/null 2>&1 || packages+=(nodejs)
  command -v npm >/dev/null 2>&1 || packages+=(npm)

  if ((${#packages[@]})); then
    command -v apt-get >/dev/null 2>&1 || die "Missing tools (${packages[*]}) and apt-get is unavailable."
    say "Installing required Mallard packages: ${packages[*]}"
    sudo apt-get update
    sudo apt-get install -y "${packages[@]}"
  fi
}

ensure_node() {
  local major
  major="$(node -p 'Number(process.versions.node.split(".")[0])' 2>/dev/null || echo 0)"
  (( major >= MIN_NODE_MAJOR )) || die "Node.js ${MIN_NODE_MAJOR}+ is required by current Wrangler/Codex; found $(node --version 2>/dev/null || echo none). Please update Node, then rerun."
}

ensure_codex() {
  if command -v codex >/dev/null 2>&1; then
    return
  fi
  say "Installing OpenAI Codex CLI into ~/.local (no sudo)"
  mkdir -p "$HOME/.local/bin"
  npm install -g --prefix "$HOME/.local" @openai/codex
  export PATH="$HOME/.local/bin:$PATH"
  if ! grep -Fqs 'export PATH="$HOME/.local/bin:$PATH"' "$HOME/.profile" 2>/dev/null; then
    printf '\n# User-local CLI tools (PaidIn bootstrap)\nexport PATH="$HOME/.local/bin:$PATH"\n' >> "$HOME/.profile"
  fi
  command -v codex >/dev/null 2>&1 || die "Codex installed but is not on PATH. Open a new shell and rerun."
}

install_cloudflare_agent_bits() {
  say "Installing Cloudflare Skills globally"
  npx -y skills add cloudflare/skills --skill '*' --yes --global

  say "Registering Cloudflare MCP servers with Codex"
  local i name url list
  list="$(codex mcp list 2>/dev/null || true)"
  for i in "${!MCP_NAMES[@]}"; do
    name="${MCP_NAMES[$i]}"
    url="${MCP_URLS[$i]}"
    if grep -Eq "(^|[[:space:]])${name}([[:space:]]|$)" <<<"$list"; then
      printf '    ✓ %s already registered\n' "$name"
    else
      codex mcp add "$name" --url "$url"
    fi
  done

  say "Writing/merging VS Code MCP config"
  mkdir -p "$(dirname "$VSCODE_MCP")"
  VSCODE_MCP="$VSCODE_MCP" node <<'NODE'
const fs = require('fs');
const path = process.env.VSCODE_MCP;
const servers = {
  cloudflare: { type: 'http', url: 'https://mcp.cloudflare.com/mcp' },
  'cloudflare-docs': { type: 'http', url: 'https://docs.mcp.cloudflare.com/mcp' },
  'cloudflare-bindings': { type: 'http', url: 'https://bindings.mcp.cloudflare.com/mcp' },
  'cloudflare-builds': { type: 'http', url: 'https://builds.mcp.cloudflare.com/mcp' },
  'cloudflare-observability': { type: 'http', url: 'https://observability.mcp.cloudflare.com/mcp' },
};
let doc = { servers: {} };
if (fs.existsSync(path)) {
  const raw = fs.readFileSync(path, 'utf8').trim();
  if (raw) {
    try { doc = JSON.parse(raw); }
    catch (err) {
      console.error(`Existing ${path} is not valid JSON; refusing to overwrite it.`);
      process.exit(2);
    }
  }
}
doc.servers = { ...(doc.servers || {}), ...servers };
fs.writeFileSync(path, JSON.stringify(doc, null, 2) + '\n');
NODE
}

cloudflare_login() {
  cd "$SCOUT_DIR"
  say "Installing Scout dependencies"
  npm install

  if npx wrangler whoami --json >/dev/null 2>&1; then
    say "Wrangler is already authenticated"
  else
    say "Authorizing Wrangler with Cloudflare (browser OAuth; encrypted keyring storage)"
    npx wrangler login --use-keyring
  fi
  npx wrangler whoami

  say "Authorizing the Cloudflare API MCP for Codex"
  if ! codex mcp login cloudflare; then
    warn "Codex MCP OAuth did not complete. Wrangler deployment can still proceed; rerun 'codex mcp login cloudflare' later if you want Codex account tools."
  fi
}

get_db_id() {
  local json
  json="$(npx wrangler d1 list --json)"
  DB_LIST_JSON="$json" DB_NAME="$DB_NAME" node <<'NODE'
const rows = JSON.parse(process.env.DB_LIST_JSON || '[]');
const db = rows.find((x) => x.name === process.env.DB_NAME);
if (db) process.stdout.write(db.uuid || db.id || db.database_id || '');
NODE
}

provision_d1() {
  cd "$SCOUT_DIR"
  local db_id
  db_id="$(get_db_id)"
  if [[ -z "$db_id" ]]; then
    say "Creating Cloudflare D1 database: $DB_NAME"
    npx wrangler d1 create "$DB_NAME"
    db_id="$(get_db_id)"
  else
    say "Reusing existing D1 database: $DB_NAME"
  fi
  [[ -n "$db_id" ]] || die "D1 exists/creation ran, but its database ID could not be resolved."

  say "Binding D1 database $db_id into scout/wrangler.jsonc"
  CONFIG_PATH="$SCOUT_DIR/wrangler.jsonc" DB_ID="$db_id" DB_NAME="$DB_NAME" node <<'NODE'
const fs = require('fs');
const p = process.env.CONFIG_PATH;
const cfg = JSON.parse(fs.readFileSync(p, 'utf8'));
cfg.d1_databases ||= [];
let db = cfg.d1_databases.find((x) => x.binding === 'DB');
if (!db) {
  db = { binding: 'DB', database_name: process.env.DB_NAME };
  cfg.d1_databases.push(db);
}
db.database_name = process.env.DB_NAME;
db.database_id = process.env.DB_ID;
fs.writeFileSync(p, JSON.stringify(cfg, null, 2) + '\n');
NODE

  say "Initializing the remote D1 schema"
  npx wrangler d1 execute "$DB_NAME" --remote --file=./schema.sql --yes
}

collect_secrets() {
  if [[ -z "${OPENAI_API_KEY:-}" ]]; then
    printf '\nOpenAI API key (input hidden; stored only as a Cloudflare Worker secret): '
    IFS= read -r -s OPENAI_API_KEY
    printf '\n'
  fi
  [[ -n "${OPENAI_API_KEY:-}" ]] || die "An OpenAI API key is required for PaidIn Scout live web search."

  mkdir -p "$CLIENT_DIR"
  chmod 700 "$CLIENT_DIR"

  if [[ -z "${SCOUT_TOKEN:-}" && -f "$CLIENT_ENV" ]]; then
    SCOUT_TOKEN="$(sed -n 's/^SCOUT_TOKEN=//p' "$CLIENT_ENV" | head -n1)"
  fi
  if [[ -z "${SCOUT_TOKEN:-}" ]]; then
    SCOUT_TOKEN="$(openssl rand -hex 32)"
  fi
}

deploy_scout() {
  cd "$SCOUT_DIR"
  say "Typechecking PaidIn Scout"
  npm run typecheck

  say "Initial Worker deployment"
  local first_log final_log
  first_log="$(mktemp)"
  final_log="$(mktemp)"
  NO_COLOR=1 npx wrangler deploy 2>&1 | tee "$first_log"

  say "Uploading Worker secrets"
  printf '%s' "$OPENAI_API_KEY" | npx wrangler secret put OPENAI_API_KEY
  printf '%s' "$SCOUT_TOKEN" | npx wrangler secret put SCOUT_TOKEN
  unset OPENAI_API_KEY

  say "Final deployment"
  NO_COLOR=1 npx wrangler deploy 2>&1 | tee "$final_log"

  WORKER_URL="$(grep -Eo 'https://[A-Za-z0-9._-]+\.workers\.dev' "$final_log" | tail -n1 || true)"
  if [[ -z "$WORKER_URL" ]]; then
    WORKER_URL="$(grep -Eo 'https://[A-Za-z0-9._-]+\.workers\.dev' "$first_log" | tail -n1 || true)"
  fi

  {
    printf 'SCOUT_TOKEN=%s\n' "$SCOUT_TOKEN"
    [[ -n "$WORKER_URL" ]] && printf 'SCOUT_URL=%s\n' "$WORKER_URL"
  } > "$CLIENT_ENV"
  chmod 600 "$CLIENT_ENV"
  rm -f "$first_log" "$final_log"
}

configure_search_and_smoke_test() {
  [[ -n "${WORKER_URL:-}" ]] || return 0

  say "Smoke-testing the deployed Worker"
  curl -fsS "$WORKER_URL/api/health" >/dev/null
  printf '    ✓ %s/api/health\n' "$WORKER_URL"

  printf '\nPrivate job-search brief (one line; press Enter to configure it later in the web portal):\n> '
  IFS= read -r SEARCH_BRIEF
  if [[ -n "$SEARCH_BRIEF" ]]; then
    payload="$(SEARCH_BRIEF="$SEARCH_BRIEF" node <<'NODE'
process.stdout.write(JSON.stringify({ searchBrief: process.env.SEARCH_BRIEF, resultCount: 12 }));
NODE
)"
    curl -fsS -X PUT \
      -H "Authorization: Bearer $SCOUT_TOKEN" \
      -H 'Content-Type: application/json' \
      --data "$payload" \
      "$WORKER_URL/api/settings" >/dev/null
    printf '    ✓ Search brief saved privately in D1\n'

    read -r -p "Run the first live Scout search now? [Y/n] " answer
    if [[ ! "$answer" =~ ^[Nn]$ ]]; then
      curl -fsS -X POST \
        -H "Authorization: Bearer $SCOUT_TOKEN" \
        "$WORKER_URL/api/scan" >/dev/null
      printf '    ✓ First Scout search completed\n'
    fi
  fi
}

main() {
  say "PaidIn Scout × Cloudflare bootstrap"
  ensure_apt_tools
  ensure_node
  ensure_codex
  install_cloudflare_agent_bits
  cloudflare_login
  provision_d1
  collect_secrets
  deploy_scout
  configure_search_and_smoke_test

  printf '\n\033[1;32m┌─ PaidIn Scout Cloudflare Setup Complete ──────────────┐\033[0m\n'
  printf '  ✓ Cloudflare Skills installed\n'
  printf '  ✓ Codex MCPs registered\n'
  printf '  ✓ VS Code MCP config: %s\n' "$VSCODE_MCP"
  printf '  ✓ D1: %s\n' "$DB_NAME"
  printf '  ✓ Worker: %s\n' "${WORKER_URL:-deployed; see Wrangler output above}"
  printf '  ✓ Client credentials: %s (mode 600)\n' "$CLIENT_ENV"
  printf '\033[1;32m└───────────────────────────────────────────────────────┘\033[0m\n'
  printf '\nScout token (copy into PaidIn Android/web settings):\n%s\n' "$SCOUT_TOKEN"
  if [[ -n "${WORKER_URL:-}" ]]; then
    printf '\nScout web portal:\n%s\n' "$WORKER_URL"
  fi
  printf '\nFor VS Code: reopen the repo, open .vscode/mcp.json, and click Start on a Cloudflare server when VS Code offers it.\n'
}

main "$@"
