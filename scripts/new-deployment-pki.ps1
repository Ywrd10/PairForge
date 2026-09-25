#Requires -Version 7.0
param([Parameter(Mandatory)][string]$Directory)
$ErrorActionPreference='Stop'
if($IsWindows) { throw 'Generate deployment PKI on an administrator-controlled Linux system with OpenSSL' }
$openSslExecutable=(Get-Command openssl -CommandType Application -ErrorAction Stop | Select-Object -First 1).Source
$Directory=[IO.Path]::GetFullPath($Directory)
if(Test-Path -LiteralPath $Directory) { throw 'Destination exists; refusing to replace CA or keys' }
[void][IO.Directory]::CreateDirectory($Directory)
[IO.File]::SetUnixFileMode($Directory, [IO.UnixFileMode]::UserRead -bor [IO.UnixFileMode]::UserWrite -bor [IO.UnixFileMode]::UserExecute)
function OpenSsl([string[]]$Arguments) {
    & $openSslExecutable @Arguments 2>$null
    if($LASTEXITCODE -ne 0) { throw "OpenSSL operation failed: $($Arguments[0])" }
}
Push-Location $Directory
try {
    OpenSsl @('req','-x509','-newkey','rsa:3072','-nodes','-sha256','-days','365','-subj','/CN=PairForge private demo CA','-addext','basicConstraints=critical,CA:TRUE','-addext','keyUsage=critical,keyCertSign,cRLSign','-keyout','ca.key','-out','ca.crt')
    foreach($service in @('postgres','rabbitmq')) {
        [void][IO.Directory]::CreateDirectory((Join-Path $Directory $service))
        $name=if($service -eq 'postgres') {'db.pairforge.internal'} else {'rabbit.pairforge.internal'}
        $extension="basicConstraints=critical,CA:FALSE`nkeyUsage=critical,digitalSignature,keyEncipherment`nextendedKeyUsage=serverAuth`nsubjectAltName=DNS:$name`n"
        [IO.File]::WriteAllText((Join-Path $Directory "$service/server.ext"), $extension)
        OpenSsl @('req','-new','-newkey','rsa:3072','-nodes','-sha256','-subj',"/CN=$name",'-keyout',"$service/server.key",'-out',"$service/server.csr")
        OpenSsl @('x509','-req','-in',"$service/server.csr",'-CA','ca.crt','-CAkey','ca.key','-CAcreateserial','-days','90','-sha256','-extfile',"$service/server.ext",'-out',"$service/server.crt")
        Copy-Item -LiteralPath 'ca.crt' -Destination "$service/ca.crt"
        OpenSsl @('verify','-CAfile','ca.crt','-verify_hostname',$name,"$service/server.crt")
    }
    # The backup decryption key remains offline; only its public certificate goes to the app.
    OpenSsl @('req','-x509','-newkey','rsa:3072','-nodes','-sha256','-days','365','-subj','/CN=PairForge backup recipient','-keyout','backup.key','-out','backup.crt')
    foreach($key in Get-ChildItem -LiteralPath $Directory -Recurse -Filter '*.key') {
        [IO.File]::SetUnixFileMode($key.FullName, [IO.UnixFileMode]::UserRead -bor [IO.UnixFileMode]::UserWrite)
    }
} finally { Pop-Location }
Write-Host 'Private CA and backup recipient created. Keep ca.key and backup.key offline. Worker receives only ca.crt; never copy this entire directory to a host.'
