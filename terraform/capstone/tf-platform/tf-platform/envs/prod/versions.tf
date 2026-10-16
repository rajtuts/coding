terraform {
  required_version = ">= 1.10"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.80"
    }
  }

  # Partial backend: bucket is injected at init time so no account IDs live in git.
  #   terraform init -backend-config="bucket=$TF_STATE_BUCKET"
  backend "s3" {
    key          = "platform/terraform.tfstate"
    region       = "ap-south-1"
    encrypt      = true
    use_lockfile = true # S3-native locking (Terraform >= 1.10): no DynamoDB table needed
  }
}
