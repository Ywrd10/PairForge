#Requires -Version 7.0
# Approved IAM bootstrap repair only; never use the administrator for provisioning.
param([Parameter(Mandatory)][switch]$ApprovedAdministratorRepair)
$ErrorActionPreference='Stop'
if(-not $ApprovedAdministratorRepair) { throw 'Administrator repair approval required' }
. "$PSScriptRoot/aws-deployment-context.ps1"
$operator=Get-PairForgeOperatorConfig
$state=Get-PairForgeDeploymentState -RequiredKeys AppSubnetId,AdminSecurityGroupId
$aws=Get-PairForgeAwsExecutable
function Admin($Arguments) {
    $output=& $aws @Arguments --profile default --region us-east-1 --output json --no-cli-pager
    if($LASTEXITCODE -ne 0) { throw "Administrator operation failed: $($Arguments[1])" }
    if($output) { return ($output | ConvertFrom-Json -AsHashtable) }
}
$identity=Admin @('sts','get-caller-identity')
if($identity.Account -ne $operator.AccountId -or $identity.Arn -ne "arn:aws:iam::$($operator.AccountId):root") { throw 'Unexpected administrator identity' }
$arn="arn:aws:iam::$($operator.AccountId):policy/pairforge/PairForge-compute"
$policy=Admin @('iam','get-policy','--policy-arn',$arn)
$version=Admin @('iam','get-policy-version','--policy-arn',$arn,'--version-id',$policy.Policy.DefaultVersionId)
$document=$version.PolicyVersion.Document
if(@($document.Statement | Where-Object Sid -eq 'AdministrationInterfaceCreationTags').Count) { throw 'Tag statement already exists; inspect instead of overwriting' }
$dependencies=@($document.Statement | Where-Object Sid -eq 'AdministrationInterfaceDependencies')
if($dependencies.Count -ne 1 -or (($dependencies[0].Resource | Sort-Object) -join ',') -ne "arn:aws:ec2:us-east-1:$($operator.AccountId):security-group/$($state.AdminSecurityGroupId),arn:aws:ec2:us-east-1:$($operator.AccountId):subnet/$($state.AppSubnetId)") { throw 'Exact administration boundary missing' }
$document.Statement+=@{Sid='AdministrationInterfaceCreationTags';Effect='Allow';Action=@('ec2:CreateTags');Resource=@("arn:aws:ec2:us-east-1:$($operator.AccountId):network-interface/*");Condition=@{StringEquals=@{'ec2:NetworkInterfaceID'='*'}}}
$file=[IO.Path]::GetTempFileName()
try {
    [IO.File]::WriteAllText($file,($document | ConvertTo-Json -Depth 20),[Text.UTF8Encoding]::new($false))
    $versions=Admin @('iam','list-policy-versions','--policy-arn',$arn)
    if($versions.Versions.Count -ge 5) {
        $old=$versions.Versions | Where-Object { -not $_.IsDefaultVersion } | Sort-Object CreateDate | Select-Object -First 1
        if(-not $old) { throw 'No obsolete non-default version available' }
        $backup=Admin @('iam','get-policy-version','--policy-arn',$arn,'--version-id',$old.VersionId)
        $history=[IO.Path]::GetFullPath("$PSScriptRoot/../.tmp/m15-iam-history")
        [void][IO.Directory]::CreateDirectory($history)
        [IO.File]::WriteAllText((Join-Path $history "compute-$($old.VersionId).json"),($backup.PolicyVersion.Document | ConvertTo-Json -Depth 20))
        [void](Admin @('iam','delete-policy-version','--policy-arn',$arn,'--version-id',$old.VersionId))
    }
    $current=Admin @('iam','get-policy','--policy-arn',$arn)
    if($current.Policy.DefaultVersionId -ne $policy.Policy.DefaultVersionId) { throw 'Concurrent policy edit detected' }
    $created=Admin @('iam','create-policy-version','--policy-arn',$arn,'--policy-document',"file://$file",'--set-as-default')
    $verified=Admin @('iam','get-policy','--policy-arn',$arn)
    if($verified.Policy.DefaultVersionId -ne $created.PolicyVersion.VersionId) { throw 'Default version verification failed' }
    Write-Host "Added only creation-time interface-tag permission in $($created.PolicyVersion.VersionId); existing-interface tagging remains denied."
} finally { Remove-Item -LiteralPath $file -Force }
