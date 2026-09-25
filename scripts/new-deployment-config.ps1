#Requires -Version 7.0
param(
    [Parameter(Mandatory)][string]$Directory,
    [Parameter(Mandatory)][ValidatePattern('^(?=.{1,253}$)[a-z0-9]+(?:[a-z0-9-]*[a-z0-9])?(?:\.[a-z0-9]+(?:[a-z0-9-]*[a-z0-9])?)+$')][string]$PublicHost,
    [Parameter(Mandatory)][string]$DataPrivateIp
)
$ErrorActionPreference = 'Stop'
$address = [Net.IPAddress]::Parse($DataPrivateIp)
$b = $address.GetAddressBytes()
if ($b.Length -ne 4 -or -not ($b[0] -eq 10 -or ($b[0] -eq 172 -and $b[1] -ge 16 -and $b[1] -le 31) -or ($b[0] -eq 192 -and $b[1] -eq 168))) { throw 'Use the data host RFC1918 private IPv4' }
$Directory = [IO.Path]::GetFullPath($Directory)
if (Test-Path -LiteralPath $Directory) { throw 'Destination exists; refusing to overwrite deployment credentials' }
[void][IO.Directory]::CreateDirectory($Directory)
if ($IsWindows) {
    $owner = [Security.Principal.WindowsIdentity]::GetCurrent().User
    $acl = Get-Acl -LiteralPath $Directory
    $acl.SetAccessRuleProtection($true, $false)
    $acl.SetOwner($owner)
    $acl.AddAccessRule([Security.AccessControl.FileSystemAccessRule]::new($owner, 'FullControl', 'ContainerInherit,ObjectInherit', 'None', 'Allow'))
    Set-Acl -LiteralPath $Directory -AclObject $acl
} else { [IO.File]::SetUnixFileMode($Directory, [IO.UnixFileMode]::UserRead -bor [IO.UnixFileMode]::UserWrite -bor [IO.UnixFileMode]::UserExecute) }
function Write-PrivateFile([string]$Name, [string]$Text) {
    $path = Join-Path $Directory $Name
    [IO.File]::WriteAllText($path, $Text, [Text.UTF8Encoding]::new($false))
    if (-not $IsWindows) { [IO.File]::SetUnixFileMode($path, [IO.UnixFileMode]::UserRead -bor [IO.UnixFileMode]::UserWrite) }
}
function New-Secret { return [Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(32)).ToLowerInvariant() }
function Rabbit-Hash([string]$Password) {
    $salt = [Security.Cryptography.RandomNumberGenerator]::GetBytes(4)
    $hash = [Security.Cryptography.SHA256]::HashData([byte[]]($salt + [Text.Encoding]::UTF8.GetBytes($Password)))
    return [Convert]::ToBase64String([byte[]]($salt + $hash))
}
$apiDb = New-Secret; $workerDb = New-Secret; $migration = New-Secret
$apiBroker = New-Secret; $workerBroker = New-Secret
$templates = Join-Path (Split-Path $PSScriptRoot -Parent) 'infra/deploy'
$api = Get-Content (Join-Path $templates 'api.env.example') -Raw
$worker = Get-Content (Join-Path $templates 'worker.env.example') -Raw
$caddy = Get-Content (Join-Path $templates 'caddy.env.example') -Raw
$api = $api.Replace('https://pairforge.example.com', "https://$PublicHost")
foreach ($entry in @{DATABASE_PASSWORD=$apiDb; MIGRATION_PASSWORD=$migration; RABBITMQ_PASSWORD=$apiBroker; JWT_KEY_HEX=(New-Secret)}.GetEnumerator()) {
    $api = [regex]::Replace($api, "(?m)^$($entry.Key)=\r?$", "$($entry.Key)=$($entry.Value)")
}
foreach ($entry in @{DATABASE_PASSWORD=$workerDb; RABBITMQ_PASSWORD=$workerBroker}.GetEnumerator()) {
    $worker = [regex]::Replace($worker, "(?m)^$($entry.Key)=\r?$", "$($entry.Key)=$($entry.Value)")
}
Write-PrivateFile 'api.env' $api
Write-PrivateFile 'worker.env' $worker
$caddy = $caddy.Replace('pairforge.example.com', $PublicHost)
$caddy = [regex]::Replace($caddy, '(?m)^ORIGIN_BIND=\r?$', "ORIGIN_BIND=$DataPrivateIp")
$caddy = [regex]::Replace($caddy, '(?m)^ORIGIN_TOKEN=\r?$', "ORIGIN_TOKEN=$(New-Secret)")
Write-PrivateFile 'caddy.env' $caddy
Write-PrivateFile 'infra.env' "DATA_PRIVATE_IP=$DataPrivateIp`n"
Write-PrivateFile 'postgres-password' (New-Secret)
$definitions = @{
    vhosts = @(@{name='pairforge'})
    users = @(
        @{name='pairforge_api'; password_hash=(Rabbit-Hash $apiBroker); hashing_algorithm='rabbit_password_hashing_sha256'; tags=@()},
        @{name='pairforge_worker'; password_hash=(Rabbit-Hash $workerBroker); hashing_algorithm='rabbit_password_hashing_sha256'; tags=@()}
    )
    permissions = @(
        @{user='pairforge_api';vhost='pairforge';configure='^(pairforge\.execution|execution\.jobs|execution\.events)$';write='^(pairforge\.execution|execution\.jobs|execution\.events)$';read='^(pairforge\.execution|execution\.jobs|execution\.events)$'},
        @{user='pairforge_worker';vhost='pairforge';configure='^$';write='^pairforge\.execution$';read='^execution\.jobs$'}
    )
}
Write-PrivateFile 'rabbit-definitions.json' ($definitions | ConvertTo-Json -Depth 6)
Write-Host 'Generated separate deployment configuration and credentials. No values printed. Transfer only worker.env and the public CA certificate to the worker host.'
