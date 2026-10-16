# Drift & failure drill (dev only)

Goal: practise detecting and recovering from the four things that actually go wrong:
**drift**, a **failed/partial apply**, a **stuck state lock**, and **bad state**.

> Status: these scripts and procedures were written but **not executed against a live AWS account** while this
> repo was produced. Outputs below are *representative*, not captured. Run the drill once in a scratch account
> and treat any mismatch as a doc bug to fix.

Prereqs: dev is deployed (`make apply ENV=dev`), AWS CLI authenticated to the **dev** account, and:

```bash
export TF_STATE_BUCKET_DEV=$(terraform -chdir=bootstrap output -json state_buckets | jq -r .dev)
```

The scripts refuse to run for any environment other than `dev`.

Before any drill, take a state backup you can restore:

```bash
terraform -chdir=envs/dev init -backend-config="bucket=$TF_STATE_BUCKET_DEV"
terraform -chdir=envs/dev state pull > /tmp/dev-before-drill.tfstate
```

---

## Scenario 1: Drift (someone changed things by hand)

```bash
./scripts/drill/01-drift.sh
```

What the script does behind Terraform's back:

| # | Out-of-band change | Will Terraform see it? |
|---|---|---|
| A1 | Adds `tcp/22 from 0.0.0.0/0` to the app security group | **No** (blind spot, see below) |
| A2 | Deletes the managed HTTPS egress rule | Yes: plan wants to re-create it |
| B | Overwrites the bucket's tags | Yes: `tags_all` differs |
| C | Suspends bucket versioning | Yes: `status` Enabled vs Suspended |

### Detect

```bash
cd envs/dev
terraform plan -refresh-only -var-file=dev.tfvars   # "what changed in the real world?"
terraform plan -var-file=dev.tfvars -detailed-exitcode   # "what would Terraform do?"  exit 2 = drift
```

Representative `-refresh-only` output:

```
Note: Objects have changed outside of Terraform
  # module.storage.aws_s3_bucket_versioning.this has changed
  ~ versioning_configuration { ~ status = "Enabled" -> "Suspended" }
  # module.compute.aws_vpc_security_group_egress_rule.https has been deleted
```

In CI, `drift.yml` runs the same plan nightly and opens a GitHub issue labelled `drift` on exit code 2.

### The blind spot (read this)

Terraform only knows about rules it manages. Because the module uses standalone
`aws_vpc_security_group_*_rule` resources (the recommended pattern), a rule added by hand is **not part of any
resource Terraform tracks**, so `plan` stays silent about A1. Mitigations, in order of value:

1. **AWS Config** managed rule `restricted-ssh` (or `INCOMING_SSH_DISABLED`) + an alert: detects it regardless of IaC.
2. **CloudTrail** alert on `AuthorizeSecurityGroupIngress` outside the CI role.
3. Remove console write access (SCP / IAM) so the change can't happen. This is the real fix.
4. Alternative design: inline `ingress = []` on the SG makes Terraform enforce "no ingress", but you then
   **cannot** mix inline and standalone rules on the same SG. Pick one style per SG.

Clean up A1 by hand:

```bash
aws ec2 revoke-security-group-ingress --group-id <sg-id> --protocol tcp --port 22 --cidr 0.0.0.0/0
```

### Recover: choose ONE path per drifted item

**Path 1: Revert (code is right, reality is wrong).** The default for security-relevant drift.

```bash
terraform plan  -var-file=dev.tfvars -out=tfplan   # read it: should only re-create egress rule, versioning, tags
terraform apply tfplan
terraform plan  -var-file=dev.tfvars -detailed-exitcode ; echo $?   # expect 0
```

**Path 2: Accept (reality is right, code is stale).** For intentional, approved changes made out-of-band.

```bash
terraform apply -refresh-only -var-file=dev.tfvars   # writes reality INTO STATE only; changes no infrastructure
# then edit the .tf code to match, and:
terraform plan -var-file=dev.tfvars -detailed-exitcode   # expect 0, otherwise code still differs
```

`-refresh-only` never touches real resources; it only updates state. Always follow it with a code change, or the next
normal plan will try to revert what you just accepted.

---

## Scenario 2: Failed (partial) apply

```bash
./scripts/drill/02-failed-apply.sh
```

It requests `instance_type=t3.doesnotexist`. Depending on provider version the error appears at plan time (validation)
or at apply time (EC2 API `InvalidParameterValue`). If your provider rejects it at plan, use a real type your account
can't launch (e.g. a GPU family) to get a genuine API-time failure.

Key facts about failures:

- Terraform **does not roll back**. Everything that succeeded before the error is real and is in state.
- A resource that failed *during create* may be recorded as **tainted** (next plan will say "must be replaced").
- Changing an instance type is an in-place change that **stops the instance, modifies it, starts it**. A failure in
  the middle can leave the instance **stopped**. Check it; don't assume.
- A normal error exits cleanly and **releases the lock**. (A crash does not: Scenario 3.)

### Recover

```bash
cd envs/dev

# 1. Read the error. Fix the CAUSE (wrong value, quota, IAM, name clash). Don't just retry.

# 2. See ground truth: what exists vs what state says?
terraform state list
terraform plan -refresh-only -var-file=dev.tfvars
aws ec2 describe-instances --instance-ids "$(terraform output -raw instance_id)" \
  --query 'Reservations[0].Instances[0].State.Name'       # running? stopped?

# 3. Look for taint: anything marked tainted will show "-/+ must be replaced" in plan.
#    If the object is actually fine, un-taint rather than replace:
terraform untaint module.compute.aws_instance.this        # only if you've verified it is healthy

# 4. Re-plan with the good value and apply. Terraform resumes from state; it never re-does completed work.
terraform plan  -var-file=dev.tfvars -out=tfplan
terraform apply tfplan

# 5. If the instance is still stopped and state says running:
aws ec2 start-instances --instance-ids "$(terraform output -raw instance_id)"

# 6. Prove it's clean.
terraform plan -var-file=dev.tfvars -detailed-exitcode ; echo $?   # expect 0
```

**Never** "fix" a failed apply by deleting resources by hand and hoping. That creates Scenario 1 on top of Scenario 2.

---

## Scenario 3: Stuck state lock (runner died mid-apply)

```bash
./scripts/drill/03-crash-lock.sh
```

It `kill -9`s `terraform apply`, so the lock is never released. With S3 native locking
(`use_lockfile = true`) the lock is a small object `platform/terraform.tfstate.tflock` next to the state file.
Next command fails with:

```
Error: Error acquiring the state lock
Lock Info:
  ID:        3f1c9a7e-....
  Operation: OperationTypeApply
  Who:       runner@host
  Created:   2026-...
```

### Recover

```bash
# 1. PROVE nobody is really running. Check CI and teammates first.
gh run list --workflow terraform --status in_progress
gh run list --workflow drift-detection --status in_progress
# Check the 'Who' and 'Created' fields in the lock info. A lock minutes old may be a live apply.

# 2. Only then, release it using the ID from the error:
terraform -chdir=envs/dev force-unlock 3f1c9a7e-....

# 3. If force-unlock cannot (corrupt lock object), delete the lock object. Last resort:
aws s3 rm "s3://$TF_STATE_BUCKET_DEV/platform/terraform.tfstate.tflock"

# 4. A killed apply may have partially applied. Treat as Scenario 2: refresh-only plan, then plan, then apply.
terraform -chdir=envs/dev plan -refresh-only -var-file=dev.tfvars
```

Force-unlocking while someone else is genuinely applying can corrupt state. That is why step 1 exists.
The workflow `concurrency` group (never cancel in progress) and `-lock-timeout=5m` reduce how often this occurs.

---

## Scenario 4: State is wrong or corrupted (rollback from S3 versioning)

Why the state bucket has versioning on: every write is a recoverable object version.

```bash
KEY=platform/terraform.tfstate
aws s3api list-object-versions --bucket "$TF_STATE_BUCKET_DEV" --prefix "$KEY" \
  --query 'Versions[].{Id:VersionId,Modified:LastModified,Latest:IsLatest}' --output table

# Download the last known-good version and inspect it BEFORE doing anything:
aws s3api get-object --bucket "$TF_STATE_BUCKET_DEV" --key "$KEY" --version-id <GOOD_VERSION_ID> /tmp/good.tfstate
jq '{serial, lineage, resources: (.resources|length)}' /tmp/good.tfstate

# Restore. `state push` refuses if the serial is lower or lineage differs; that is a safety feature:
terraform -chdir=envs/dev state push /tmp/good.tfstate
#   If you are certain: terraform state push -force /tmp/good.tfstate   (rare)

# Then reconcile with reality; the rolled-back state may be missing resources created after it:
terraform -chdir=envs/dev plan -refresh-only -var-file=dev.tfvars
#   Resource exists in AWS but not in state -> add an `import {}` block (see the import/moved notes)
```

---

## Debrief checklist

- [ ] Did you see all four drift items, and which one did Terraform *not* see?
- [ ] After recovery, does `plan -detailed-exitcode` return `0`?
- [ ] Is the lock gone and `terraform plan` working for a second person / CI?
- [ ] What alert would have told you *before* you ran the drill script?
- [ ] Open a PR that changes the SSH exposure control you chose (Config rule / SCP) so the blind spot is closed.
