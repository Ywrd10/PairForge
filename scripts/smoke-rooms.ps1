. (Join-Path $PSScriptRoot 'environment.ps1')
Add-Type -AssemblyName System.Net.Http
Import-PairForgeEnvironment
$apiPort = if ($env:API_PORT) { $env:API_PORT } else { '8080' }
$handler = New-Object System.Net.Http.HttpClientHandler
$handler.UseProxy = $false
$client = New-Object System.Net.Http.HttpClient($handler)
$client.Timeout = [TimeSpan]::FromSeconds(15)
$baseUrl = "http://127.0.0.1:$apiPort"

function Send-RoomSmokeRequest {
    param([string]$Method, [string]$Path, [int]$Expected, [object]$Body = $null, [string]$Token = '')
    $request = New-Object System.Net.Http.HttpRequestMessage((New-Object System.Net.Http.HttpMethod($Method)), "$baseUrl$Path")
    try {
        if ($Token) { $request.Headers.Authorization = New-Object System.Net.Http.Headers.AuthenticationHeaderValue('Bearer', $Token) }
        if ($null -ne $Body) {
            $request.Content = New-Object System.Net.Http.StringContent(($Body | ConvertTo-Json -Compress), [Text.Encoding]::UTF8, 'application/json')
        }
        $response = $client.SendAsync($request).GetAwaiter().GetResult()
        try {
            if ([int]$response.StatusCode -ne $Expected) { throw "Room smoke $Method $Path expected $Expected, got $([int]$response.StatusCode)." }
            return ($response.Content.ReadAsStringAsync().GetAwaiter().GetResult() | ConvertFrom-Json)
        } finally { $response.Dispose() }
    } finally { $request.Dispose() }
}

try {
    $actors = @()
    for ($i = 0; $i -lt 3; $i++) {
        $credentials = @{ email = ('room-smoke-' + [guid]::NewGuid().ToString('N') + '@example.com'); password = [guid]::NewGuid().ToString('N') }
        $user = Send-RoomSmokeRequest POST '/api/auth/register' 201 $credentials
        $login = Send-RoomSmokeRequest POST '/api/auth/login' 200 $credentials
        $actors += @{ Id = $user.id; Token = $login.accessToken }
    }
    $created = Send-RoomSmokeRequest POST '/api/rooms' 201 @{ name = 'Room smoke'; language = 'JAVA' } $actors[0].Token
    if ($created.room.ownerId -ne $actors[0].Id -or -not $created.invitationToken) { throw 'Room creation contract failed.' }
    $path = '/api/rooms/' + $created.room.id
    Send-RoomSmokeRequest GET $path 200 $null $actors[0].Token | Out-Null
    Send-RoomSmokeRequest GET $path 401 | Out-Null
    Send-RoomSmokeRequest GET $path 404 $null $actors[1].Token | Out-Null
    Send-RoomSmokeRequest POST "$path/join" 404 @{ invitationToken = ('x' * 43) } $actors[1].Token | Out-Null
    foreach ($actor in $actors) {
        Send-RoomSmokeRequest POST "$path/join" 200 @{ invitationToken = $created.invitationToken } $actor.Token | Out-Null
        Send-RoomSmokeRequest POST "$path/join" 200 @{ invitationToken = $created.invitationToken } $actor.Token | Out-Null
        $room = Send-RoomSmokeRequest GET $path 200 $null $actor.Token
        $list = Send-RoomSmokeRequest GET '/api/rooms' 200 $null $actor.Token
        if ($room.id -ne $created.room.id -or @($list.items | Where-Object { $_.id -eq $room.id }).Count -ne 1) { throw 'Room membership/read/list contract failed.' }
        if ($room.PSObject.Properties.Name -contains 'invitationToken' -or $room.PSObject.Properties.Name -contains 'invitationTokenHash') { throw 'Room response disclosed invitation data.' }
    }
    Write-Host 'Room smoke passed: create, automatic owner access, three members, repeated joins, list/read, and denied access.'
    Write-Host 'Three uniquely named smoke accounts and one room remain in the local database. No credentials or invitations were printed.'
} finally { $client.Dispose() }
