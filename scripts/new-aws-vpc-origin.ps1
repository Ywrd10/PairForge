#Requires -Version 7.0
param([string]$StateFile="$PSScriptRoot/../.tmp/m15-aws-state.json")
$ErrorActionPreference='Stop'
. "$PSScriptRoot/aws-deployment-context.ps1"
Assert-PairForgeDeploymentIdentity
$StateFile=[IO.Path]::GetFullPath($StateFile)
$state=Get-Content -Raw -LiteralPath $StateFile | ConvertFrom-Json -AsHashtable
if($state.Account -ne '298984481596' -or $state.Region -ne 'us-east-1' -or -not $state.AppInstanceId) { throw 'Approved application host required' }
$record=Invoke-PairForgeAws @('ec2','describe-instances','--instance-ids',$state.AppInstanceId)
$instance=$record.Reservations[0].Instances[0]
if($instance.State.Name -ne 'running' -or $instance.VpcId -ne $state.VpcId -or $instance.SubnetId -ne $state.AppSubnetId -or
   $instance.PrivateIpAddress -ne $state.AppPrivateIp -or $instance.InstanceType -ne 't3a.medium' -or $instance.PublicIpAddress) { throw 'Private application instance is not ready or differs from approved topology' }
$arn="arn:aws:ec2:us-east-1:298984481596:instance/$($state.AppInstanceId)"
$existing=Invoke-PairForgeAws @('cloudfront','list-vpc-origins')
$matches=@($existing.VpcOriginList.Items | Where-Object { $_.VpcOriginEndpointConfig.Name -eq 'pairforge-app' })
if($matches.Count -gt 1 -or ($matches.Count -eq 1 -and ($matches[0].Id -ne $state.VpcOriginId -or $matches[0].VpcOriginEndpointConfig.Arn -ne $arn))) { throw 'Unrecorded or mismatched VPC origin; reconcile before creating another' }
if($state.VpcOriginId) {
    if($matches.Count -ne 1) { throw 'Recorded VPC origin missing' }
    Write-Host "Recorded VPC origin $($state.VpcOriginId): $($matches[0].Status)"
    exit 0
}
$request=@{
    VpcOriginEndpointConfig=@{Name='pairforge-app';Arn=$arn;HTTPPort=8084;HTTPSPort=443;OriginProtocolPolicy='http-only'}
    Tags=@{Items=@(@{Key='Project';Value='PairForge'},@{Key='Name';Value='pairforge-app'})}
}
$file=[IO.Path]::GetTempFileName()
try {
    [IO.File]::WriteAllText($file,($request | ConvertTo-Json -Depth 10),[Text.UTF8Encoding]::new($false))
    $created=Invoke-PairForgeAws @('cloudfront','create-vpc-origin','--cli-input-json',"file://$file")
    if(-not $created.VpcOrigin.Id -or $created.VpcOrigin.VpcOriginEndpointConfig.Arn -ne $arn) { throw 'CloudFront did not return the expected origin' }
    $state.VpcOriginId=$created.VpcOrigin.Id
    [IO.File]::WriteAllText("$StateFile.new",($state | ConvertTo-Json -Depth 12))
    Move-Item -LiteralPath "$StateFile.new" -Destination $StateFile -Force
    Write-Host "Created private VPC origin $($state.VpcOriginId): $($created.VpcOrigin.Status). CloudFront-to-Caddy will use approved private HTTP."
} finally { Remove-Item -LiteralPath $file -Force }
