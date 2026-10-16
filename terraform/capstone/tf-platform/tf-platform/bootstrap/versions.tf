terraform {
  required_version = ">= 1.10"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.80"
    }
  }
  # Bootstrap uses LOCAL state on purpose: it creates the remote backend, so it cannot
  # use it (chicken-and-egg). Keep bootstrap/terraform.tfstate somewhere safe
  # (encrypted drive / password manager attachment), or migrate it later - see README.
}

provider "aws" {
  region = var.region

  default_tags {
    tags = {
      Project   = var.project
      ManagedBy = "terraform"
      Stack     = "bootstrap"
    }
  }
}
