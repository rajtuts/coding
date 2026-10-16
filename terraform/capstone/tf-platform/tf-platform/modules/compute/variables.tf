variable "name" {
  type        = string
  description = "Name prefix (e.g. acme-dev)."
}

variable "vpc_id" {
  type        = string
  description = "VPC to place the security group in."
}

variable "subnet_id" {
  type        = string
  description = "Private subnet for the instance."
}

variable "instance_type" {
  type        = string
  description = "EC2 instance type."
  default     = "t3.micro"
}

variable "root_volume_size_gb" {
  type        = number
  description = "Root EBS volume size in GiB."
  default     = 20
}

variable "data_bucket_arn" {
  type        = string
  description = "ARN of the bucket the instance may read/write (least privilege)."
}
