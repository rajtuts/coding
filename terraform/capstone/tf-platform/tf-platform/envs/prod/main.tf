data "aws_caller_identity" "current" {}

locals {
  environment = "prod" # hard-coded per directory on purpose: the folder IS the environment
  name        = "${var.project}-${local.environment}"
}

module "network" {
  source = "../../modules/network"

  name                    = local.name
  vpc_cidr                = var.vpc_cidr
  az_count                = var.az_count
  enable_nat_gateway      = var.enable_nat_gateway
  flow_log_retention_days = var.flow_log_retention_days
}

module "storage" {
  source = "../../modules/storage"

  bucket_name                       = "${local.name}-data-${data.aws_caller_identity.current.account_id}"
  force_destroy                     = false # prod data is never force-destroyed
  noncurrent_version_retention_days = var.bucket_noncurrent_retention_days
}

module "compute" {
  source = "../../modules/compute"

  name            = local.name
  vpc_id          = module.network.vpc_id
  subnet_id       = module.network.private_subnet_ids[0]
  instance_type   = var.instance_type
  data_bucket_arn = module.storage.bucket_arn
}
