variable "name" {
  type        = string
  description = "Name prefix for all network resources (e.g. acme-dev)."
}

variable "vpc_cidr" {
  type        = string
  description = "CIDR block for the VPC. Must be /20 or larger so it can be split into 16 subnets."

  validation {
    condition     = can(cidrhost(var.vpc_cidr, 0)) && tonumber(split("/", var.vpc_cidr)[1]) <= 20
    error_message = "vpc_cidr must be a valid CIDR with a prefix length of /20 or larger (e.g. 10.10.0.0/16)."
  }
}

variable "az_count" {
  type        = number
  description = "Number of availability zones to spread subnets across."
  default     = 2

  validation {
    condition     = var.az_count >= 1 && var.az_count <= 4
    error_message = "az_count must be between 1 and 4."
  }
}

variable "enable_nat_gateway" {
  type        = bool
  description = "Create a single NAT gateway so private subnets have outbound internet. Costs money; off by default."
  default     = false
}

variable "flow_log_retention_days" {
  type        = number
  description = "Retention for VPC flow logs in CloudWatch."
  default     = 14
}
