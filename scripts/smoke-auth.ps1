. (Join-Path $PSScriptRoot 'environment.ps1')
Add-Type -AssemblyName System.Net.Http
Import-PairForgeEnvironment
$apiPort = if ($env:API_PORT) { $env:API_PORT } else { '8080' }
$handler = New-Object System.Net.Http.HttpClientHandler
$handler.UseProxy = $false
$client = New-Object System.Net.Http.HttpClient($handler)
$client.Timeout = [TimeSpan]::FromSeconds(15)
$baseUrl = "http://127.0.0.1:$apiPort/api/auth"
try {
    $credentials = @{ email = ('smoke-' + [guid]::NewGuid().ToString('N') + '@example.com'); password = [guid]::NewGuid().ToString('N') }
    $json = $credentials | ConvertTo-Json -Compress
    foreach ($operation in @('register', 'login')) {
        $watch = [Diagnostics.Stopwatch]::StartNew()
        $content = New-Object System.Net.Http.StringContent($json, [Text.Encoding]::UTF8, 'application/json')
        $response = $client.PostAsync("$baseUrl/$operation", $content).GetAwaiter().GetResult()
        try {
            $expected = if ($operation -eq 'register') { 201 } else { 200 }
            if ([int]$response.StatusCode -ne $expected) { throw "Auth $operation expected $expected, got $([int]$response.StatusCode)." }
            if ($operation -eq 'login') { $token = ($response.Content.ReadAsStringAsync().GetAwaiter().GetResult() | ConvertFrom-Json).accessToken }
            $watch.Stop()
            Write-Host "Auth $operation HTTP round trip: $($watch.ElapsedMilliseconds) ms (local smoke sample)."
        } finally { $response.Dispose(); $content.Dispose() }
    }
    $client.DefaultRequestHeaders.Authorization = New-Object System.Net.Http.Headers.AuthenticationHeaderValue('Bearer', $token)
    $response = $client.GetAsync("$baseUrl/me").GetAwaiter().GetResult()
    try {
        if ([int]$response.StatusCode -ne 200) { throw 'Protected identity request failed.' }
        $user = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult() | ConvertFrom-Json
        if ($user.email -ne $credentials.email) { throw 'Protected identity did not match the registered user.' }
    } finally { $response.Dispose() }
    $client.DefaultRequestHeaders.Authorization = $null
    $response = $client.GetAsync("$baseUrl/me").GetAwaiter().GetResult()
    try { if ([int]$response.StatusCode -ne 401) { throw 'Unauthenticated identity request was not denied.' } }
    finally { $response.Dispose() }
    Write-Host 'Authentication smoke passed: register, login, protected identity, unauthenticated denial.'
    Write-Host 'One uniquely named smoke account remains in the local database.'
} finally { $client.Dispose() }
