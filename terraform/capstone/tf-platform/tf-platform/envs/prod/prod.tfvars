region                           = "ap-south-1"
vpc_cidr                         = "10.20.0.0/16"
az_count                         = 2
enable_nat_gateway               = true
instance_type                    = "t3.small"
flow_log_retention_days          = 365
bucket_noncurrent_retention_days = 90
# allowed_account_ids            = ["222222222222"]
