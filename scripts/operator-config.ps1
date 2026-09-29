# Private operator metadata, never inferred from the active AWS caller or login.
function Get-PairForgeOperatorConfig {
    $path=if($env:PAIRFORGE_OPERATOR_CONFIG){$env:PAIRFORGE_OPERATOR_CONFIG}else{"$PSScriptRoot/../.tmp/operator-config.json"}
    try {
        $config=Get-Content -LiteralPath $path -Raw -ErrorAction Stop | ConvertFrom-Json -AsHashtable
        if($config -isnot [System.Collections.IDictionary] -or
           $config.AccountId -isnot [string] -or $config.AccountId -notmatch '^\d{12}$' -or
           $config.DeploymentArn -isnot [string] -or $config.DeploymentArn -cne "arn:aws:iam::$($config.AccountId):user/pairforge/pairforge-deployer" -or
           $config.BackupBucket -isnot [string] -or $config.BackupBucket -notmatch '^[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]$') { throw 'Invalid metadata' }
        Assert-PairForgeBenchmarkIdentity $config.ApprovedBenchmarkUsers $config.ApprovedBenchmarkUsers
        return $config
    } catch { throw 'Valid private operator configuration required; see docs/OPERATOR_CONFIGURATION.md. Values are not logged.' }
}
function Assert-PairForgeBenchmarkIdentity($Ids, $Approved) {
    $uuid='^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
    foreach($pair in @(@{Values=$Approved},@{Values=$Ids})) {
        if($pair.Values -isnot [array] -or $pair.Values.Count -ne 2 -or
           @($pair.Values | Select-Object -Unique).Count -ne 2 -or
           @($pair.Values | Where-Object { $_ -isnot [string] -or $_ -cnotmatch $uuid }).Count) {
            throw 'Only the two explicitly approved benchmark accounts may benchmark'
        }
    }
    if(@($Ids | Where-Object { $_ -cnotin $Approved }).Count) { throw 'Only the two explicitly approved benchmark accounts may benchmark' }
}
function Get-PairForgeDeploymentState {
    param([string]$StateFile="$PSScriptRoot/../.tmp/m15-aws-state.json",
          [ValidateSet('AppSubnetId','AdminSecurityGroupId','WorkerInstanceId')][string[]]$RequiredKeys=@())
    $config=Get-PairForgeOperatorConfig
    $state=Get-Content -LiteralPath $StateFile -Raw | ConvertFrom-Json -AsHashtable
    if($state.Account -cne $config.AccountId -or $state.Region -cne 'us-east-1') { throw 'Exact approved deployment state required for the one-time repair' }
    $patterns=@{AppSubnetId='^subnet-[0-9a-f]{8,17}$';AdminSecurityGroupId='^sg-[0-9a-f]{8,17}$';WorkerInstanceId='^i-[0-9a-f]{8,17}$'}
    foreach($key in $RequiredKeys) {
        if($state[$key] -isnot [string] -or $state[$key] -cnotmatch $patterns[$key]) { throw 'Exact approved deployment state required for the one-time repair' }
    }
    return $state
}
