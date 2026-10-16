variable "project" {
  type        = string
  description = "Project prefix; must match the bootstrap stack."
  default     = "acme"
}

variable "region" {
  type        = string
  description = "AWS region."
  default     = "ap-south-1"
}

variable "allowed_account_ids" {
  type        = list(string)
  description = "If set, Terraform refuses to run against any other AWS account."
  default     = []
}

variable "vpc_cidr" {
  type        = string
  description = "VPC CIDR."
}

variable "az_count" {
  type        = number
  description = "Number of AZs."
}

variable "enable_nat_gateway" {
  type        = bool
  description = "Create a NAT gateway for private-subnet egress."
}

variable "instance_type" {
  type        = string
  description = "EC2 instance type for the app instance."
}

variable "flow_log_retention_days" {
  type        = number
  description = "VPC flow log retention."
}

variable "bucket_noncurrent_retention_days" {
  type        = number
  description = "Days to keep old object versions in the data bucket."
}
