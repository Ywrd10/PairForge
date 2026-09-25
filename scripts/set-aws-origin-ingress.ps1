#Requires -Version 7.0
param([string]$StateFile="$PSScriptRoot/../.tmp/m15-aws-state.json")
$ErrorActionPreference='Stop'
. "$PSScriptRoot/aws-deployment-context.ps1"
Assert-PairForgeDeploymentIdentity
$StateFile=[IO.Path]::GetFullPath($StateFile)
$state=Get-Content -Raw -LiteralPath $StateFile | ConvertFrom-Json -AsHashtable
if($state.Account -ne '298984481596' -or -not $state.VpcOriginId -or -not $state.AppSecurityGroupId) { throw 'Recorded VPC origin and application group required' }
$origin=Invoke-PairForgeAws @('cloudfront','get-vpc-origin','--id',$state.VpcOriginId)
$expected="arn:aws:ec2:us-east-1:298984481596:instance/$($state.AppInstanceId)"
if($origin.VpcOrigin.VpcOriginEndpointConfig.Arn -ne $expected -or $origin.VpcOrigin.VpcOriginEndpointConfig.HTTPPort -ne 8084 -or
   $origin.VpcOrigin.VpcOriginEndpointConfig.OriginProtocolPolicy -ne 'http-only') { throw 'Unexpected VPC origin configuration' }
$groups=Invoke-PairForgeAws @('ec2','describe-security-groups','--filters',"Name=vpc-id,Values=$($state.VpcId)")
$service=@($groups.SecurityGroups | Where-Object GroupName -eq 'CloudFront-VPCOrigins-Service-SG')
$app=@($groups.SecurityGroups | Where-Object GroupId -eq $state.AppSecurityGroupId)
if($service.Count -ne 1 -or $app.Count -ne 1 -or $app[0].Tags.Key -notcontains 'Project') { throw 'Expected one AWS service group and tagged application group' }
$source=$service[0].GroupId
if($state.CloudFrontSecurityGroupId -and $state.CloudFrontSecurityGroupId -ne $source) { throw 'CloudFront group identity changed' }
$rules=Invoke-PairForgeAws @('ec2','describe-security-group-rules','--filters',"Name=group-id,Values=$($state.AppSecurityGroupId)")
$matches=@($rules.SecurityGroupRules | Where-Object { -not $_.IsEgress -and $_.IpProtocol -eq 'tcp' -and $_.FromPort -eq 8084 })
if($matches.Count -gt 1 -or ($matches.Count -eq 1 -and ($matches[0].ReferencedGroupInfo.GroupId -ne $source -or $matches[0].ToPort -ne 8084))) { throw 'Unexpected public-origin ingress; refusing duplicate/broad rule' }
if(-not $matches.Count) {
    $request=@{GroupId=$state.AppSecurityGroupId;IpPermissions=@(@{IpProtocol='tcp';FromPort=8084;ToPort=8084;UserIdGroupPairs=@(@{GroupId=$source;Description='PairForge CloudFront VPC origin only'})})}
    $file=[IO.Path]::GetTempFileName()
    try {
        [IO.File]::WriteAllText($file,($request | ConvertTo-Json -Depth 10),[Text.UTF8Encoding]::new($false))
        [void](Invoke-PairForgeAws @('ec2','authorize-security-group-ingress','--cli-input-json',"file://$file"))
    } finally { Remove-Item -LiteralPath $file -Force }
}
$state.CloudFrontSecurityGroupId=$source
[IO.File]::WriteAllText("$StateFile.new",($state | ConvertTo-Json -Depth 12))
Move-Item -LiteralPath "$StateFile.new" -Destination $StateFile -Force
Write-Host "Application port 8084 accepts only AWS service-managed CloudFront group $source; other ingress unchanged."
