# Keyless CI auth: GitHub Actions exchanges its OIDC token for short-lived AWS creds.
# No long-lived access keys are stored in GitHub.

resource "aws_iam_openid_connect_provider" "github" {
  count = var.create_github_oidc_provider ? 1 : 0

  url            = "https://token.actions.githubusercontent.com"
  client_id_list = ["sts.amazonaws.com"]
  # AWS validates GitHub's cert chain itself; the thumbprint is required by the API but not used for trust.
  thumbprint_list = ["6938fd4d98bab03faadb97b34396831e3780aea1"]
}

data "aws_iam_openid_connect_provider" "github" {
  count = var.create_github_oidc_provider ? 0 : 1
  url   = "https://token.actions.githubusercontent.com"
}

locals {
  oidc_arn = var.create_github_oidc_provider ? aws_iam_openid_connect_provider.github[0].arn : data.aws_iam_openid_connect_provider.github[0].arn
}

# PLAN role: read-only + read/write on that env's state bucket (plan must take the lock).
# Assumable from pull requests and from main (for scheduled drift detection).
data "aws_iam_policy_document" "plan_trust" {
  for_each = toset(var.environments)

  statement {
    actions = ["sts:AssumeRoleWithWebIdentity"]
    principals {
      type        = "Federated"
      identifiers = [local.oidc_arn]
    }
    condition {
      test     = "StringEquals"
      variable = "token.actions.githubusercontent.com:aud"
      values   = ["sts.amazonaws.com"]
    }
    condition {
      test     = "StringLike"
      variable = "token.actions.githubusercontent.com:sub"
      values = [
        "repo:${var.github_repo}:pull_request",
        "repo:${var.github_repo}:ref:refs/heads/main",
      ]
    }
  }
}

resource "aws_iam_role" "plan" {
  for_each = toset(var.environments)

  name               = "${var.project}-tf-plan-${each.key}"
  assume_role_policy = data.aws_iam_policy_document.plan_trust[each.key].json
}

resource "aws_iam_role_policy_attachment" "plan_readonly" {
  for_each = aws_iam_role.plan

  role       = each.value.name
  policy_arn = "arn:aws:iam::aws:policy/ReadOnlyAccess"
}

data "aws_iam_policy_document" "state_access" {
  for_each = toset(var.environments)

  statement {
    actions   = ["s3:ListBucket"]
    resources = [aws_s3_bucket.state[each.key].arn]
  }
  statement {
    actions   = ["s3:GetObject", "s3:PutObject", "s3:DeleteObject"] # DeleteObject: native lock file cleanup
    resources = ["${aws_s3_bucket.state[each.key].arn}/*"]
  }
}

resource "aws_iam_role_policy" "plan_state" {
  for_each = aws_iam_role.plan

  name   = "state-access"
  role   = each.value.id
  policy = data.aws_iam_policy_document.state_access[each.key].json
}

# APPLY role: only assumable by the GitHub *Environment* of the same name.
# Put required reviewers on the 'prod' environment in GitHub => human approval gate.
data "aws_iam_policy_document" "apply_trust" {
  for_each = toset(var.environments)

  statement {
    actions = ["sts:AssumeRoleWithWebIdentity"]
    principals {
      type        = "Federated"
      identifiers = [local.oidc_arn]
    }
    condition {
      test     = "StringEquals"
      variable = "token.actions.githubusercontent.com:aud"
      values   = ["sts.amazonaws.com"]
    }
    condition {
      test     = "StringEquals"
      variable = "token.actions.githubusercontent.com:sub"
      values   = ["repo:${var.github_repo}:environment:${each.key}"]
    }
  }
}

resource "aws_iam_role" "apply" {
  for_each = toset(var.environments)

  name               = "${var.project}-tf-apply-${each.key}"
  assume_role_policy = data.aws_iam_policy_document.apply_trust[each.key].json
}

resource "aws_iam_role_policy_attachment" "apply" {
  for_each = {
    for pair in setproduct(var.environments, var.apply_role_policy_arns) :
    "${pair[0]}|${pair[1]}" => { env = pair[0], arn = pair[1] }
  }

  role       = aws_iam_role.apply[each.value.env].name
  policy_arn = each.value.arn
}
