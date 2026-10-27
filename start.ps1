param([switch]$SkipBuild)
$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath $PSScriptRoot
if (-not (Get-Command java -ErrorAction SilentlyContinue)) { throw 'Java 21 is required.' }
if (-not (Get-Command node -ErrorAction SilentlyContinue)) { throw 'Node.js 22+ is required for the frontend entry point.' }
if (-not $SkipBuild) {
    if (-not (Get-Command mvn -ErrorAction SilentlyContinue)) { throw 'Maven 3.6.3+ is required.' }
    & mvn -B -ntp clean package
    if ($LASTEXITCODE -ne 0) { throw 'Build failed.' }
}
$jar = Join-Path $PSScriptRoot 'target\menu-1.0.0.jar'
if (-not (Test-Path -LiteralPath $jar)) { throw 'Run .\start.ps1 without -SkipBuild first.' }
$menuOriginalChefPassword = $env:MENU_CHEF_PASSWORD
if (-not $env:MENU_CHEF_PASSWORD -and $env:MENU_ADMIN_PASSWORD) { $env:MENU_CHEF_PASSWORD = $env:MENU_ADMIN_PASSWORD }
$passwordWasProvided = -not [string]::IsNullOrEmpty($env:MENU_CHEF_PASSWORD)
try {
    if (-not $env:MENU_CHEF_USERNAME) { $env:MENU_CHEF_USERNAME = 'chef' }
    if (-not $passwordWasProvided) {
        Write-Host 'Set the chef password for this run (at least 12 characters).'
        $secret = Read-Host 'Chef password' -AsSecureString
        $ptr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secret)
        try { $env:MENU_CHEF_PASSWORD = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($ptr) }
        finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($ptr) }
    }
    if ($env:MENU_CHEF_PASSWORD.Length -lt 12 -or [Text.Encoding]::UTF8.GetByteCount($env:MENU_CHEF_PASSWORD) -gt 72) {
        throw 'Password must contain at least 12 characters and at most 72 UTF-8 bytes.'
    }
    $menuPort = if ($env:PORT) { $env:PORT } else { '12233' }
    $menuFrontendPort = if ($env:FRONTEND_PORT) { $env:FRONTEND_PORT } else { '34455' }
    Write-Host ('Backend: http://127.0.0.1:' + $menuPort)
    Write-Host ('Menu: http://127.0.0.1:' + $menuFrontendPort)
    Write-Host 'Public menu: https://menu.yaozher.com/'
    Write-Host 'Review: https://menu.yaozher.com/chef'
    $runtimeTmp = Join-Path $PSScriptRoot 'data\tmp'
    New-Item -ItemType Directory -Path $runtimeTmp -Force | Out-Null
    & node (Join-Path $PSScriptRoot 'frontend-server.mjs') --jar $jar --tmp $runtimeTmp
    if ($LASTEXITCODE -ne 0) { throw 'The application exited with an error.' }
}
finally {
    $env:MENU_CHEF_PASSWORD = $menuOriginalChefPassword
}
