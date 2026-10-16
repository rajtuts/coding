output "vpc_id" {
  description = "VPC ID."
  value       = module.network.vpc_id
}

output "instance_id" {
  description = "App instance ID."
  value       = module.compute.instance_id
}

output "data_bucket" {
  description = "Data bucket name."
  value       = module.storage.bucket_id
}
