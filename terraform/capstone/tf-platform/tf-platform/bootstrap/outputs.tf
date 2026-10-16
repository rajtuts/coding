output "state_buckets" {
  description = "Environment => state bucket name. Put these in GitHub vars TF_STATE_BUCKET_<ENV> and your local backend config."
  value       = { for env, b in aws_s3_bucket.state : env => b.id }
}

output "plan_role_arns" {
  description = "Environment => read-only CI plan role ARN (GitHub var AWS_PLAN_ROLE_<ENV>)."
  value       = { for env, r in aws_iam_role.plan : env => r.arn }
}

output "apply_role_arns" {
  description = "Environment => CI apply role ARN (GitHub *environment* var AWS_APPLY_ROLE)."
  value       = { for env, r in aws_iam_role.apply : env => r.arn }
}
