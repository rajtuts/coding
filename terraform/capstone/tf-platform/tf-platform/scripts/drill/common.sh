#!/usr/bin/env bash
# Shared helpers. ONLY RUN THESE AGAINST DEV.
set -euo pipefail

ENV="${ENV:-dev}"
if [[ "$ENV" != "dev" ]]; then
  echo "Refusing to run drills against '$ENV'. Drills are dev-only." >&2
  exit 1
fi

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
TF_DIR="$ROOT/envs/$ENV"
: "${TF_STATE_BUCKET_DEV:?export TF_STATE_BUCKET_DEV (see: terraform -chdir=bootstrap output state_buckets)}"

tf() { terraform -chdir="$TF_DIR" "$@"; }
say() { printf '\n\033[1;36m== %s\033[0m\n' "$*"; }

tf_init() { tf init -input=false -reconfigure -backend-config="bucket=$TF_STATE_BUCKET_DEV" >/dev/null; }
