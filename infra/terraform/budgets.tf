# §8.6: 월 USD 500·700에 budget alarm. budget_email 미설정 시 생성하지 않는다.
resource "aws_budgets_budget" "monthly" {
  for_each = var.budget_email == "" ? {} : { warn = "500", high = "700" }

  name         = "${var.project}-monthly-${each.value}"
  budget_type  = "COST"
  limit_amount = each.value
  limit_unit   = "USD"
  time_unit    = "MONTHLY"

  notification {
    comparison_operator        = "GREATER_THAN"
    threshold                  = 100
    threshold_type             = "PERCENTAGE"
    notification_type          = "ACTUAL"
    subscriber_email_addresses = [var.budget_email]
  }
}
