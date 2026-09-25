#Requires -Version 7.0
# One-time administrator repair; infrastructure operations still use pairforge.
param([Parameter(Mandatory)][switch]$ApprovedAdministratorRepair)
$ErrorActionPreference='Stop'
if(-not $ApprovedAdministratorRepair) { throw 'Explicit administrator repair approval required' }
. "$PSScriptRoot/aws-deployment-context.ps1"
$aws=Get-PairForgeAwsExecutable
function Admin($Arguments) {
    $output=& $aws @Arguments --profile default --region us-east-1 --output json --no-cli-pager
    if($LASTEXITCODE -ne 0) { throw "Administrator operation failed: $($Arguments[1])" }
    if($output) { return ($output | ConvertFrom-Json -AsHashtable) }
}
$identity=Admin @('sts','get-caller-identity')
if($identity.Account -ne '298984481596' -or $identity.Arn -ne 'arn:aws:iam::298984481596:root') { throw 'Unexpected administrator identity' }
$arn='arn:aws:iam::298984481596:policy/pairforge/PairForge-compute'
$policy=Admin @('iam','get-policy','--policy-arn',$arn)
$version=Admin @('iam','get-policy-version','--policy-arn',$arn,'--version-id',$policy.Policy.DefaultVersionId)
$document=$version.PolicyVersion.Document
$statements=@($document.Statement | Where-Object Sid -eq 'DisableWorkerMetadataAfterBootstrap')
if($statements.Count -ne 1) { throw 'Expected one metadata statement' }
$statement=$statements[0]
$expected=@{StringEquals=@{'aws:ResourceTag/Project'='PairForge';'aws:ResourceTag/Name'='pairforge-worker';'ec2:MetadataHttpEndpoint'='disabled'}}
$actualNode=[System.Text.Json.Nodes.JsonNode]::Parse(($statement.Condition | ConvertTo-Json -Depth 10))
$expectedNode=[System.Text.Json.Nodes.JsonNode]::Parse(($expected | ConvertTo-Json -Depth 10))
if(-not [System.Text.Json.Nodes.JsonNode]::DeepEquals($actualNode,$expectedNode) -or
   $statement.Effect -ne 'Allow' -or @($statement.Action).Count -ne 1 -or $statement.Action[0] -ne 'ec2:ModifyInstanceMetadataOptions' -or
   @($statement.Resource).Count -ne 1 -or $statement.Resource[0] -ne 'arn:aws:ec2:us-east-1:298984481596:instance/*') { throw 'Unexpected metadata permission; review manually' }
$statement.Condition.StringEquals.Remove('ec2:MetadataHttpEndpoint')
$statement.Condition.StringEquals['ec2:Attribute/HttpEndpoint']='disabled'
$statement.Condition['ForAllValues:StringEquals']=@{'ec2:Attribute'=@('HttpEndpoint')}
$statement.Condition.Null=@{'ec2:Attribute'='false'}
$statement.Resource=@('arn:aws:ec2:us-east-1:298984481596:instance/i-06befca753a936715')
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
    Write-Host "Repaired requested metadata attribute condition for the exact worker only, in $($created.PolicyVersion.VersionId)."
} finally { Remove-Item -LiteralPath $file -Force }
