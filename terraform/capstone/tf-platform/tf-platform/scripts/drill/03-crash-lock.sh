#!/usr/bin/env bash
# Simulates a runner dying mid-apply: SIGKILL leaves the state lock behind.
# With S3 native locking the lock is an object: <key>.tflock next to the state file.
source "$(dirname "$0")/common.sh"
tf_init

say "Start a slow-ish apply in the background (adds a tag change so there is work to do)"
( tf apply -auto-approve -var-file=dev.tfvars -var 'flow_log_retention_days=14' -no-color >/tmp/drill-apply.log 2>&1 ) &
BG=$!
sleep 6

say "kill -9 the terraform process: no chance to release the lock"
pkill -9 -f "terraform.*apply" || true
wait "$BG" 2>/dev/null || true

say "Lock object now present in S3:"
aws s3 ls "s3://$TF_STATE_BUCKET_DEV/platform/" || true

say "Next terraform command is blocked:"
set +e; tf plan -var-file=dev.tfvars -lock-timeout=5s -no-color 2>&1 | sed -n '1,25p'; set -e

cat <<MSG

You should see 'Error acquiring the state lock' with a Lock ID.
Recover with docs/DRILL.md -> Scenario 3 (verify nobody is running, then force-unlock).
MSG
