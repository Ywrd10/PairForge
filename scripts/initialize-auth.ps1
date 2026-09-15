Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$envPath = Join-Path (Split-Path -Parent $PSScriptRoot) '.env'
if (-not (Test-Path -LiteralPath $envPath)) { throw 'Initialize .env with scripts/dev.ps1 -Service infrastructure -Initialize first.' }
$contents = [IO.File]::ReadAllText($envPath)
if ($contents -match '(?m)^JWT_KEY_HEX=[^\r\n]+') { throw 'JWT_KEY_HEX already exists; it will not be overwritten.' }
$generator = [Security.Cryptography.RandomNumberGenerator]::Create()
try {
    $bytes = New-Object byte[] 32
    $generator.GetBytes($bytes)
    $key = ([BitConverter]::ToString($bytes)).Replace('-', '').ToLowerInvariant()
} finally { $generator.Dispose() }
if ($contents -match '(?m)^JWT_KEY_HEX=\r?$') {
    $contents = [regex]::Replace($contents, '(?m)^JWT_KEY_HEX=\r?$', "JWT_KEY_HEX=$key")
} else { $contents = $contents.TrimEnd() + "`nJWT_KEY_HEX=$key`n" }
[IO.File]::WriteAllText($envPath, $contents)
Write-Host 'Added API signing key to ignored .env; existing credentials preserved.'
