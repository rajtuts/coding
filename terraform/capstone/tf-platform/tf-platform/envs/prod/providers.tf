provider "aws" {
  region = var.region

  # Guard rail: refuses to run against the wrong AWS account.
  # Set allowed_account_ids in prod.tfvars once you know the account ID.
  allowed_account_ids = length(var.allowed_account_ids) > 0 ? var.allowed_account_ids : null

  default_tags {
    tags = {
      Project     = var.project
      Environment = local.environment
      ManagedBy   = "terraform"
      Repo        = "tf-platform"
    }
  }
}
