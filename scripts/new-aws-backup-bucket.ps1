#Requires -Version 7.0
param([string]$StateFile="$PSScriptRoot/../.tmp/m15-aws-state.json")
$ErrorActionPreference='Stop'
. "$PSScriptRoot/aws-deployment-context.ps1"
Assert-PairForgeDeploymentIdentity
$StateFile=[IO.Path]::GetFullPath($StateFile)
$state=Get-Content -Raw -LiteralPath $StateFile | ConvertFrom-Json -AsHashtable
if($state.Account -ne '298984481596' -or $state.Region -ne 'us-east-1' -or -not $state.S3EndpointId) { throw 'Approved account and S3 gateway endpoint must be recorded first' }
$bucket='pairforge-298984481596-us-east-1-backups'
function Request($Action,$Body) {
    $file=[IO.Path]::GetTempFileName()
    try {
        [IO.File]::WriteAllText($file,($Body | ConvertTo-Json -Depth 15),[Text.UTF8Encoding]::new($false))
        return Invoke-PairForgeAws @('s3api',$Action,'--cli-input-json',"file://$file")
    } finally { Remove-Item -LiteralPath $file -Force }
}
if(-not $state.BackupBucket) {
    $aws=Get-PairForgeAwsExecutable
    $head=& $aws s3api head-bucket --bucket $bucket --expected-bucket-owner 298984481596 --profile pairforge --region us-east-1 --no-cli-pager 2>&1
    if($LASTEXITCODE -eq 0) { throw 'Bucket already exists without recorded ownership; reconcile before changing it' }
    if(($head -join ' ') -notmatch '\(404\)') { throw 'Could not confirm bucket absence; refusing creation' }
    [void](Request create-bucket @{Bucket=$bucket;ObjectOwnership='BucketOwnerEnforced'})
    $state.BackupBucket=$bucket
    [IO.File]::WriteAllText("$StateFile.new",($state | ConvertTo-Json -Depth 12))
    Move-Item -LiteralPath "$StateFile.new" -Destination $StateFile -Force
} elseif($state.BackupBucket -ne $bucket) { throw 'Unexpected recorded bucket' }
$base=@{Bucket=$bucket;ExpectedBucketOwner='298984481596'}
$block=@{BlockPublicAcls=$true;IgnorePublicAcls=$true;BlockPublicPolicy=$true;RestrictPublicBuckets=$true}
[void](Request put-public-access-block ($base+@{PublicAccessBlockConfiguration=$block}))
[void](Request put-bucket-ownership-controls ($base+@{OwnershipControls=@{Rules=@(@{ObjectOwnership='BucketOwnerEnforced'})}}))
[void](Request put-bucket-encryption ($base+@{ServerSideEncryptionConfiguration=@{Rules=@(@{ApplyServerSideEncryptionByDefault=@{SSEAlgorithm='AES256'}})}}))
[void](Request put-bucket-tagging ($base+@{Tagging=@{TagSet=@(@{Key='Project';Value='PairForge'})}}))
[void](Request put-bucket-lifecycle-configuration ($base+@{LifecycleConfiguration=@{Rules=@(@{ID='seven-day-postgres';Status='Enabled';Filter=@{Prefix='postgres/'};Expiration=@{Days=7};AbortIncompleteMultipartUpload=@{DaysAfterInitiation=1}})}}))
$policy=@{Version='2012-10-17';Statement=@(
    @{Sid='RequireTls';Effect='Deny';Principal='*';Action='s3:*';Resource=@("arn:aws:s3:::$bucket","arn:aws:s3:::$bucket/*");Condition=@{Bool=@{'aws:SecureTransport'='false'}}},
    @{Sid='BackupRoleUsesPrivateEndpoint';Effect='Deny';Principal='*';Action=@('s3:PutObject','s3:AbortMultipartUpload');Resource="arn:aws:s3:::$bucket/postgres/*";Condition=@{ArnEquals=@{'aws:PrincipalArn'='arn:aws:iam::298984481596:role/pairforge/pairforge-app-backup'};StringNotEquals=@{'aws:SourceVpce'=$state.S3EndpointId}}}
)}
[void](Request put-bucket-policy ($base+@{Policy=($policy | ConvertTo-Json -Depth 12 -Compress)}))
$actual=Request get-public-access-block $base
foreach($name in $block.Keys) { if($actual.PublicAccessBlockConfiguration.$name -ne $true) { throw "Missing public access protection: $name" } }
$encryption=Request get-bucket-encryption $base
if($encryption.ServerSideEncryptionConfiguration.Rules[0].ApplyServerSideEncryptionByDefault.SSEAlgorithm -ne 'AES256') { throw 'Bucket encryption verification failed' }
$lifecycle=Request get-bucket-lifecycle-configuration $base
if($lifecycle.Rules.Count -ne 1 -or $lifecycle.Rules[0].Expiration.Days -ne 7 -or $lifecycle.Rules[0].Filter.Prefix -ne 'postgres/' -or $lifecycle.Rules[0].Status -ne 'Enabled') { throw 'Retention verification failed' }
$ownership=Request get-bucket-ownership-controls $base
if($ownership.OwnershipControls.Rules[0].ObjectOwnership -ne 'BucketOwnerEnforced') { throw 'Bucket ownership verification failed' }
$actualPolicy=(Request get-bucket-policy $base).Policy | ConvertFrom-Json
if($actualPolicy.Statement.Count -ne 2 -or $actualPolicy.Statement[1].Condition.StringNotEquals.'aws:SourceVpce' -ne $state.S3EndpointId) { throw 'Bucket endpoint policy verification failed' }
Write-Host 'Verified private backup bucket, TLS-only access, encryption, private endpoint for host uploads and seven-day retention. No database backup or restore has been performed.'
