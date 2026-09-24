$ErrorActionPreference = 'Stop'
. "$PSScriptRoot/check-ci-prerequisites.ps1"
. "$PSScriptRoot/check-ci-reports.ps1"
$checks = 0
function Assert-Rejected {
    param([scriptblock]$Action)
    $rejected = $false
    try { & $Action | Out-Null } catch { $rejected = $true }
    if (-not $rejected) { throw 'Expected CI gate rejection' }
    $script:checks++
}

# Replace only the external command boundary; exercise the actual prerequisite gate.
$actualTool = ${function:Invoke-CiTool}
$script:fault = ''
function Invoke-CiTool {
    param([string]$Name, [string[]]$Arguments)
    if ($script:fault -eq "missing-$Name") { throw 'Tool unavailable' }
    switch ($Name) {
        java { if ($script:fault -eq 'java-version') { return 'version "17.0.1"' }; return 'version "21.0.12"' }
        node { if ($script:fault -eq 'node-version') { return 'v22.0.0' }; return 'v24.19.0' }
        npm { return '11.0.0' }
        docker {
            if ($script:fault -eq 'docker-down') { throw 'Daemon unavailable' }
            if ($script:fault -eq 'docker-json') { return 'not json' }
            $info = @{ OSType = 'linux'; CgroupVersion = '2'; SecurityOptions = @('name=seccomp,profile=builtin'); ServerVersion = 'test'; KernelVersion = 'test' }
            if ($script:fault -eq 'docker-os') { $info.OSType = 'windows' }
            if ($script:fault -eq 'docker-cgroup') { $info.CgroupVersion = '1' }
            if ($script:fault -eq 'docker-seccomp') { $info.SecurityOptions = @() }
            return ($info | ConvertTo-Json)
        }
        default { throw 'Unexpected command' }
    }
}
try {
    Assert-CiPrerequisites -Frontend
    $checks++
    foreach ($faultCase in @('missing-java', 'missing-docker', 'missing-node', 'missing-npm', 'java-version', 'node-version', 'docker-down', 'docker-json', 'docker-os', 'docker-cgroup', 'docker-seccomp')) {
        $script:fault = $faultCase
        Assert-Rejected { Assert-CiPrerequisites -Frontend }
    }
} finally { Set-Item Function:Invoke-CiTool $actualTool }
# Check real tool discovery failure too; no real daemon stop is needed.
Assert-Rejected { Invoke-CiTool 'pairforge-intentionally-absent-tool' @() }

$fixture = Join-Path (Split-Path $PSScriptRoot -Parent) ('.tmp/ci-fixture-' + [guid]::NewGuid())
function Set-FixtureFile {
    param([string]$Relative, [string]$Content)
    $path = Join-Path $fixture $Relative
    [void][IO.Directory]::CreateDirectory((Split-Path $path -Parent))
    [IO.File]::WriteAllText($path, $Content)
}
function Suite-Xml {
    param([string]$Name, [string]$Outcome = '')
    $failure = if ($Outcome -eq 'failure') { 1 } else { 0 }
    $errorCount = if ($Outcome -eq 'error') { 1 } else { 0 }
    $skipped = if ($Outcome -eq 'skipped') { 1 } else { 0 }
    $child = if ($Outcome) { "<$Outcome message='PRIVATE_SENTINEL'>PRIVATE_SENTINEL</$Outcome>" } else { '' }
    return "<testsuite name='$Name' tests='1' failures='$failure' errors='$errorCount' skipped='$skipped'><properties><property name='secret' value='PRIVATE_SENTINEL'/></properties><testcase name='PRIVATE_SENTINEL'>$child<system-out>PRIVATE_SENTINEL</system-out></testcase></testsuite>"
}
try {
    Set-FixtureFile 'backend/src/test/java/demo/ExampleTest.java' ''
    Set-FixtureFile 'backend/src/test/java/demo/ExampleIT.java' ''
    Set-FixtureFile 'execution-worker/src/test/java/demo/WorkerTest.java' ''
    Set-FixtureFile 'execution-worker/src/test/java/demo/WorkerIT.java' ''
    Set-FixtureFile 'frontend/src/example.test.tsx' ''
    Set-FixtureFile 'frontend/e2e/example.spec.ts' ''
    $reportPaths = @(
        @('backend/target/surefire-reports/TEST-demo.ExampleTest.xml', 'demo.ExampleTest'),
        @('backend/target/failsafe-reports/TEST-demo.ExampleIT.xml', 'demo.ExampleIT'),
        @('execution-worker/target/surefire-reports/TEST-demo.WorkerTest.xml', 'demo.WorkerTest'),
        @('execution-worker/target/failsafe-reports/TEST-demo.WorkerIT.xml', 'demo.WorkerIT'),
        @('frontend/test-results/unit.xml', 'src/example.test.tsx'),
        @('frontend/test-results/browser.xml', 'example.spec.ts')
    )
    foreach ($report in $reportPaths) { Set-FixtureFile $report[0] (Suite-Xml $report[1]) }
    foreach ($kindCase in @('Java', 'Unit', 'Browser')) {
        Test-CiReports -Kind $kindCase -Root $fixture
        $checks++
        $report = switch ($kindCase) { Java { $reportPaths[0] }; Unit { $reportPaths[4] }; Browser { $reportPaths[5] } }
        $good = Suite-Xml $report[1]
        foreach ($outcome in @('failure', 'error', 'skipped')) {
            Set-FixtureFile $report[0] (Suite-Xml $report[1] $outcome)
            Assert-Rejected { Test-CiReports -Kind $kindCase -Root $fixture }
            $summary = Get-Content (Join-Path $fixture ".tmp/ci-reports/$($kindCase.ToLowerInvariant()).json") -Raw
            if ($summary.Contains('PRIVATE_SENTINEL')) { throw 'Sensitive failure content leaked' }
        }
        foreach ($invalid in @('<broken', '<testsuites/>', ($good -replace "tests='1'", "tests='0'"), ($good -replace "tests='1'", "tests='2'"), ($good -replace "skipped='0'", "skipped='-1'"), (Suite-Xml 'wrong-suite'), "<testsuites>$good$good</testsuites>", ($good -replace '</testcase>', '<skipped/></testcase>'), '<!DOCTYPE x [<!ENTITY leak SYSTEM "file:///etc/passwd">]><testsuite>&leak;</testsuite>')) {
            Set-FixtureFile $report[0] $invalid
            Assert-Rejected { Test-CiReports -Kind $kindCase -Root $fixture }
        }
        Remove-Item -LiteralPath (Join-Path $fixture $report[0])
        Assert-Rejected { Test-CiReports -Kind $kindCase -Root $fixture }
        Set-FixtureFile $report[0] $good
        Test-CiReports -Kind $kindCase -Root $fixture
        $summary = Get-Content (Join-Path $fixture ".tmp/ci-reports/$($kindCase.ToLowerInvariant()).json") -Raw
        if ($summary.Contains('PRIVATE_SENTINEL')) { throw 'Sensitive report content leaked' }
        $checks++
    }
    # Discovery is dynamic: adding an integration suite without its report must fail.
    Set-FixtureFile 'execution-worker/src/test/java/demo/NewIT.java' ''
    Assert-Rejected { Test-CiReports -Kind Java -Root $fixture }
    Write-Host "Passed $checks CI gate checks"
} finally {
    $expectedParent = [IO.Path]::GetFullPath((Join-Path (Split-Path $PSScriptRoot -Parent) '.tmp'))
    $resolved = [IO.Path]::GetFullPath($fixture)
    if ((Split-Path $resolved -Parent) -ne $expectedParent -or (Split-Path $resolved -Leaf) -notlike 'ci-fixture-*') { throw 'Unsafe fixture cleanup path' }
    if (Test-Path -LiteralPath $resolved) { Remove-Item -LiteralPath $resolved -Recurse -Force }
}
