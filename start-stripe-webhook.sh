#!/usr/bin/env bash
set -Eeuo pipefail

# Forward Stripe test-mode webhooks into the local Spring Boot backend.
# This script intentionally does not start Spring Boot; run it in a second
# terminal next to ./start-mock.sh when checkout mocks are disabled.

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
PORT="${PORT:-8080}"
STRIPE_FORWARD_URL="${STRIPE_FORWARD_URL:-http://localhost:${PORT}/v1/webhook/stripe}"
START_STRIPE_WEBHOOK_ENV_FILE_KEYS=" "

trim_env_value() {
  local value="$1"
  value="${value#"${value%%[![:space:]]*}"}"
  value="${value%"${value##*[![:space:]]}"}"
  printf '%s' "${value}"
}

strip_env_quotes() {
  local value="$1"
  if [[ ${#value} -ge 2 ]]; then
    if [[ "${value}" == \"*\" && "${value}" == *\" ]]; then
      value="${value:1:${#value}-2}"
    elif [[ "${value}" == \'*\' && "${value}" == *\' ]]; then
      value="${value:1:${#value}-2}"
    fi
  fi
  printf '%s' "${value}"
}

load_local_env_file() {
  local env_file="$1"
  [[ -f "${env_file}" ]] || return 0

  echo "Loading local env file: ${env_file}"
  local line key value
  while IFS= read -r line || [[ -n "${line}" ]]; do
    line="$(trim_env_value "${line}")"
    [[ -z "${line}" || "${line}" == \#* ]] && continue

    if [[ "${line}" == export[[:space:]]* ]]; then
      line="$(trim_env_value "${line#export}")"
    fi

    [[ "${line}" == *=* ]] || continue
    key="$(trim_env_value "${line%%=*}")"
    value="$(trim_env_value "${line#*=}")"
    value="$(strip_env_quotes "${value}")"

    [[ "${key}" =~ ^[A-Za-z_][A-Za-z0-9_]*$ ]] || continue

    # Explicit shell env wins over .env files; .env.local can override .env.
    if [[ -n "${!key+x}" && "${START_STRIPE_WEBHOOK_ENV_FILE_KEYS}" != *" ${key} "* ]]; then
      continue
    fi

    export "${key}=${value}"
    if [[ "${START_STRIPE_WEBHOOK_ENV_FILE_KEYS}" != *" ${key} "* ]]; then
      START_STRIPE_WEBHOOK_ENV_FILE_KEYS="${START_STRIPE_WEBHOOK_ENV_FILE_KEYS}${key} "
    fi
  done < "${env_file}"
}

load_local_env_file "${SCRIPT_DIR}/.env"
load_local_env_file "${SCRIPT_DIR}/.env.local"

if ! command -v stripe >/dev/null 2>&1; then
  echo "ERROR: stripe CLI is required for local webhook forwarding." >&2
  echo "Install it from https://docs.stripe.com/stripe-cli, then run: stripe login" >&2
  exit 1
fi

cat <<EOF
Forwarding Stripe test webhooks to:
  ${STRIPE_FORWARD_URL}

Keep this process running while completing Stripe Checkout.
The listener uses STRIPE_SECRET_KEY from .env/.env.local when available, so it
listens to the same Stripe test account that the backend uses to create Checkout
Sessions.
If you want strict signature verification, copy the whsec_... value printed by
Stripe CLI into ${SCRIPT_DIR}/.env.local as STRIPE_WEBHOOK_SECRET, then restart
./start-mock.sh. For local development, STRIPE_ALLOW_UNSIGNED_WEBHOOKS=true with
STRIPE_WEBHOOK_SECRET=whsec_xxx lets the backend accept forwarded test events.
EOF

if [[ -n "${STRIPE_SECRET_KEY:-}" && "${STRIPE_SECRET_KEY}" != "sk_test_xxx" ]]; then
  export STRIPE_API_KEY="${STRIPE_SECRET_KEY}"
  unset STRIPE_SECRET_KEY
else
  echo "WARN: STRIPE_SECRET_KEY is not configured; using the default Stripe CLI profile." >&2
fi

exec stripe listen --forward-to "${STRIPE_FORWARD_URL}"
