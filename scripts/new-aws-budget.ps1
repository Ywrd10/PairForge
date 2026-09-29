#Requires -Version 7.0
param([Parameter(Mandatory)][string]$Email)
$ErrorActionPreference='Stop'
. "$PSScriptRoot/aws-deployment-context.ps1"
$operator=Get-PairForgeOperatorConfig
$address=[Net.Mail.MailAddress]::new($Email)
if($address.Address -cne $Email -or $Email.Contains('[')) { throw 'Use a real alert email address without a display name or placeholder' }
Assert-PairForgeDeploymentIdentity
# A create-only operation fails if the budget exists. Never overwrite a pre-existing budget.
$request=@{
    AccountId=$operator.AccountId
    Budget=@{BudgetName='PairForge-Monthly';BudgetLimit=@{Amount='50';Unit='USD'};TimeUnit='MONTHLY';BudgetType='COST'}
    NotificationsWithSubscribers=@(50,80,100 | ForEach-Object {
        @{Notification=@{NotificationType='ACTUAL';ComparisonOperator='GREATER_THAN';Threshold=$_;ThresholdType='PERCENTAGE'};Subscribers=@(@{SubscriptionType='EMAIL';Address=$Email})}
    })
}
$file=[IO.Path]::GetTempFileName()
try {
    [IO.File]::WriteAllText($file,($request | ConvertTo-Json -Depth 8),[Text.UTF8Encoding]::new($false))
    [void](Invoke-PairForgeAws @('budgets','create-budget','--cli-input-json',"file://$file"))
    $budget=Invoke-PairForgeAws @('budgets','describe-budget','--account-id',$operator.AccountId,'--budget-name','PairForge-Monthly')
    if([decimal]$budget.Budget.BudgetLimit.Amount -ne 50 -or $budget.Budget.BudgetLimit.Unit -ne 'USD') { throw 'Budget verification failed' }
    $notifications=Invoke-PairForgeAws @('budgets','describe-notifications-for-budget','--account-id',$operator.AccountId,'--budget-name','PairForge-Monthly')
    if((($notifications.Notifications.Threshold | Sort-Object) -join ',') -ne '50,80,100') { throw 'Budget notification verification failed' }
    foreach($notification in $notifications.Notifications) {
        @{AccountId=$operator.AccountId;BudgetName='PairForge-Monthly';Notification=$notification} | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $file
        $subscribers=Invoke-PairForgeAws @('budgets','describe-subscribers-for-notification','--cli-input-json',"file://$file")
        if($subscribers.Subscribers.Count -ne 1 -or $subscribers.Subscribers[0].SubscriptionType -ne 'EMAIL' -or $subscribers.Subscribers[0].Address -ne $Email) { throw 'Budget recipient verification failed' }
    }
    Write-Host 'Created and verified the $50 monthly budget and 50/80/100 percent notification thresholds. Email delivery and costs remain subject to AWS processing delays; this is not a spending cap.'
} finally { Remove-Item -LiteralPath $file -Force }
