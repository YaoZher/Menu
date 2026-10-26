$ErrorActionPreference = 'Stop'
$menuJar = Join-Path $PSScriptRoot 'target\menu-1.0.0.jar'
$menuFrontend = Join-Path $PSScriptRoot 'frontend-server.mjs'

function Get-MenuProcesses([string]$Name, [string]$Path) {
    @(Get-CimInstance Win32_Process -Filter ("Name = '" + $Name + "'") | Where-Object { $_.CommandLine -like ('*' + $Path + '*') })
}

try {
    foreach ($menuProcess in (Get-MenuProcesses 'java.exe' $menuJar)) {
        Stop-Process -Id $menuProcess.ProcessId -ErrorAction SilentlyContinue
    }
    for ($menuAttempt = 0; $menuAttempt -lt 5; $menuAttempt++) {
        $menuProcesses = @(Get-MenuProcesses 'node.exe' $menuFrontend)
        if ($menuProcesses.Count -eq 0) { break }
        Start-Sleep -Seconds 1
    }
    foreach ($menuProcess in (Get-MenuProcesses 'node.exe' $menuFrontend)) {
        Stop-Process -Id $menuProcess.ProcessId -ErrorAction SilentlyContinue
    }
    Write-Host '菜单网站已停止。'
} catch {
    Write-Host ('停止失败：' + $_.Exception.Message) -ForegroundColor Red
    exit 1
}
