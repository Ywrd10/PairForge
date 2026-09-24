param([switch]$Frontend)
$ErrorActionPreference = 'Stop'

function Invoke-CiTool {
    param([string]$Name, [string[]]$Arguments)
    if (-not (Get-Command $Name -ErrorAction SilentlyContinue)) { throw "Required tool missing: $Name" }
    $result = & $Name @Arguments 2>&1
    if ($LASTEXITCODE -ne 0) { throw "Required tool failed: $Name (exit $LASTEXITCODE)" }
    return ($result -join "`n")
}

function Assert-CiPrerequisites {
    param([switch]$Frontend)
    $java = Invoke-CiTool java @('-version')
    if ($java -notmatch 'version "21\.') { throw 'Java 21 is required' }
    $docker = Invoke-CiTool docker @('info', '--format', '{{json .}}') | ConvertFrom-Json
    if ($docker.OSType -ne 'linux' -or $docker.CgroupVersion -ne '2' -or
        ($docker.SecurityOptions -join ',') -notmatch 'name=seccomp') {
        throw 'Linux Docker with cgroup v2 and seccomp is required'
    }
    # Print only an allowlist, never Docker environment/configuration or credentials.
    Write-Host ($java.Split("`n")[0])
    Write-Host "Docker $($docker.ServerVersion); kernel $($docker.KernelVersion); cgroup v2; seccomp enabled"
    if ($Frontend) {
        $node = Invoke-CiTool node @('--version')
        if ($node -notmatch '^v24\.') { throw 'Node 24 is required' }
        $npm = Invoke-CiTool npm @('--version')
        Write-Host "Node $node; npm $npm"
    }
}

# Dot-sourcing exposes the same functions for fault-injection tests.
if ($MyInvocation.InvocationName -ne '.') { Assert-CiPrerequisites -Frontend:$Frontend }
