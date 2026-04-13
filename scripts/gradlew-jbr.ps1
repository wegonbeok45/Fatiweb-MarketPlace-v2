[CmdletBinding()]
param(
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$GradleArgs
)

$jbrPath = "C:\Program Files\Android\Android Studio\jbr"
$javaExe = Join-Path $jbrPath "bin\java.exe"

if (-not (Test-Path $javaExe)) {
    Write-Error "Android Studio JBR was not found at '$jbrPath'. Set JAVA_HOME manually and rerun Gradle."
    exit 1
}

$env:JAVA_HOME = $jbrPath
$env:Path = "$($env:JAVA_HOME)\bin;$env:Path"

if (-not $GradleArgs -or $GradleArgs.Count -eq 0) {
    $GradleArgs = @("testDebugUnitTest")
}

& "$PSScriptRoot\..\gradlew.bat" @GradleArgs
exit $LASTEXITCODE
