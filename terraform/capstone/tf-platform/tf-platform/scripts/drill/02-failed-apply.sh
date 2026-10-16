#!/usr/bin/env bash
# Forces a mid-apply failure by asking for an instance type that does not exist,
# AND adding a tag change that WILL succeed in the same apply. Result: a PARTIALLY applied run.
source "$(dirname "$0")/common.sh"
tf_init

say "Baseline: should be clean"
set +e; tf plan -var-file=dev.tfvars -detailed-exitcode -no-color >/dev/null; echo "baseline exit: $? (0 = clean)"; set -e

say "Apply with a bad instance type (will fail at the EC2 API, after other changes may land)"
set +e
tf apply -auto-approve -var-file=dev.tfvars -var 'instance_type=t3.doesnotexist' -no-color
echo "apply exit code: $?"
set -e

say "What does state think now?"
tf state list | head -40
echo
echo "Check: terraform state show module.compute.aws_instance.this | grep instance_type"
tf state show module.compute.aws_instance.this | grep -E 'instance_type|id ' || true

cat <<MSG

The apply failed. Terraform does NOT roll back: whatever succeeded before the error is real and recorded in state.
Recover with docs/DRILL.md -> Scenario 2 (fix the cause, re-run apply; it resumes from state).
MSG
