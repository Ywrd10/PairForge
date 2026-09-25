#Requires -Version 7.0
param([Parameter(Mandatory)][ValidateSet('Start','Stop')][string]$Mode,
      [switch]$CleanShutdownVerified,
      [string]$StateFile="$PSScriptRoot/../.tmp/m15-aws-state.json")
$ErrorActionPreference='Stop'
if($Mode -eq 'Stop' -and -not $CleanShutdownVerified) {
    throw 'Stop requires explicit confirmation that admission is closed, jobs drained, worker stopped and off-host backup verified'
}
if($Mode -eq 'Start' -and $CleanShutdownVerified) { throw 'CleanShutdownVerified applies only to Stop' }
. "$PSScriptRoot/aws-deployment-context.ps1"
Assert-PairForgeDeploymentIdentity
$state=Get-Content -Raw -LiteralPath $StateFile | ConvertFrom-Json
if($state.Account -ne '298984481596' -or -not $state.AppInstanceId -or -not $state.WorkerInstanceId) {
    throw 'Both recorded PairForge hosts are required'
}
$ids=@($state.AppInstanceId,$state.WorkerInstanceId)
if($ids[0] -eq $ids[1]) { throw 'App and worker instance IDs must differ' }
$records=@((Invoke-PairForgeAws @('ec2','describe-instances','--instance-ids',$ids[0],$ids[1])).Reservations | ForEach-Object { $_.Instances })
foreach($role in @('App','Worker')) {
    $record=@($records | Where-Object InstanceId -eq $state."${role}InstanceId")
    $type=if($role -eq 'App') {'t3a.medium'} else {'t3a.small'}
    if($record.Count -ne 1 -or $record[0].VpcId -ne $state.VpcId -or
            $record[0].SubnetId -ne $state."${role}SubnetId" -or $record[0].InstanceType -ne $type) {
        throw "$role host no longer matches the approved deployment"
    }
}
$expected=if($Mode -eq 'Start') {'stopped'} else {'running'}
if(@($records | Where-Object { $_.State.Name -ne $expected }).Count) {
    throw "Both hosts must be $expected before $Mode. Reconcile partial state manually."
}
if($Mode -eq 'Stop') {
    Write-Host 'Operator confirmed clean shutdown preconditions for the two exact hosts.'
}
$operation=if($Mode -eq 'Start') {'start-instances'} else {'stop-instances'}
[void](Invoke-PairForgeAws @('ec2',$operation,'--instance-ids',$ids[0],$ids[1]))
$waiter=if($Mode -eq 'Start') {'instance-running'} else {'instance-stopped'}
$aws=Get-PairForgeAwsExecutable
& $aws ec2 wait $waiter --instance-ids $ids[0] $ids[1] --profile pairforge --region us-east-1
if($LASTEXITCODE -ne 0) { throw "Hosts did not reach $waiter; inspect AWS state" }
Write-Host "Both approved PairForge hosts reached $waiter."
