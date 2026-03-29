param(
    [string]$DeviceId = "emulator-5554",
    [string]$AppId = "isim.ia2y.myapplication",
    [string]$FlowPath = "",
    [string]$JavaHome = "C:\Program Files\Android\Android Studio\jbr",
    [string]$MaestroPath = "",
    [string]$AdbPath = "",
    [switch]$SkipBuild
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Resolve-RequiredPath {
    param(
        [string]$ExplicitPath,
        [string]$FallbackPath,
        [string]$Label
    )

    $candidate = if ($ExplicitPath) { $ExplicitPath } else { $FallbackPath }
    if (-not (Test-Path $candidate)) {
        throw "$Label not found at '$candidate'."
    }
    return (Resolve-Path $candidate).Path
}

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$maestro = Resolve-RequiredPath -ExplicitPath $MaestroPath -FallbackPath (Join-Path $env:USERPROFILE "maestro\bin\maestro.bat") -Label "Maestro"
$adb = Resolve-RequiredPath -ExplicitPath $AdbPath -FallbackPath (Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe") -Label "adb"
$gradle = Resolve-RequiredPath -ExplicitPath "" -FallbackPath (Join-Path $repoRoot "gradlew.bat") -Label "Gradle wrapper"

if (-not (Test-Path $JavaHome)) {
    throw "JDK 17 was not found at '$JavaHome'."
}

$env:JAVA_HOME = $JavaHome
if (-not (($env:Path -split ';') -contains (Join-Path $JavaHome "bin"))) {
    $env:Path = "$(Join-Path $JavaHome 'bin');$env:Path"
}

$timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
$qaTimestamp = Get-Date -Format "yyyyMMddHHmmss"

if (-not $env:QA_SHOPPER_NAME) { $env:QA_SHOPPER_NAME = "QA Shopper $qaTimestamp" }
if (-not $env:QA_SHOPPER_EMAIL) { $env:QA_SHOPPER_EMAIL = "qa.shopper+$qaTimestamp@fatiweb.test" }
if (-not $env:QA_SHOPPER_PASSWORD) { $env:QA_SHOPPER_PASSWORD = "Qa123456!" }
if (-not $env:QA_ADDRESS_LABEL) { $env:QA_ADDRESS_LABEL = "[QA] Home $qaTimestamp" }
if (-not $env:QA_ADDRESS_RECIPIENT) { $env:QA_ADDRESS_RECIPIENT = $env:QA_SHOPPER_NAME }
if (-not $env:QA_ADDRESS_PHONE) { $env:QA_ADDRESS_PHONE = "+21620123456" }
if (-not $env:QA_ADDRESS_GOVERNORATE) { $env:QA_ADDRESS_GOVERNORATE = "Tunis" }
if (-not $env:QA_ADDRESS_CITY) { $env:QA_ADDRESS_CITY = "Tunis" }
if (-not $env:QA_ADDRESS_LINE1) { $env:QA_ADDRESS_LINE1 = "123 QA Avenue" }

$artifactRoot = Join-Path $repoRoot "artifacts\qa-liveflow\$timestamp"
$reportDir = Join-Path $artifactRoot "reports"
$testOutputDir = Join-Path $artifactRoot "test-output"
$debugOutputDir = Join-Path $artifactRoot "debug-output"

New-Item -ItemType Directory -Force -Path $reportDir | Out-Null
New-Item -ItemType Directory -Force -Path $testOutputDir | Out-Null
New-Item -ItemType Directory -Force -Path $debugOutputDir | Out-Null

& $adb start-server | Out-Null
$devicePattern = "^{0}\s+device$" -f [regex]::Escape($DeviceId)
$deviceList = @()
$deviceReady = $false

for ($attempt = 0; $attempt -lt 15; $attempt++) {
    $deviceList = & $adb devices
    if ($deviceList -match $devicePattern) {
        $deviceReady = $true
        break
    }
    Start-Sleep -Seconds 2
}

if (-not $deviceReady) {
    $deviceOutput = ($deviceList | Out-String).Trim()
    throw "Device '$DeviceId' is not connected. adb devices returned:`n$deviceOutput"
}

if (-not $SkipBuild) {
    Write-Host "Building and installing debug APK..."
    & $gradle assembleDebug installDebug
}

$defaultFlows = @(
    (Join-Path $repoRoot ".maestro\01_onboarding_auth_gate_smoke.yaml"),
    (Join-Path $repoRoot ".maestro\02_register_address_checkout.yaml"),
    (Join-Path $repoRoot ".maestro\03_search_discovery_smoke.yaml"),
    (Join-Path $repoRoot ".maestro\04_account_orders_settings_smoke.yaml"),
    (Join-Path $repoRoot ".maestro\05_favorites_cart_extended.yaml"),
    (Join-Path $repoRoot ".maestro\06_profile_dialogs_and_filters.yaml")
)

$flowsToRun = if ($FlowPath) {
    @((Resolve-Path (Join-Path $repoRoot $FlowPath)).Path)
} else {
    $defaultFlows
}

if (-not $FlowPath -and $env:QA_ADMIN_EMAIL -and $env:QA_ADMIN_PASSWORD) {
    $flowsToRun += (Join-Path $repoRoot ".maestro\90_admin_smoke.yaml")
    $flowsToRun += (Join-Path $repoRoot ".maestro\91_admin_navigation_readonly.yaml")
    $flowsToRun += (Join-Path $repoRoot ".maestro\92_admin_dialogs_and_edit.yaml")
}

$sharedMaestroArgs = @(
    "test",
    "--device", $DeviceId,
    "--format", "HTML-DETAILED",
    "--flatten-debug-output",
    "--no-ansi"
)

$envKeys = @(
    "QA_SHOPPER_NAME",
    "QA_SHOPPER_EMAIL",
    "QA_SHOPPER_PASSWORD",
    "QA_ADDRESS_LABEL",
    "QA_ADDRESS_RECIPIENT",
    "QA_ADDRESS_PHONE",
    "QA_ADDRESS_GOVERNORATE",
    "QA_ADDRESS_CITY",
    "QA_ADDRESS_LINE1"
)

if ($env:QA_ADMIN_EMAIL) { $envKeys += "QA_ADMIN_EMAIL" }
if ($env:QA_ADMIN_PASSWORD) { $envKeys += "QA_ADMIN_PASSWORD" }

function Clear-AppData {
    & $adb shell pm clear $AppId | Out-Null
}

function Seed-OnboardingCompleted {
    $prefFile = Join-Path $env:TEMP "app_flow.xml"
    $prefXml = '<?xml version="1.0" encoding="utf-8" standalone="yes" ?><map><boolean name="onboarding_completed" value="true" /></map>'
    Set-Content -Path $prefFile -Value $prefXml -Encoding UTF8
    & $adb push $prefFile /data/local/tmp/app_flow.xml | Out-Null
    & $adb shell run-as $AppId mkdir shared_prefs | Out-Null
    & $adb shell run-as $AppId cp /data/local/tmp/app_flow.xml shared_prefs/app_flow.xml | Out-Null
}

function Invoke-MaestroFlow {
    param(
        [string]$FlowFile
    )

    $flowName = [System.IO.Path]::GetFileNameWithoutExtension($FlowFile)
    $flowReport = Join-Path $reportDir "$flowName.html"
    $flowTestOutput = Join-Path $testOutputDir $flowName
    $flowDebugOutput = Join-Path $debugOutputDir $flowName

    New-Item -ItemType Directory -Force -Path $flowTestOutput | Out-Null
    New-Item -ItemType Directory -Force -Path $flowDebugOutput | Out-Null

    $args = @() + $sharedMaestroArgs + @(
        "--output", $flowReport,
        "--test-output-dir", $flowTestOutput,
        "--debug-output", $flowDebugOutput
    )

    foreach ($key in $envKeys) {
        $value = (Get-Item "Env:$key" -ErrorAction SilentlyContinue).Value
        if ($null -ne $value -and $value -ne "") {
            $args += @("-e", "$key=$value")
        }
    }

    $args += $FlowFile
    & $maestro @args
}

Write-Host "Running flows:"
$flowsToRun | ForEach-Object { Write-Host " - $_" }
Write-Host "Artifacts: $artifactRoot"

$flowFailures = @()

foreach ($flow in $flowsToRun) {
    $flowName = [System.IO.Path]::GetFileNameWithoutExtension($flow)
    $flowReport = Join-Path $reportDir "$flowName.html"
    $flowDebugFolder = Join-Path $debugOutputDir $flowName
    $commandsFile = Join-Path $flowDebugFolder "commands-($flowName).json"
    if ($flowName -eq "01_onboarding_auth_gate_smoke") {
        Clear-AppData
    } else {
        Clear-AppData
        Seed-OnboardingCompleted
    }

    try {
        Invoke-MaestroFlow -FlowFile $flow
    } catch {
        $flowFailures += $flowName
        Write-Warning "Flow failed: $flowName"
        continue
    }

    if (-not (Test-Path $flowReport) -or -not (Test-Path $commandsFile)) {
        $flowFailures += $flowName
        Write-Warning "Flow output was incomplete: $flowName"
        continue
    }

    $hasFailedSteps = Select-String -Path $commandsFile -SimpleMatch '"status" : "FAILED"' -Quiet
    if ($hasFailedSteps) {
        $flowFailures += $flowName
        Write-Warning "Flow failed according to Maestro command log: $flowName"
    } else {
        Write-Host "Verified flow passed: $flowName"
    }
}

Write-Host ""
Write-Host "QA live-flow run complete."
Write-Host "Reports: $reportDir"
Write-Host "Screenshots and artifacts: $testOutputDir"
Write-Host "Debug output: $debugOutputDir"

if ($flowFailures.Count -gt 0) {
    Write-Warning "Failing flows: $($flowFailures -join ', ')"
    exit 1
}
