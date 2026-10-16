region                           = "ap-south-1"
vpc_cidr                         = "10.10.0.0/16"
az_count                         = 2
enable_nat_gateway               = false # save money; set true if you need SSM/outbound from the instance
instance_type                    = "t3.micro"
flow_log_retention_days          = 7
bucket_noncurrent_retention_days = 7
# allowed_account_ids            = ["111111111111"]
