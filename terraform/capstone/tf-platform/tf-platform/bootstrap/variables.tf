variable "project" {
  type        = string
  description = "Short project name used as a prefix for all resources."
  default     = "acme"
}

variable "region" {
  type        = string
  description = "AWS region for state buckets."
  default     = "ap-south-1"
}

variable "environments" {
  type        = list(string)
  description = "Environments that each get their own state bucket and CI roles."
  default     = ["dev", "prod"]
}

variable "github_repo" {
  type        = string
  description = "GitHub repository allowed to assume CI roles, as 'org/repo'."
}

variable "create_github_oidc_provider" {
  type        = bool
  description = "Create the GitHub OIDC provider. Set false if it already exists in this account."
  default     = true
}

variable "apply_role_policy_arns" {
  type        = list(string)
  description = "Managed policies attached to the CI APPLY roles. Default is broad: scope this down once you know what your stacks need."
  default     = ["arn:aws:iam::aws:policy/AdministratorAccess"]
}

variable "noncurrent_version_retention_days" {
  type        = number
  description = "How long old state versions are kept (your rollback window)."
  default     = 90
}
