#Requires -Version 7.0
param([Parameter(Mandatory)][ValidatePattern('^pairforge-\d{12}-us-east-1-backups$')][string]$Bucket,
      [string]$RecipientCertificate='/etc/pairforge/tls/backup.crt',
      [string]$ComposeFile='/opt/pairforge/current/infra/deploy/compose.yaml')
$ErrorActionPreference='Stop'
if($IsWindows) { throw 'Run this helper on the Linux application host' }
$temp=Join-Path '/var/lib/pairforge-backups' ([Guid]::NewGuid().ToString('N'))
[void][IO.Directory]::CreateDirectory($temp)
[IO.File]::SetUnixFileMode($temp,[IO.UnixFileMode]::UserRead -bor [IO.UnixFileMode]::UserWrite -bor [IO.UnixFileMode]::UserExecute)
$dump=Join-Path $temp 'database.dump'
$encrypted=Join-Path $temp 'database.dump.cms'
try {
    # PowerShell 7.4+ preserves native binary stdout redirected to a file.
    if($PSVersionTable.PSVersion -lt [Version]'7.4') { throw 'Binary pg_dump requires PowerShell 7.4+' }
    & docker compose --env-file /etc/pairforge/infra.env -f $ComposeFile exec -T postgres pg_dump -U pairforge_bootstrap -d pairforge -Fc --no-owner --no-acl > $dump
    if($LASTEXITCODE -ne 0 -or (Get-Item -LiteralPath $dump).Length -eq 0) { throw 'Database dump failed' }
    & openssl cms -encrypt -binary -aes-256-gcm -in $dump -out $encrypted -outform DER $RecipientCertificate
    if($LASTEXITCODE -ne 0) { throw 'Backup encryption failed' }
    $key='postgres/'+[DateTime]::UtcNow.ToString('yyyy-MM-dd/HHmmss')+'-'+[Guid]::NewGuid().ToString('N')+'.dump.cms'
    $hash=(Get-FileHash -LiteralPath $encrypted -Algorithm SHA256).Hash.ToLowerInvariant()
    $expectedOwner=$Bucket.Split('-')[1]
    & aws s3api put-object --region us-east-1 --bucket $Bucket --expected-bucket-owner $expectedOwner --key $key --body $encrypted --server-side-encryption AES256 --checksum-algorithm SHA256 --metadata "sha256=$hash" --no-cli-pager --query '{Checksum:ChecksumSHA256}' --output json
    if($LASTEXITCODE -ne 0) { throw 'Off-host backup upload failed; shutdown must not claim success' }
    Write-Host "Uploaded encrypted backup: s3://$Bucket/$key"
} finally {
    # Exact files and directory only; no computed recursive deletion.
    foreach($file in @($dump,$encrypted)) { if(Test-Path -LiteralPath $file) { Remove-Item -LiteralPath $file -Force } }
    if(Test-Path -LiteralPath $temp) { Remove-Item -LiteralPath $temp -Force }
}
