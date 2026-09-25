#Requires -Version 7.4
# Local disposable PostgreSQL fixture. No AWS calls or application credentials.
$ErrorActionPreference='Stop'
$repository=Split-Path $PSScriptRoot -Parent
$id='pairforge-m15-'+[Guid]::NewGuid().ToString('N')
$directory=Join-Path $repository ".tmp/$id"
[void][IO.Directory]::CreateDirectory($directory)
$bootstrap=[Guid]::NewGuid().ToString('N'); $api=[Guid]::NewGuid().ToString('N'); $migrator=[Guid]::NewGuid().ToString('N')
$networkCreated=$false; $containerCreated=$false
$runtimeImage='mcr.microsoft.com/powershell@sha256:62300a213a9293916333df2b014cd3a8f22fb0b0b65f2bb446aaf436bcf8c868'
function Docker([string[]]$Arguments) {
    $output=& $script:dockerExecutable @Arguments
    if($LASTEXITCODE -ne 0) { throw "Docker fixture operation failed: $($Arguments[0])" }
    return $output
}
$script:dockerExecutable=(Get-Command docker -CommandType Application | Select-Object -First 1).Source
try {
    [void](Docker @('run','--rm','--network','none','--mount',"type=bind,source=$PSScriptRoot,target=/scripts,readonly",'--mount',"type=bind,source=$directory,target=/fixture",$runtimeImage,'pwsh','-NoProfile','-File','/scripts/new-deployment-pki.ps1','-Directory','/fixture/pki'))
    [void](Docker @('network','create',$id)); $networkCreated=$true
    $command='chown postgres:postgres /tls/server.key; chmod 600 /tls/server.key; exec docker-entrypoint.sh postgres -c ssl=on -c ssl_cert_file=/tls/server.crt -c ssl_key_file=/tls/server.key'
    [void](Docker @('create','--name',$id,'--network',$id,'--network-alias','db.pairforge.internal','-e',"POSTGRES_PASSWORD=$bootstrap",'-e','POSTGRES_USER=pairforge_bootstrap','-e','POSTGRES_DB=pairforge','--entrypoint','bash','postgres:17.11-bookworm','-c',$command)); $containerCreated=$true
    [void](Docker @('cp',"$directory/pki/postgres","${id}:/tls"))
    [void](Docker @('start',$id))
    $ready=$false
    for($attempt=0;$attempt -lt 30;$attempt++) {
        & $script:dockerExecutable exec $id pg_isready -h db.pairforge.internal -U pairforge_bootstrap *> $null
        if($LASTEXITCODE -eq 0) { $ready=$true; break }
        Start-Sleep -Seconds 1
    }
    if(-not $ready) { throw 'PostgreSQL did not become ready' }
    [void](Docker @('cp',"$repository/infra/deploy/provision-app.sql","${id}:/tmp/provision.sql"))
    [void](Docker @('exec','-e',"API_DB_PASSWORD=$api",'-e',"MIGRATION_PASSWORD=$migrator",$id,'psql','-U','pairforge_bootstrap','-d','pairforge','-f','/tmp/provision.sql'))
    $tls=@('exec','-e',"PGPASSWORD=$api",'-e','PGSSLMODE=verify-full','-e','PGSSLROOTCERT=/tls/ca.crt',$id,'psql','-h','db.pairforge.internal','-U','pairforge_api','-d','pairforge','-v','ON_ERROR_STOP=1','-Atc')
    $encrypted=Docker ($tls+@('SELECT ssl FROM pg_stat_ssl WHERE pid=pg_backend_pid()'))
    if($encrypted.Trim() -ne 't') { throw 'Verified TLS was not used' }
    & $script:dockerExecutable @($tls+@('CREATE TABLE forbidden(id int)')) *> $null
    if($LASTEXITCODE -eq 0) { throw 'Runtime API must not create tables' }
    $badTls=$tls.Clone(); $badTls[($badTls.IndexOf('db.pairforge.internal'))]='127.0.0.1'
    & $script:dockerExecutable @($badTls+@('SELECT 1')) *> $null
    if($LASTEXITCODE -eq 0) { throw 'Hostname mismatch must fail TLS' }
    [void](Docker @('exec',$id,'psql','-U','pairforge_bootstrap','-d','pairforge','-v','ON_ERROR_STOP=1','-c','SET ROLE pairforge_migrator; CREATE TABLE backup_fixture(id int PRIMARY KEY, value text); INSERT INTO backup_fixture VALUES (1, ''retained'');'))
    & $script:dockerExecutable exec $id pg_dump -U pairforge_bootstrap -d pairforge -Fc --no-owner --no-acl > (Join-Path $directory 'original.dump')
    if($LASTEXITCODE -ne 0) { throw 'pg_dump failed' }
    $crypto='$ErrorActionPreference="Stop"; & /usr/bin/openssl cms -encrypt -binary -aes-256-gcm -in /fixture/original.dump -out /fixture/backup.cms -outform DER /fixture/pki/backup.crt; if($LASTEXITCODE -ne 0){throw "encrypt"}; & /usr/bin/openssl cms -decrypt -binary -inform DER -in /fixture/backup.cms -recip /fixture/pki/backup.crt -inkey /fixture/pki/backup.key -out /fixture/restored.dump; if($LASTEXITCODE -ne 0){throw "decrypt"}'
    [void](Docker @('run','--rm','--network','none','--mount',"type=bind,source=$directory,target=/fixture",$runtimeImage,'pwsh','-NoProfile','-Command',$crypto))
    if((Get-FileHash "$directory/original.dump").Hash -ne (Get-FileHash "$directory/restored.dump").Hash) { throw 'Encrypted backup changed bytes' }
    [void](Docker @('exec',$id,'createdb','-U','pairforge_bootstrap','-O','pairforge_migrator','pairforge_restore_test'))
    [void](Docker @('cp',"$directory/restored.dump","${id}:/tmp/restored.dump"))
    [void](Docker @('exec',$id,'pg_restore','-U','pairforge_bootstrap','--role=pairforge_migrator','-d','pairforge_restore_test','--no-owner','--no-acl','--exit-on-error','/tmp/restored.dump'))
    $restored=Docker @('exec',$id,'psql','-U','pairforge_bootstrap','-d','pairforge_restore_test','-Atc','SELECT value FROM backup_fixture WHERE id=1')
    if($restored.Trim() -ne 'retained') { throw 'Restored data mismatch' }
    Write-Host 'PASS private certificate generation, PostgreSQL verified TLS, hostname rejection, API DDL denial, encrypted pg_dump and isolated pg_restore.'
} finally {
    if($containerCreated) { [void](Docker @('rm','--force','--volumes',$id)) }
    if($networkCreated) { [void](Docker @('network','rm',$id)) }
    # Resolve and verify the generated fixture path before recursive cleanup.
    $resolved=[IO.Path]::GetFullPath($directory)
    $expected=[IO.Path]::GetFullPath((Join-Path $repository '.tmp'))+[IO.Path]::DirectorySeparatorChar
    if(-not $resolved.StartsWith($expected,[StringComparison]::OrdinalIgnoreCase) -or (Split-Path $resolved -Leaf) -ne $id) { throw 'Unsafe fixture cleanup path' }
    Remove-Item -LiteralPath $resolved -Recurse -Force
}
