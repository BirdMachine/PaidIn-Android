#!/usr/bin/env bash
set -Eeuo pipefail

# Resume PaidIn Scout after the initial bootstrap reached Worker deployment.
# Stores Cloudflare API auth in a mode-600 env file and sources it from ~/.zshrc.
# OpenAI/Scout secrets are never written into the repository.

WORKER_NAME="paidin-scout"
CLIENT_DIR="$HOME/.config/paidin-scout"
CLIENT_ENV="$CLIENT_DIR/client.env"
CF_ENV="$CLIENT_DIR/cloudflare.env"
ZSHRC="$HOME/.zshrc"

say() { printf '\n\033[1;36m==> %s\033[0m\n' "$*"; }
warn() { printf '\n\033[1;33m!! %s\033[0m\n' "$*" >&2; }
die() { printf '\n\033[1;31mERROR: %s\033[0m\n' "$*" >&2; exit 1; }

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd -- "$SCRIPT_DIR/.." && pwd)"
SCOUT_DIR="$REPO_ROOT/scout"

[[ -f "$SCOUT_DIR/wrangler.jsonc" ]] || die "Could not find scout/wrangler.jsonc. Pull the scout-feed branch and run this script from that checkout."
[[ -f "$SCOUT_DIR/schema.sql" ]] || die "Could not find scout/schema.sql."
command -v node >/dev/null 2>&1 || die "Node.js is required."
command -v npm >/dev/null 2>&1 || die "npm is required."
command -v curl >/dev/null 2>&1 || die "curl is required."
command -v openssl >/dev/null 2>&1 || die "openssl is required."

mkdir -p "$CLIENT_DIR"
chmod 700 "$CLIENT_DIR"

load_existing_cloudflare_env() {
  if [[ -r "$CF_ENV" ]]; then
    # shellcheck disable=SC1090
    source "$CF_ENV"
  fi
}

prompt_cloudflare_token() {
  if [[ -z "${CLOUDFLARE_API_TOKEN:-}" ]]; then
    printf '\nCloudflare API token (input hidden): '
    IFS= read -r -s CLOUDFLARE_API_TOKEN
    printf '\n'
  fi
  [[ -n "${CLOUDFLARE_API_TOKEN:-}" ]] || die "Cloudflare API token is required."
  export CLOUDFLARE_API_TOKEN
}

verify_cloudflare_token() {
  say "Verifying Cloudflare API token"
  local verify_json
  verify_json="$(curl -fsS \
    -H "Authorization: Bearer $CLOUDFLARE_API_TOKEN" \
    https://api.cloudflare.com/client/v4/user/tokens/verify)" \
    || die "Cloudflare rejected the API token. Create/use a valid token and rerun."

  VERIFY_JSON="$verify_json" node <<'NODE'
const j = JSON.parse(process.env.VERIFY_JSON || '{}');
if (!j.success || j.result?.status !== 'active') process.exit(2);
NODE
}

resolve_account_id() {
  if [[ -n "${CLOUDFLARE_ACCOUNT_ID:-}" ]]; then
    export CLOUDFLARE_ACCOUNT_ID
    return
  fi

  say "Resolving your Cloudflare account ID"
  local accounts_json ids count
  accounts_json="$(curl -fsS \
    -H "Authorization: Bearer $CLOUDFLARE_API_TOKEN" \
    'https://api.cloudflare.com/client/v4/accounts?page=1&per_page=50' 2>/dev/null || true)"

  ids="$(ACCOUNTS_JSON="$accounts_json" node <<'NODE'
try {
  const j = JSON.parse(process.env.ACCOUNTS_JSON || '{}');
  if (!j.success || !Array.isArray(j.result)) process.exit(0);
  for (const a of j.result) console.log(`${a.id}\t${a.name || '(unnamed account)'}`);
} catch {}
NODE
)"

  count="$(printf '%s\n' "$ids" | sed '/^$/d' | wc -l | tr -d ' ')"
  if [[ "$count" == "1" ]]; then
    CLOUDFLARE_ACCOUNT_ID="$(printf '%s\n' "$ids" | cut -f1)"
    printf '    ✓ %s\n' "$(printf '%s\n' "$ids" | cut -f2-)"
  elif [[ "$count" =~ ^[0-9]+$ ]] && (( count > 1 )); then
    printf '\nCloudflare accounts visible to this token:\n%s\n' "$ids"
    printf '\nAccount ID to use for PaidIn Scout: '
    IFS= read -r CLOUDFLARE_ACCOUNT_ID
  else
    warn "The token could not list accounts automatically (this can simply be a token-scope limitation)."
    printf 'Cloudflare Account ID: '
    IFS= read -r CLOUDFLARE_ACCOUNT_ID
  fi

  [[ -n "${CLOUDFLARE_ACCOUNT_ID:-}" ]] || die "Cloudflare Account ID is required."
  export CLOUDFLARE_ACCOUNT_ID
}

persist_cloudflare_env() {
  say "Persisting Cloudflare auth for zsh (secret file mode 600)"

  # Tokens/IDs are Cloudflare-generated ASCII identifiers; write them only to the
  # protected env file, not literally into ~/.zshrc.
  umask 077
  {
    printf '# PaidIn Scout / Wrangler authentication. Keep this file private.\n'
    printf 'export CLOUDFLARE_API_TOKEN=%q\n' "$CLOUDFLARE_API_TOKEN"
    printf 'export CLOUDFLARE_ACCOUNT_ID=%q\n' "$CLOUDFLARE_ACCOUNT_ID"
  } > "$CF_ENV"
  chmod 600 "$CF_ENV"

  touch "$ZSHRC"
  local source_line='[[ -r "$HOME/.config/paidin-scout/cloudflare.env" ]] && source "$HOME/.config/paidin-scout/cloudflare.env"'
  if ! grep -Fqs "$source_line" "$ZSHRC"; then
    {
      printf '\n# PaidIn Scout / Cloudflare Wrangler credentials\n'
      printf '%s\n' "$source_line"
    } >> "$ZSHRC"
  fi

  printf '    ✓ %s\n' "$CF_ENV"
  printf '    ✓ sourced by %s\n' "$ZSHRC"
}

ensure_scout_ready() {
  cd "$SCOUT_DIR"
  say "Installing/checking Scout dependencies"
  npm install

  if grep -q 'REPLACE_WITH_D1_DATABASE_ID' wrangler.jsonc; then
    die "D1 binding is still a placeholder, so the first bootstrap did not finish provisioning D1. Rerun scripts/bootstrap-cloudflare-scout.sh with these Cloudflare env vars loaded."
  fi

  say "Checking Wrangler authentication"
  npx wrangler whoami
}

collect_worker_secrets() {
  if [[ -z "${OPENAI_API_KEY:-}" ]]; then
    printf '\nOpenAI API key (input hidden; sent directly to Cloudflare Worker Secrets): '
    IFS= read -r -s OPENAI_API_KEY
    printf '\n'
  fi
  [[ -n "${OPENAI_API_KEY:-}" ]] || die "An OpenAI API key is required for live Scout searches."

  if [[ -z "${SCOUT_TOKEN:-}" && -r "$CLIENT_ENV" ]]; then
    SCOUT_TOKEN="$(sed -n 's/^SCOUT_TOKEN=//p' "$CLIENT_ENV" | head -n1)"
  fi
  if [[ -z "${SCOUT_TOKEN:-}" ]]; then
    SCOUT_TOKEN="$(openssl rand -hex 32)"
  fi
  export SCOUT_TOKEN
}

worker_url_fallback() {
  local subdomain_json subdomain
  subdomain_json="$(curl -fsS \
    -H "Authorization: Bearer $CLOUDFLARE_API_TOKEN" \
    "https://api.cloudflare.com/client/v4/accounts/$CLOUDFLARE_ACCOUNT_ID/workers/subdomain" 2>/dev/null || true)"
  subdomain="$(SUBDOMAIN_JSON="$subdomain_json" node <<'NODE'
try {
  const j = JSON.parse(process.env.SUBDOMAIN_JSON || '{}');
  if (j.success && j.result?.subdomain) process.stdout.write(j.result.subdomain);
} catch {}
NODE
)"
  [[ -n "$subdomain" ]] && printf 'https://%s.%s.workers.dev' "$WORKER_NAME" "$subdomain"
}

deploy_and_finish() {
  cd "$SCOUT_DIR"

  say "Typechecking PaidIn Scout"
  npm run typecheck

  say "Initial Worker deployment (API-token authenticated)"
  local first_log final_log
  first_log="$(mktemp)"
  final_log="$(mktemp)"
  trap 'rm -f "${first_log:-}" "${final_log:-}"' RETURN
  NO_COLOR=1 npx wrangler deploy 2>&1 | tee "$first_log"

  say "Uploading Worker secrets"
  printf '%s' "$OPENAI_API_KEY" | npx wrangler secret put OPENAI_API_KEY
  printf '%s' "$SCOUT_TOKEN" | npx wrangler secret put SCOUT_TOKEN
  unset OPENAI_API_KEY

  say "Final deployment"
  NO_COLOR=1 npx wrangler deploy 2>&1 | tee "$final_log"

  WORKER_URL="$(grep -Eo 'https://[A-Za-z0-9._-]+\.workers\.dev' "$final_log" | tail -n1 || true)"
  [[ -n "$WORKER_URL" ]] || WORKER_URL="$(grep -Eo 'https://[A-Za-z0-9._-]+\.workers\.dev' "$first_log" | tail -n1 || true)"
  [[ -n "$WORKER_URL" ]] || WORKER_URL="$(worker_url_fallback || true)"

  {
    printf 'SCOUT_TOKEN=%s\n' "$SCOUT_TOKEN"
    [[ -n "${WORKER_URL:-}" ]] && printf 'SCOUT_URL=%s\n' "$WORKER_URL"
  } > "$CLIENT_ENV"
  chmod 600 "$CLIENT_ENV"

  rm -f "$first_log" "$final_log"
  trap - RETURN
}

configure_search_and_smoke_test() {
  [[ -n "${WORKER_URL:-}" ]] || {
    warn "Worker deployed, but I could not determine its workers.dev URL automatically. Check the Wrangler deployment output above."
    return 0
  }

  say "Smoke-testing the deployed Worker"
  curl -fsS "$WORKER_URL/api/health" >/dev/null
  printf '    ✓ %s/api/health\n' "$WORKER_URL"

  printf '\nPrivate job-search brief (one line; press Enter to configure later in the portal):\n> '
  IFS= read -r SEARCH_BRIEF
  if [[ -n "$SEARCH_BRIEF" ]]; then
    local payload answer
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
  say "PaidIn Scout × Cloudflare resume"
  load_existing_cloudflare_env
  prompt_cloudflare_token
  verify_cloudflare_token
  resolve_account_id
  persist_cloudflare_env
  ensure_scout_ready
  collect_worker_secrets
  deploy_and_finish
  configure_search_and_smoke_test

  printf '\n\033[1;32m┌─ PaidIn Scout Cloudflare Setup Complete ──────────────┐\033[0m\n'
  printf '  ✓ Cloudflare API auth: %s (mode 600)\n' "$CF_ENV"
  printf '  ✓ ~/.zshrc sources Cloudflare auth\n'
  printf '  ✓ Worker: %s\n' "${WORKER_URL:-deployed; see Wrangler output above}"
  printf '  ✓ Client credentials: %s (mode 600)\n' "$CLIENT_ENV"
  printf '\033[1;32m└───────────────────────────────────────────────────────┘\033[0m\n'
  printf '\nScout token (copy into PaidIn Android/web settings):\n%s\n' "$SCOUT_TOKEN"
  if [[ -n "${WORKER_URL:-}" ]]; then
    printf '\nScout web portal:\n%s\n' "$WORKER_URL"
  fi
  printf '\nFuture zsh shells will automatically have CLOUDFLARE_API_TOKEN and CLOUDFLARE_ACCOUNT_ID loaded.\n'
}

main "$@"
