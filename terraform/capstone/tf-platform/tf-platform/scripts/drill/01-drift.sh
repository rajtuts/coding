#!/usr/bin/env bash
# Creates THREE kinds of drift out-of-band (bypassing Terraform), dev only:
#   A) security group: a risky rule ADDED by hand (SSH from the world) AND the managed HTTPS egress rule REMOVED
#   B) tags on the data bucket overwritten
#   C) a bucket setting weakened (versioning suspended)
# Then shows how Terraform sees each one.
source "$(dirname "$0")/common.sh"
tf_init

SG_ID="$(tf output -raw instance_id | xargs -I{} aws ec2 describe-instances --instance-ids {} \
  --query 'Reservations[0].Instances[0].SecurityGroups[0].GroupId' --output text)"
BUCKET="$(tf output -raw data_bucket)"

say "A1) 'Quick fix' in the console: open SSH to the world (Terraform will NOT see this: see docs/DRILL.md 'blind spot')"
aws ec2 authorize-security-group-ingress --group-id "$SG_ID" --protocol tcp --port 22 --cidr 0.0.0.0/0

say "A2) Someone deletes the managed HTTPS egress rule (Terraform WILL see this)"
aws ec2 revoke-security-group-egress --group-id "$SG_ID" --protocol tcp --port 443 --cidr 0.0.0.0/0

say "B) Someone retags the bucket by hand"
aws s3api put-bucket-tagging --bucket "$BUCKET" \
  --tagging 'TagSet=[{Key=Owner,Value=somebody-else},{Key=Environment,Value=dev},{Key=Project,Value=acme},{Key=ManagedBy,Value=terraform}]'

say "C) Someone suspends bucket versioning"
aws s3api put-bucket-versioning --bucket "$BUCKET" --versioning-configuration Status=Suspended

say "Detect: refresh-only plan shows what changed in the REAL world vs state"
tf plan -refresh-only -var-file=dev.tfvars -no-color || true

say "Detect: normal plan shows what Terraform would DO about it (exit 2 = drift)"
set +e; tf plan -var-file=dev.tfvars -detailed-exitcode -no-color; echo "exit code: $?"; set -e

cat <<MSG

Next: recover with docs/DRILL.md  ->  Scenario 1 (Path 1 revert or Path 2 accept).
Expect plan to show: egress rule re-created (A2), versioning back to Enabled (C), tags restored (B).
Expect plan NOT to mention the SSH rule (A1). That is the blind spot; clean it up by hand (see doc).
MSG
