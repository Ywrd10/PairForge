#Requires -Version 7.0
$ErrorActionPreference='Stop'
$root=Join-Path (Split-Path $PSScriptRoot -Parent) '.tmp'
$directory=Join-Path $root ('deployment-config-test-'+[Guid]::NewGuid().ToString('N'))
function Require($Condition, $Message) { if(-not $Condition) { throw $Message } }
function Read-Environment($Name) {
    $result=@{}
    foreach($line in Get-Content -LiteralPath (Join-Path $directory $Name)) {
        if($line -match '^([A-Z_]+)=(.*)$') { $result[$Matches[1]]=$Matches[2] }
    }
    return $result
}
try {
    # Production provisioning must reference SQL shipped in the repository release.
    $repository=Split-Path $PSScriptRoot -Parent
    foreach($helper in @('provision-app.sh','provision-worker-production.sh')) {
        $source=Get-Content -LiteralPath (Join-Path $repository "infra/deploy/$helper") -Raw
        $inputSql=[regex]::Match($source, '< /opt/pairforge/current/([^\s]+\.sql)')
        Require $inputSql.Success "Missing SQL input in $helper"
        Require (Test-Path -LiteralPath (Join-Path $repository $inputSql.Groups[1].Value) -PathType Leaf) "Release SQL input missing for $helper"
    }
    & "$PSScriptRoot/new-deployment-config.ps1" -Directory $directory -PublicHost d123.example.cloudfront.net -DataPrivateIp 10.42.1.10
    $api=Read-Environment 'api.env'; $worker=Read-Environment 'worker.env'; $caddy=Read-Environment 'caddy.env'
    $secrets=@($api.DATABASE_PASSWORD,$api.MIGRATION_PASSWORD,$api.RABBITMQ_PASSWORD,$api.JWT_KEY_HEX,$worker.DATABASE_PASSWORD,$worker.RABBITMQ_PASSWORD,$caddy.ORIGIN_TOKEN,(Get-Content -LiteralPath (Join-Path $directory 'postgres-password') -Raw))
    Require (($secrets | Select-Object -Unique).Count -eq 8) 'Credentials must be distinct'
    foreach($secret in $secrets) { Require ($secret -cmatch '^[a-f0-9]{64}$') 'Credential is missing or malformed' }
    Require ($api.EXECUTION_APPROVED_USERS -eq '') 'New deployment must deny all execution'
    Require ($api.PUBLIC_ORIGIN -eq 'https://d123.example.cloudfront.net') 'Wrong public origin'
    Require ($caddy.ORIGIN_BIND -eq '10.42.1.10') 'Origin must bind to the private address'
    Require (-not $worker.ContainsKey('JWT_KEY_HEX') -and -not $worker.ContainsKey('MIGRATION_PASSWORD')) 'Worker received application credentials'
    $definitions=Get-Content -LiteralPath (Join-Path $directory 'rabbit-definitions.json') -Raw | ConvertFrom-Json
    Require ($definitions.users.Count -eq 2) 'Only the API and worker broker users are expected'
    foreach($user in $definitions.users) {
        $password=if($user.name -eq 'pairforge_api') {$api.RABBITMQ_PASSWORD} else {$worker.RABBITMQ_PASSWORD}
        $encoded=[Convert]::FromBase64String($user.password_hash)
        $expected=[Security.Cryptography.SHA256]::HashData([byte[]]($encoded[0..3]+[Text.Encoding]::UTF8.GetBytes($password)))
        Require ([Convert]::ToHexString($encoded[4..35]) -eq [Convert]::ToHexString($expected)) 'Broker password hash does not match'
        Require ($user.tags.Count -eq 0) 'Broker user must not be an administrator'
    }
    $before=Get-FileHash -LiteralPath (Join-Path $directory 'api.env')
    $refused=$false
    try { & "$PSScriptRoot/new-deployment-config.ps1" -Directory $directory -PublicHost example.com -DataPrivateIp 10.42.1.10 } catch { $refused=$true }
    Require $refused 'Existing credentials must not be overwritten'
    Require ((Get-FileHash -LiteralPath (Join-Path $directory 'api.env')).Hash -eq $before.Hash) 'Credentials changed on rejected overwrite'
    foreach($ip in @('8.8.8.8','127.0.0.1','::1','172.32.0.1')) {
        $refused=$false
        try { & "$PSScriptRoot/new-deployment-config.ps1" -Directory ($directory+'-invalid') -PublicHost example.com -DataPrivateIp $ip } catch { $refused=$true }
        Require $refused 'A non-private deployment IP was accepted'
    }
    Write-Host 'Deployment credential checks passed: isolation, unique secrets, broker hashes, deny-all execution, private bind, overwrite protection and invalid IP rejection.'
} finally {
    # Known fixture files and an empty directory only, no recursive deletion.
    foreach($name in @('api.env','worker.env','caddy.env','infra.env','postgres-password','rabbit-definitions.json')) {
        $path=Join-Path $directory $name
        if(Test-Path -LiteralPath $path) { Remove-Item -LiteralPath $path -Force }
    }
    if(Test-Path -LiteralPath $directory) { Remove-Item -LiteralPath $directory -Force }
}
