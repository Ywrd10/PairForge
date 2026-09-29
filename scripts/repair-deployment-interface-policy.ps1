#Requires -Version 7.0
# One-time administrator repair. Requires explicit approval to use profile default.
# Infrastructure provisioning must continue exclusively through profile pairforge.
param([Parameter(Mandatory)][switch]$ApprovedAdministratorRepair)
$ErrorActionPreference='Stop'
if(-not $ApprovedAdministratorRepair) { throw 'Explicit approval for this administrator repair is required' }
. "$PSScriptRoot/aws-deployment-context.ps1"
$operator=Get-PairForgeOperatorConfig
$state=Get-PairForgeDeploymentState -RequiredKeys AppSubnetId,AdminSecurityGroupId
$aws=Get-PairForgeAwsExecutable
function AdminRead($Arguments) {
    $output=& $aws @Arguments --profile default --region us-east-1 --output json --no-cli-pager
    if($LASTEXITCODE -ne 0) { throw "Administrator operation failed: $($Arguments[1])" }
    if($output) { return ($output | ConvertFrom-Json -AsHashtable) }
}
$identity=AdminRead @('sts','get-caller-identity')
if($identity.Account -ne $operator.AccountId -or $identity.Arn -ne "arn:aws:iam::$($operator.AccountId):root") { throw 'Administrator profile does not match the explicitly approved account/identity' }
$arn="arn:aws:iam::$($operator.AccountId):policy/pairforge/PairForge-compute"
$policy=AdminRead @('iam','get-policy','--policy-arn',$arn)
$version=AdminRead @('iam','get-policy-version','--policy-arn',$arn,'--version-id',$policy.Policy.DefaultVersionId)
$document=$version.PolicyVersion.Document
$statements=@($document.Statement | Where-Object Sid -eq 'AdministrationInterfaceInApprovedSubnet')
if($statements.Count -ne 1 -or $statements[0].Effect -ne 'Allow' -or @($statements[0].Action).Count -ne 1 -or $statements[0].Action[0] -ne 'ec2:CreateNetworkInterface' -or @($statements[0].Resource).Count -ne 1 -or $statements[0].Resource[0] -ne "arn:aws:ec2:us-east-1:$($operator.AccountId):network-interface/*") { throw 'Active policy differs from the reviewed repair target' }
if(-not $statements[0].Condition) { Write-Host 'The reviewed interface condition is already removed; no change made.'; exit 0 }
if(($statements[0].Condition.Keys -join ',') -ne 'ArnEquals' -or ($statements[0].Condition.ArnEquals.Keys -join ',') -ne 'ec2:Subnet' -or $statements[0].Condition.ArnEquals.'ec2:Subnet' -ne "arn:aws:ec2:us-east-1:$($operator.AccountId):subnet/$($state.AppSubnetId)") { throw 'Unexpected condition; refusing an unreviewed policy edit' }
$dependencies=@($document.Statement | Where-Object Sid -eq 'AdministrationInterfaceDependencies')
if($dependencies.Count -ne 1 -or (($dependencies[0].Resource | Sort-Object) -join ',') -ne "arn:aws:ec2:us-east-1:$($operator.AccountId):security-group/$($state.AdminSecurityGroupId),arn:aws:ec2:us-east-1:$($operator.AccountId):subnet/$($state.AppSubnetId)") { throw 'Exact subnet/security-group boundary must remain in place' }
$versions=AdminRead @('iam','list-policy-versions','--policy-arn',$arn)
if($versions.Versions.Count -ge 5) { throw 'No free policy version slot; administrator must remove an obsolete non-default version before this repair' }
$statements[0].Remove('Condition')
$file=[IO.Path]::GetTempFileName()
try {
    [IO.File]::WriteAllText($file,($document | ConvertTo-Json -Depth 20),[Text.UTF8Encoding]::new($false))
    $current=AdminRead @('iam','get-policy','--policy-arn',$arn)
    if($current.Policy.DefaultVersionId -ne $policy.Policy.DefaultVersionId) { throw 'Policy changed during review; refusing to overwrite concurrent edits' }
    $created=AdminRead @('iam','create-policy-version','--policy-arn',$arn,'--policy-document',"file://$file",'--set-as-default')
    $verified=AdminRead @('iam','get-policy','--policy-arn',$arn)
    if($verified.Policy.DefaultVersionId -ne $created.PolicyVersion.VersionId) { throw 'Policy default version verification failed' }
    Write-Host "Applied only the reviewed condition removal in $($created.PolicyVersion.VersionId). All other permissions preserved."
} finally { Remove-Item -LiteralPath $file -Force }
