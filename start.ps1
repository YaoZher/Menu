param([switch]$SkipBuild)
$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath $PSScriptRoot
if (-not (Get-Command java -ErrorAction SilentlyContinue)) { throw 'Java 21 is required.' }
if (-not $SkipBuild) {
    if (-not (Get-Command mvn -ErrorAction SilentlyContinue)) { throw 'Maven 3.6.3+ is required.' }
    & mvn -B -ntp clean package
    if ($LASTEXITCODE -ne 0) { throw 'Build failed.' }
}
$jar = Join-Path $PSScriptRoot 'target\menu-1.0.0.jar'
if (-not (Test-Path -LiteralPath $jar)) { throw 'Run .\start.ps1 without -SkipBuild first.' }
$passwordWasProvided = -not [string]::IsNullOrEmpty($env:MENU_ADMIN_PASSWORD)
try {
    if (-not $env:MENU_ADMIN_USERNAME) { $env:MENU_ADMIN_USERNAME = 'admin' }
    if (-not $passwordWasProvided) {
        Write-Host 'Set the administrator password for this run (at least 12 characters).'
        $secret = Read-Host 'Administrator password' -AsSecureString
        $ptr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secret)
        try { $env:MENU_ADMIN_PASSWORD = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($ptr) }
        finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($ptr) }
    }
    if ($env:MENU_ADMIN_PASSWORD.Length -lt 12 -or [Text.Encoding]::UTF8.GetByteCount($env:MENU_ADMIN_PASSWORD) -gt 72) {
        throw 'Password must contain at least 12 characters and at most 72 UTF-8 bytes.'
    }
    $menuPort = if ($env:PORT) { $env:PORT } else { '8080' }
    Write-Host ('Menu: http://localhost:' + $menuPort)
    Write-Host ('Review: http://localhost:' + $menuPort + '/admin')
    $runtimeTmp = Join-Path $PSScriptRoot 'data\tmp'
    New-Item -ItemType Directory -Path $runtimeTmp -Force | Out-Null
    & java -Xms64m -Xmx384m "-Djava.io.tmpdir=$runtimeTmp" "-Djdk.net.unixdomain.tmpdir=$runtimeTmp" -jar $jar
    if ($LASTEXITCODE -ne 0) { throw 'The application exited with an error.' }
}
finally {
    if (-not $passwordWasProvided) { Remove-Item Env:\MENU_ADMIN_PASSWORD -ErrorAction SilentlyContinue }
}
