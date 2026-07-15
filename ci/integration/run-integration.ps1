$ErrorActionPreference = "Stop"

$jmeterVersion = if ($env:JMETER_VERSION) { $env:JMETER_VERSION } else { "5.6.2" }
$rootDir = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$outDir = Join-Path $rootDir "ci\integration\out"
$workDir = Join-Path $outDir "work"
$jmeterDir = Join-Path $workDir "apache-jmeter-$jmeterVersion"
$jmeterZip = Join-Path $workDir "apache-jmeter-$jmeterVersion.zip"
$jmxFile = Join-Path $rootDir "ci\integration\integration-commands.jmx"
$resultsFile = Join-Path $outDir "results-$jmeterVersion.jtl"
$logFile = Join-Path $outDir "jmeter-$jmeterVersion.log"

New-Item -ItemType Directory -Force -Path $outDir, $workDir | Out-Null

$pluginJar = Get-ChildItem (Join-Path $rootDir "target\jmeter-agent-*.jar") -File |
    Where-Object { $_.Name -notmatch "sources|javadoc|original" } |
    Select-Object -First 1

if (-not $pluginJar) {
    throw "Plugin JAR not found in target/."
}

if (-not (Test-Path $jmeterDir)) {
    $url = "https://archive.apache.org/dist/jmeter/binaries/apache-jmeter-$jmeterVersion.zip"
    Invoke-WebRequest -Uri $url -OutFile $jmeterZip
    Expand-Archive -Path $jmeterZip -DestinationPath $workDir -Force
}

Copy-Item -Path $pluginJar.FullName -Destination (Join-Path $jmeterDir "lib\ext\") -Force

$jmeterScript = if ($IsWindows) {
    Join-Path $jmeterDir "bin\jmeter.bat"
} else {
    Join-Path $jmeterDir "bin/jmeter"
}

& $jmeterScript -n -t $jmxFile -l $resultsFile -j $logFile

$hasResults = $false
for ($i = 0; $i -lt 10; $i++) {
    if ((Test-Path $resultsFile) -and ((Get-Item $resultsFile).Length -gt 0)) {
        $hasResults = $true
        break
    }
    Start-Sleep -Milliseconds 500
}

if (-not $hasResults) {
    throw "No JTL results produced."
}

$rows = Import-Csv -Path $resultsFile
if (-not $rows -or $rows.Count -eq 0) {
    throw "Integration test produced empty JTL."
}

$failedRows = $rows | Where-Object { $_.success -ne "true" }
if ($failedRows) {
    $failedRows | Select-Object -First 10 | ForEach-Object {
        Write-Host ("FAILED SAMPLE: label={0}, message={1}, code={2}" -f $_.label, $_.responseMessage, $_.responseCode)
    }
    throw "Integration JMeter samples contain failures."
}

$pattern = "(?i)(^\d{4}-\d{2}-\d{2}.*\sERROR\s|stackoverflowerror|incompatibleclasschangeerror|groovyruntimeexception|conflicting module versions)"
$matches = Select-String -Path $logFile -Pattern $pattern -AllMatches
if ($matches) {
    Write-Host "Integration test failed: critical error patterns detected in $logFile"
    $matches | Select-Object -First 50 | ForEach-Object { Write-Host $_.Line }
    throw "JMeter integration log check failed."
}

Write-Host "Integration test passed for JMeter $jmeterVersion."
