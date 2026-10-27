param([switch]$NoBrowser, [switch]$ResetPassword)

$ErrorActionPreference = 'Stop'
$menuUrl = 'http://127.0.0.1:34455/'
$menuPublicUrl = 'https://menu.yaozher.com/'
$menuJar = Join-Path $PSScriptRoot 'target\menu-1.0.0.jar'
$menuFrontendScript = Join-Path $PSScriptRoot 'frontend-server.mjs'
$menuPasswordFile = Join-Path $env:LOCALAPPDATA 'YaoZherMenu\chef-password.dpapi'
$menuLegacyPasswordFile = Join-Path $env:LOCALAPPDATA 'YaoZherMenu\admin-password.dpapi'
$menuBrowserJob = $null
$menuOriginalPort = $env:PORT
$menuOriginalFrontendPort = $env:FRONTEND_PORT
$menuOriginalPassword = $env:MENU_CHEF_PASSWORD

function Get-PlainPassword([Security.SecureString]$Secret) {
    $menuSecretPointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($Secret)
    try { [Runtime.InteropServices.Marshal]::PtrToStringBSTR($menuSecretPointer) }
    finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($menuSecretPointer) }
}

try {
    Set-Location -LiteralPath $PSScriptRoot
    $menuRunning = @(Get-CimInstance Win32_Process -Filter "Name = 'java.exe'" | Where-Object { $_.CommandLine -like ('*' + $menuJar + '*') })
    $menuFrontendRunning = @(Get-CimInstance Win32_Process -Filter "Name = 'node.exe'" | Where-Object { $_.CommandLine -like ('*' + $menuFrontendScript + '*') })
    if ($menuFrontendRunning.Count -gt 0 -and $menuRunning.Count -eq 0) { throw '前端入口正在启动后端，请稍后重试；若持续失败，请检查原启动窗口。' }
    if ($menuRunning.Count -gt 0) {
        if ($ResetPassword) { throw '网站正在运行，请先停止后再重设密码。' }
        if ($menuFrontendRunning.Count -eq 0) { throw '后端已运行但前端入口未运行，请先停止旧启动窗口再重新启动。' }
        $menuResponse = Invoke-WebRequest -Uri ($menuUrl + 'api/menu') -UseBasicParsing -TimeoutSec 5
        if ($menuResponse.StatusCode -ne 200 -or $menuResponse.Headers['Content-Type'] -notlike 'application/json*') { throw '检测到项目进程，但网站尚未就绪，请稍后重试。' }
        Write-Host ('菜单网站已在运行：' + $menuPublicUrl)
        if (-not $NoBrowser) { Start-Process -FilePath $menuPublicUrl }
        exit 0
    }
    foreach ($menuPortToCheck in @(12233, 34455)) {
        $menuProbe = New-Object Net.Sockets.TcpClient
        $menuPortBusy = $false
        try { $menuProbe.Connect('127.0.0.1', $menuPortToCheck); $menuPortBusy = $true }
        catch { }
        finally { $menuProbe.Dispose() }
        if ($menuPortBusy) { throw ($menuPortToCheck.ToString() + ' 端口被其他程序占用，请先关闭占用程序后重试。') }
    }

    if (-not (Get-Command java -ErrorAction SilentlyContinue)) { throw '未找到 Java，请安装 JDK 21 并加入 PATH。' }
    if (-not (Get-Command node -ErrorAction SilentlyContinue)) { throw '未找到 Node.js，请安装 Node.js 22 或以上并加入 PATH。' }
    if (-not (Test-Path -LiteralPath $menuJar)) {
        if (-not (Get-Command mvn -ErrorAction SilentlyContinue)) { throw '缺少运行文件，请先安装 Maven 并在项目中执行 mvn package。' }
        Write-Host '首次构建，请稍候…'
        & mvn -B -ntp package
        if ($LASTEXITCODE -ne 0) { throw '构建失败，请查看上方错误信息。' }
    }

    if (-not $env:MENU_CHEF_PASSWORD -and $env:MENU_ADMIN_PASSWORD) { $env:MENU_CHEF_PASSWORD = $env:MENU_ADMIN_PASSWORD }
    $menuSavedPasswordFile = if (Test-Path -LiteralPath $menuPasswordFile) { $menuPasswordFile } else { $menuLegacyPasswordFile }
    if ($ResetPassword -or [string]::IsNullOrEmpty($env:MENU_CHEF_PASSWORD)) {
        if (-not $ResetPassword -and (Test-Path -LiteralPath $menuSavedPasswordFile)) {
            $menuSecret = (Get-Content -LiteralPath $menuSavedPasswordFile -Raw).Trim() | ConvertTo-SecureString
            try { $env:MENU_CHEF_PASSWORD = Get-PlainPassword $menuSecret }
            finally { $menuSecret.Dispose() }
            if ($menuSavedPasswordFile -ne $menuPasswordFile) {
                Copy-Item -LiteralPath $menuSavedPasswordFile -Destination $menuPasswordFile
            }
        } else {
            Write-Host '首次启动请设置厨师密码（至少 12 个字符）。'
            Write-Host '密码将由当前 Windows 账户加密保存，之后双击即可启动。'
            do {
                $menuSecret = Read-Host '厨师密码' -AsSecureString
                $menuConfirm = Read-Host '再次输入密码' -AsSecureString
                try {
                    $menuPlain = Get-PlainPassword $menuSecret
                    $menuConfirmation = Get-PlainPassword $menuConfirm
                    $menuValid = $menuPlain.Length -ge 12 -and [Text.Encoding]::UTF8.GetByteCount($menuPlain) -le 72 -and $menuPlain -ceq $menuConfirmation
                    if ($menuValid) {
                        New-Item -ItemType Directory -Path (Split-Path -Parent $menuPasswordFile) -Force | Out-Null
                        $menuSecret | ConvertFrom-SecureString | Set-Content -LiteralPath $menuPasswordFile -Encoding ASCII
                        $env:MENU_CHEF_PASSWORD = $menuPlain
                    } else { Write-Host '两次密码需一致，且至少 12 个字符、UTF-8 不超过 72 字节。' -ForegroundColor Yellow }
                } finally {
                    $menuSecret.Dispose()
                    $menuConfirm.Dispose()
                    $menuPlain = $null
                    $menuConfirmation = $null
                }
            } until ($menuValid)
        }
    }

    $env:PORT = '12233'
    $env:FRONTEND_PORT = '34455'
    $menuUsername = if ($env:MENU_CHEF_USERNAME) { $env:MENU_CHEF_USERNAME } else { 'chef' }
    Write-Host ('菜单：' + $menuPublicUrl)
    Write-Host ('审核：' + $menuPublicUrl + 'chef  账号：' + $menuUsername)
    Write-Host ('本地前端：' + $menuUrl + '  后端：http://127.0.0.1:12233/')
    Write-Host '启动后请保留此窗口；按 Ctrl+C 停止网站。'

    if (-not $NoBrowser) {
        $menuBrowserJob = Start-Job -ArgumentList $menuUrl, $menuPublicUrl -ScriptBlock {
            param($Url, $PublicUrl)
            for ($menuAttempt = 0; $menuAttempt -lt 90; $menuAttempt++) {
                try {
                    $menuResponse = Invoke-WebRequest -Uri ($Url + 'api/menu') -UseBasicParsing -TimeoutSec 2
                    if ($menuResponse.StatusCode -eq 200 -and $menuResponse.Headers['Content-Type'] -like 'application/json*') {
                        Start-Process -FilePath $PublicUrl
                        return
                    }
                } catch { }
                Start-Sleep -Seconds 1
            }
        }
    }
    & (Join-Path $PSScriptRoot 'start.ps1') -SkipBuild
} catch {
    Write-Host ('启动失败：' + $_.Exception.Message) -ForegroundColor Red
    exit 1
} finally {
    if ($menuBrowserJob) {
        Stop-Job -Job $menuBrowserJob -ErrorAction SilentlyContinue
        Remove-Job -Job $menuBrowserJob -Force -ErrorAction SilentlyContinue
    }
    $env:PORT = $menuOriginalPort
    $env:FRONTEND_PORT = $menuOriginalFrontendPort
    $env:MENU_CHEF_PASSWORD = $menuOriginalPassword
}
