#Requires -Version 7.0
$ErrorActionPreference='Stop'
. "$PSScriptRoot/aws-deployment-context.ps1"
$script:Calls=0
function Invoke-PairForgeAws([string[]]$Arguments) {
    $script:Calls++
    return @{Account=$script:Account;Arn=$script:Arn}
}
# These identities must be rejected before any other CLI operation is attempted.
foreach($case in @(
    @('298984481596','arn:aws:iam::298984481596:root'),
    @('111111111111','arn:aws:iam::111111111111:user/pairforge/pairforge-deployer'),
    @('298984481596','arn:aws:iam::298984481596:user/unrelated')
)) {
    $script:Account=$case[0]; $script:Arn=$case[1]; $rejected=$false
    try { Assert-PairForgeDeploymentIdentity } catch {
        if($_.Exception.Message -notlike 'Deployment requires*') { throw }
        $rejected=$true
    }
    if(-not $rejected) { throw 'Unauthorized deployment identity was accepted' }
}
if($script:Calls -ne 3) { throw 'Unexpected identity lookup count' }
Write-Host 'Deployment identity rejection checks passed: root, wrong account and unrelated user.'
$state=@{AdminEndpointId='eice-approved';AppSubnetId='subnet-approved';AdminSecurityGroupId='sg-approved'}
function Invoke-PairForgeAws([string[]]$Arguments) {
    if($Arguments[1] -ne 'describe-instance-connect-endpoints') { throw 'Unexpected mutation or lookup during readiness validation' }
    return @{InstanceConnectEndpoints=@($script:Endpoint)}
}
$valid=@{InstanceConnectEndpointId='eice-approved';State='create-complete';SubnetId='subnet-approved';SecurityGroupIds=@('sg-approved')}
foreach($change in @(@{State='create-in-progress'},@{State='create-failed'},@{SubnetId='subnet-other'},@{InstanceConnectEndpointId='eice-other'},@{SecurityGroupIds=@('sg-approved','sg-other')})) {
    $script:Endpoint=$valid.Clone()
    foreach($entry in $change.GetEnumerator()) { $script:Endpoint[$entry.Key]=$entry.Value }
    $rejected=$false
    try { Assert-PairForgeAdministrationReady $state } catch {
        if($_.Exception.Message -notlike 'Private administration endpoint must*') { throw }
        $rejected=$true
    }
    if(-not $rejected) { throw 'Unsafe or incomplete administration endpoint accepted' }
}
$script:Endpoint=$valid
Assert-PairForgeAdministrationReady $state
Write-Host 'Six administration readiness checks passed: pending/failed creation, wrong endpoint/subnet/group set rejected; exact ready endpoint accepted.'
