# Shared by deployment entry points. Never use ambient/default profile selection.
$ErrorActionPreference='Stop'
. "$PSScriptRoot/operator-config.ps1"
function Get-PairForgeAwsExecutable {
    $command=Get-Command aws -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1
    if($command) { return $command.Source }
    if($IsWindows) {
        foreach($path in @((Join-Path $env:LOCALAPPDATA 'Programs/Amazon/AWSCLIV2/aws.exe'),(Join-Path $env:ProgramFiles 'Amazon/AWSCLIV2/aws.exe'))) {
            if(Test-Path -LiteralPath $path) { return $path }
        }
    }
    throw 'AWS CLI unavailable; configure the pairforge profile, never use another profile'
}
function Invoke-PairForgeAws([string[]]$Arguments) {
    $executable=Get-PairForgeAwsExecutable
    $output=& $executable @Arguments --profile pairforge --region us-east-1 --output json --no-cli-pager
    if($LASTEXITCODE -ne 0) { throw "AWS operation failed: $($Arguments[0]) $($Arguments[1])" }
    if($output) { return ($output | ConvertFrom-Json) }
}
function Assert-PairForgeDeploymentIdentity {
    $config=Get-PairForgeOperatorConfig
    $identity=Invoke-PairForgeAws @('sts','get-caller-identity')
    if($identity.Account -cne $config.AccountId -or $identity.Arn -cne $config.DeploymentArn) {
        throw 'Deployment requires the exact configured non-root pairforge-deployer identity in the approved account'
    }
    $executable=Get-PairForgeAwsExecutable
    $region=& $executable configure get region --profile pairforge
    if($LASTEXITCODE -ne 0 -or $region.Trim() -ne 'us-east-1') { throw 'pairforge profile region must be us-east-1' }
    Write-Host "Verified profile pairforge, account $($identity.Account), identity $($identity.Arn), region us-east-1"
}
function Assert-PairForgeAdministrationReady($State) {
    $result=Invoke-PairForgeAws @('ec2','describe-instance-connect-endpoints','--instance-connect-endpoint-ids',$State.AdminEndpointId)
    $endpoints=@($result.InstanceConnectEndpoints)
    if($endpoints.Count -ne 1 -or $endpoints[0].InstanceConnectEndpointId -ne $State.AdminEndpointId -or
       $endpoints[0].State -ne 'create-complete' -or $endpoints[0].SubnetId -ne $State.AppSubnetId -or
       @($endpoints[0].SecurityGroupIds).Count -ne 1 -or $endpoints[0].SecurityGroupIds[0] -ne $State.AdminSecurityGroupId) {
        throw 'Private administration endpoint must finish creation in the approved subnet/security group before launching any host'
    }
}
