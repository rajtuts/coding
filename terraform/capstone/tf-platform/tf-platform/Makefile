# Usage: make plan ENV=dev   |  make apply ENV=prod   |  make scan
# Requires: TF_STATE_BUCKET_DEV / TF_STATE_BUCKET_PROD exported (see bootstrap outputs).
ENV ?= dev
BUCKET_VAR := TF_STATE_BUCKET_$(shell echo $(ENV) | tr a-z A-Z)
BUCKET := $($(BUCKET_VAR))
DIR := envs/$(ENV)

.PHONY: fmt validate init plan apply drift scan docs

fmt:
	terraform fmt -recursive

validate:
	@for d in bootstrap envs/dev envs/prod; do terraform -chdir=$$d init -backend=false -input=false >/dev/null && terraform -chdir=$$d validate || exit 1; done

init:
	@test -n "$(BUCKET)" || (echo "Export $(BUCKET_VAR) first"; exit 1)
	terraform -chdir=$(DIR) init -reconfigure -backend-config="bucket=$(BUCKET)"

plan: init
	terraform -chdir=$(DIR) plan -var-file=$(ENV).tfvars -out=tfplan

apply: plan
	terraform -chdir=$(DIR) apply tfplan

# exit 0 = clean, 2 = drift/changes, 1 = error
drift: init
	terraform -chdir=$(DIR) plan -var-file=$(ENV).tfvars -detailed-exitcode -refresh-only; echo "exit=$$?"

scan:
	trivy config --severity HIGH,CRITICAL --ignorefile .trivyignore .
	checkov --config-file .checkov.yaml

docs:
	terraform-docs markdown table --output-file README.md --output-mode inject modules/network
	terraform-docs markdown table --output-file README.md --output-mode inject modules/storage
	terraform-docs markdown table --output-file README.md --output-mode inject modules/compute
