# Build entry point for this project: force JDK 17.
# Maven uses JAVA_HOME (not PATH), so we set it here to avoid the system-wide JDK 8.
#
# Usage:
#   .\build.ps1 clean test
#   .\build.ps1 spring-boot:run
#
# If JDK 17 is not at the default path, set AIKB_JDK to your JDK 17 folder.

$jdk = $env:AIKB_JDK
if (-not $jdk) {
    $jdk = "D:\Program Files\Java\jdk-17"
}

if (-not (Test-Path (Join-Path $jdk "bin\java.exe"))) {
    throw "JDK 17 not found at: $jdk. Edit build.ps1 or set AIKB_JDK."
}

$env:JAVA_HOME = $jdk
$env:Path = (Join-Path $jdk "bin") + ";" + $env:Path

Write-Host "Using JDK: $jdk" -ForegroundColor Cyan
& mvn @args
exit $LASTEXITCODE
