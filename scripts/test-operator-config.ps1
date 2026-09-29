#Requires -Version 7.0
$ErrorActionPreference='Stop'
. "$PSScriptRoot/aws-deployment-context.ps1"
$script:CloudCalls=0
function Invoke-PairForgeAws { $script:CloudCalls++; throw 'Unexpected AWS call in offline configuration checks' }
$previous=$env:PAIRFORGE_OPERATOR_CONFIG
$directory=Join-Path ([IO.Path]::GetTempPath()) "pairforge-operator-test-$([Guid]::NewGuid().ToString('N'))"
[void][IO.Directory]::CreateDirectory($directory)
$env:PAIRFORGE_OPERATOR_CONFIG=Join-Path $directory 'config.json'
$valid=@{AccountId='111111111111';DeploymentArn='arn:aws:iam::111111111111:user/pairforge/pairforge-deployer';BackupBucket='pairforge-test-backups';ApprovedBenchmarkUsers=@('11111111-1111-4111-8111-111111111111','22222222-2222-4222-8222-222222222222')}
function Reject-Config {
    foreach($action in @({$null=Get-PairForgeOperatorConfig},{Assert-PairForgeDeploymentIdentity})) {
        $rejected=$false
        try { & $action } catch {
            if($_.Exception.Message -cne 'Valid private operator configuration required; see docs/OPERATOR_CONFIGURATION.md. Values are not logged.') { throw }
            $rejected=$true
        }
        if(-not $rejected){throw 'Unsafe operator configuration accepted'}
    }
}
try {
    Reject-Config
    [IO.File]::WriteAllText($env:PAIRFORGE_OPERATOR_CONFIG,'not JSON')
    Reject-Config
    foreach($change in @(@{AccountId='*'},@{DeploymentArn='arn:aws:iam::111111111111:root'},
        @{DeploymentArn='arn:aws:iam::222222222222:user/pairforge/pairforge-deployer'},@{BackupBucket=''},
        @{ApprovedBenchmarkUsers=@()},@{ApprovedBenchmarkUsers=@($valid.ApprovedBenchmarkUsers[0])},
        @{ApprovedBenchmarkUsers=@($valid.ApprovedBenchmarkUsers[0],$valid.ApprovedBenchmarkUsers[0])},
        @{ApprovedBenchmarkUsers=@($valid.ApprovedBenchmarkUsers)+'33333333-3333-4333-8333-333333333333'},
        @{ApprovedBenchmarkUsers=@($valid.ApprovedBenchmarkUsers[0],'invalid')})) {
        $config=$valid.Clone()
        foreach($entry in $change.GetEnumerator()){$config[$entry.Key]=$entry.Value}
        [IO.File]::WriteAllText($env:PAIRFORGE_OPERATOR_CONFIG,($config|ConvertTo-Json -Depth 5))
        Reject-Config
    }
    [IO.File]::WriteAllText($env:PAIRFORGE_OPERATOR_CONFIG,($valid|ConvertTo-Json -Depth 5))
    $config=Get-PairForgeOperatorConfig
    if($config.AccountId -cne $valid.AccountId){throw 'Valid configuration changed'}
    Assert-PairForgeBenchmarkIdentity @($valid.ApprovedBenchmarkUsers[1],$valid.ApprovedBenchmarkUsers[0]) $config.ApprovedBenchmarkUsers
    foreach($ids in @(@{Values=@($valid.ApprovedBenchmarkUsers[0],$valid.ApprovedBenchmarkUsers[0])},
        @{Values=@($valid.ApprovedBenchmarkUsers[0],'33333333-3333-4333-8333-333333333333')})) {
        $rejected=$false
        try { Assert-PairForgeBenchmarkIdentity $ids.Values $config.ApprovedBenchmarkUsers } catch { $rejected=$true }
        if(-not $rejected){throw 'Unapproved authenticated account accepted'}
    }
    $statePath=Join-Path $directory 'state.json'
    $state=@{Account=$valid.AccountId;Region='us-east-1';AppSubnetId='subnet-00000000000000001';AdminSecurityGroupId='sg-00000000000000001';WorkerInstanceId='i-00000000000000001'}
    foreach($change in @(@{Account='222222222222'},@{Region='us-west-2'},@{AppSubnetId='*'},@{AdminSecurityGroupId=$null},@{WorkerInstanceId='i-*'})) {
        $candidate=$state.Clone()
        foreach($entry in $change.GetEnumerator()){$candidate[$entry.Key]=$entry.Value}
        [IO.File]::WriteAllText($statePath,($candidate|ConvertTo-Json))
        $rejected=$false
        try { $null=Get-PairForgeDeploymentState -StateFile $statePath -RequiredKeys AppSubnetId,AdminSecurityGroupId,WorkerInstanceId } catch { $rejected=$true }
        if(-not $rejected){throw 'Unsafe repair resource state accepted'}
    }
    [IO.File]::WriteAllText($statePath,($state|ConvertTo-Json))
    $result=Get-PairForgeDeploymentState -StateFile $statePath -RequiredKeys WorkerInstanceId
    if($result.WorkerInstanceId -cne $state.WorkerInstanceId){throw 'Exact worker pin changed'}
    if($script:CloudCalls -ne 0){throw 'Configuration rejection reached AWS'}
    Write-Host 'Private operator configuration and exact two-account rejection checks passed without AWS/network calls.'
} finally {
    $env:PAIRFORGE_OPERATOR_CONFIG=$previous
    # Delete only this uniquely created fixture directory and its one file.
    if(Test-Path -LiteralPath (Join-Path $directory 'config.json')){Remove-Item -LiteralPath (Join-Path $directory 'config.json')}
    if(Test-Path -LiteralPath (Join-Path $directory 'state.json')){Remove-Item -LiteralPath (Join-Path $directory 'state.json')}
    Remove-Item -LiteralPath $directory
}
