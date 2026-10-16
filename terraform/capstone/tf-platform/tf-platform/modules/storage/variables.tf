variable "bucket_name" {
  type        = string
  description = "Globally unique S3 bucket name."

  validation {
    condition     = can(regex("^[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]$", var.bucket_name))
    error_message = "bucket_name must be 3-63 chars: lowercase letters, numbers, dots, hyphens."
  }
}

variable "force_destroy" {
  type        = bool
  description = "Allow destroying a non-empty bucket. Keep false in prod."
  default     = false
}

variable "noncurrent_version_retention_days" {
  type        = number
  description = "Days to keep noncurrent object versions."
  default     = 30
}
