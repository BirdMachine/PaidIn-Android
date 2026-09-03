#!/usr/bin/env bash
set -Eeuo pipefail

REPO="BirdMachine/PaidIn-Android"
BRANCH="scout-feed"
KEY_DIR="$HOME/.config/paidin-scout"
KEYSTORE="$KEY_DIR/android-ci-debug.keystore"
ANDROID_KEYSTORE="$HOME/.android/debug.keystore"
ALIAS="androiddebugkey"
STOREPASS="android"
KEYPASS="android"

say() { printf '\n\033[1;36m==> %s\033[0m\n' "$*"; }
die() { printf '\n\033[1;31mERROR: %s\033[0m\n' "$*" >&2; exit 1; }

ensure_tools() {
  command -v keytool >/dev/null 2>&1 || die "keytool is missing. Install/use JDK 17, then rerun."
  command -v base64 >/dev/null 2>&1 || die "base64 is missing."
  if ! command -v gh >/dev/null 2>&1; then
    command -v apt-get >/dev/null 2>&1 || die "GitHub CLI (gh) is missing and apt-get is unavailable."
    say "Installing GitHub CLI"
    sudo apt-get update
    sudo apt-get install -y gh
  fi
}

auth_github() {
  if ! gh auth status -h github.com >/dev/null 2>&1; then
    say "Authorizing GitHub CLI in your browser"
    gh auth login -h github.com -p https -w
  fi
}

make_key() {
  mkdir -p "$KEY_DIR" "$HOME/.android"
  chmod 700 "$KEY_DIR" "$HOME/.android"

  if [[ ! -f "$KEYSTORE" ]]; then
    say "Generating PaidIn's persistent debug signing key"
    keytool -genkeypair \
      -keystore "$KEYSTORE" \
      -storepass "$STOREPASS" \
      -alias "$ALIAS" \
      -keypass "$KEYPASS" \
      -keyalg RSA \
      -keysize 2048 \
      -validity 10000 \
      -dname "CN=PaidIn Android Debug,O=BirdMachine,C=US" \
      -noprompt
    chmod 600 "$KEYSTORE"
  else
    say "Reusing existing PaidIn signing key: $KEYSTORE"
  fi

  # Keep Mallard's local debug builds signed identically to CI.
  cp "$KEYSTORE" "$ANDROID_KEYSTORE"
  chmod 600 "$ANDROID_KEYSTORE"
}

upload_secret() {
  say "Uploading the signing key to GitHub Actions secret storage"
  base64 -w 0 "$KEYSTORE" | gh secret set ANDROID_DEBUG_KEYSTORE_BASE64 --repo "$REPO"
}

trigger_build() {
  say "Triggering a fresh PaidIn Android + Scout build on $BRANCH"
  gh workflow run android.yml --repo "$REPO" --ref "$BRANCH"

  printf '\nStable signing is configured.\n'
  printf 'IMPORTANT: uninstall the currently installed randomly-signed PaidIn APK ONCE, then install the next CI APK.\n'
  printf 'Every subsequent APK built with this secret can update that installation in place.\n'
  printf '\nSigning key backup (keep this file safe):\n  %s\n' "$KEYSTORE"
  printf '\nTo watch the new workflow run:\n  gh run list --repo %s --workflow android.yml --limit 3\n' "$REPO"
}

main() {
  say "PaidIn stable Android signing setup"
  ensure_tools
  auth_github
  make_key
  upload_secret
  trigger_build
}

main "$@"
