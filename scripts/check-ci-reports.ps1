param(
    [ValidateSet('Java', 'Unit', 'Browser', 'Load')][string]$Kind = 'Java',
    [string]$Root = (Split-Path $PSScriptRoot -Parent)
)
$ErrorActionPreference = 'Stop'

function Read-CiXml {
    param([string]$Path)
    $settings = [System.Xml.XmlReaderSettings]::new()
    $settings.DtdProcessing = [System.Xml.DtdProcessing]::Prohibit
    $settings.XmlResolver = $null
    $reader = [System.Xml.XmlReader]::Create($Path, $settings)
    try {
        $document = [System.Xml.XmlDocument]::new()
        $document.XmlResolver = $null
        $document.Load($reader)
        return ,$document
    } finally { $reader.Dispose() }
}

function Test-CiReports {
    param([string]$Kind, [string]$Root)
    $expected = @()
    if ($Kind -eq 'Java') {
        foreach ($module in @('backend', 'execution-worker')) {
            $sourceRoot = Join-Path $Root "$module/src/test/java"
            foreach ($file in Get-ChildItem $sourceRoot -Recurse -File | Where-Object Name -Match '(Test|IT)\.java$') {
                $class = $file.FullName.Substring($sourceRoot.Length + 1).Replace('\', '.').Replace('/', '.') -replace '\.java$', ''
                $directory = if ($file.Name -match 'IT\.java$') { 'failsafe-reports' } else { 'surefire-reports' }
                $expected += @{ source = "$module/$class"; suite = $class; report = "$module/target/$directory/TEST-$class.xml" }
            }
        }
    } else {
        $directory = if ($Kind -eq 'Unit') { 'src' } elseif ($Kind -eq 'Load') { 'tools/load' } else { 'e2e' }
        $pattern = if ($Kind -ne 'Browser') { '\.test\.tsx?$' } else { '\.spec\.ts$' }
        $frontendRoot = Join-Path $Root 'frontend'
        foreach ($file in Get-ChildItem (Join-Path $frontendRoot $directory) -Recurse -File | Where-Object Name -Match $pattern) {
            $name = $file.FullName.Substring($frontendRoot.Length + 1).Replace('\', '/')
            $suite = if ($Kind -eq 'Browser') { $name.Substring(4) } else { $name }
            $expected += @{ source = $name; suite = $suite; report = "frontend/test-results/$($Kind.ToLowerInvariant()).xml" }
        }
    }
    if ($expected.Count -eq 0) { throw 'No required test suites discovered' }
    $results = @()
    foreach ($entry in $expected) {
        $result = [ordered]@{ source = $entry.source; status = 'invalid-or-missing'; tests = 0; failures = 0; errors = 0; skipped = 0 }
        try {
            $xml = Read-CiXml (Join-Path $Root $entry.report)
            $suites = @($xml.SelectNodes('//testsuite') | Where-Object { $_.GetAttribute('name').Replace('\', '/') -eq $entry.suite })
            if ($suites.Count -ne 1) { throw 'Expected exactly one suite' }
            $suite = $suites[0]
            foreach ($attribute in @('tests', 'failures', 'errors', 'skipped')) {
                $value = $suite.GetAttribute($attribute)
                if ($value -notmatch '^\d+$') { throw 'Invalid test count' }
                $result[$attribute] = [int]$value
            }
            $cases = @($suite.SelectNodes('testcase'))
            if ($result.tests -le 0 -or $cases.Count -ne $result.tests) { throw 'Missing test cases' }
            # Validate children too: inconsistent summary attributes cannot hide failure/skip.
            foreach ($pair in @(@('failure', 'failures'), @('error', 'errors'), @('skipped', 'skipped'))) {
                if ($suite.SelectNodes("testcase/$($pair[0])").Count -ne $result[$pair[1]]) { throw 'Inconsistent test counts' }
            }
            $result.status = if ($result.failures + $result.errors + $result.skipped -eq 0) { 'passed' } else { 'failed-or-skipped' }
        } catch {
            # Raw XML, exceptions, case names and output can contain submitted code or secrets.
            # The artifact contains only discovered source paths, numeric counts and fixed statuses.
        }
        $results += [pscustomobject]$result
    }
    $destination = Join-Path $Root '.tmp/ci-reports'
    [void][IO.Directory]::CreateDirectory($destination)
    $results | ConvertTo-Json -Depth 4 | Set-Content (Join-Path $destination "$($Kind.ToLowerInvariant()).json")
    $results | Format-Table | Out-Host
    if (@($results | Where-Object status -ne 'passed').Count -gt 0) { throw 'Required test reports failed validation; see sanitized summary' }
}

if ($MyInvocation.InvocationName -ne '.') { Test-CiReports -Kind $Kind -Root ([IO.Path]::GetFullPath($Root)) }
