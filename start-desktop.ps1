param([switch]$NoBrowser, [switch]$ResetPassword)

$ErrorActionPreference = 'Stop'
$menuUrl = 'http://localhost:8080/'
$menuJar = Join-Path $PSScriptRoot 'target\menu-1.0.0.jar'
$menuPasswordFile = Join-Path $env:LOCALAPPDATA 'YaoZherMenu\admin-password.dpapi'
$menuBrowserJob = $null
$menuOriginalPort = $env:PORT
$menuOriginalPassword = $env:MENU_ADMIN_PASSWORD

function Get-PlainPassword([Security.SecureString]$Secret) {
    $menuSecretPointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($Secret)
    try { [Runtime.InteropServices.Marshal]::PtrToStringBSTR($menuSecretPointer) }
    finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($menuSecretPointer) }
}

try {
    Set-Location -LiteralPath $PSScriptRoot
    $menuRunning = @(Get-CimInstance Win32_Process -Filter "Name = 'java.exe'" | Where-Object { $_.CommandLine -like ('*' + $menuJar + '*') })
    $menuProbe = New-Object Net.Sockets.TcpClient
    $menuPortBusy = $false
    try { $menuProbe.Connect('localhost', 8080); $menuPortBusy = $true }
    catch { }
    finally { $menuProbe.Dispose() }
    if ($menuRunning.Count -gt 0) {
        if ($ResetPassword) { throw '网站正在运行，请先停止后再重设密码。' }
        $menuResponse = Invoke-WebRequest -Uri ($menuUrl + 'api/menu') -UseBasicParsing -TimeoutSec 5
        if ($menuResponse.StatusCode -ne 200 -or $menuResponse.Headers['Content-Type'] -notlike 'application/json*') { throw '检测到项目进程，但网站尚未就绪，请稍后重试。' }
        Write-Host ('菜单网站已在运行：' + $menuUrl)
        if (-not $NoBrowser) { Start-Process -FilePath $menuUrl }
        exit 0
    }
    if ($menuPortBusy) { throw '8080 端口被其他程序占用，请先关闭占用程序后重试。' }

    if (-not (Get-Command java -ErrorAction SilentlyContinue)) { throw '未找到 Java，请安装 JDK 21 并加入 PATH。' }
    if (-not (Test-Path -LiteralPath $menuJar)) {
        if (-not (Get-Command mvn -ErrorAction SilentlyContinue)) { throw '缺少运行文件，请先安装 Maven 并在项目中执行 mvn package。' }
        Write-Host '首次构建，请稍候…'
        & mvn -B -ntp package
        if ($LASTEXITCODE -ne 0) { throw '构建失败，请查看上方错误信息。' }
    }

    if ($ResetPassword -or [string]::IsNullOrEmpty($env:MENU_ADMIN_PASSWORD)) {
        if (-not $ResetPassword -and (Test-Path -LiteralPath $menuPasswordFile)) {
            $menuSecret = (Get-Content -LiteralPath $menuPasswordFile -Raw).Trim() | ConvertTo-SecureString
            try { $env:MENU_ADMIN_PASSWORD = Get-PlainPassword $menuSecret }
            finally { $menuSecret.Dispose() }
        } else {
            Write-Host '首次启动请设置管理员密码（至少 12 个字符）。'
            Write-Host '密码将由当前 Windows 账户加密保存，之后双击即可启动。'
            do {
                $menuSecret = Read-Host '管理员密码' -AsSecureString
                $menuConfirm = Read-Host '再次输入密码' -AsSecureString
                try {
                    $menuPlain = Get-PlainPassword $menuSecret
                    $menuConfirmation = Get-PlainPassword $menuConfirm
                    $menuValid = $menuPlain.Length -ge 12 -and [Text.Encoding]::UTF8.GetByteCount($menuPlain) -le 72 -and $menuPlain -ceq $menuConfirmation
                    if ($menuValid) {
                        New-Item -ItemType Directory -Path (Split-Path -Parent $menuPasswordFile) -Force | Out-Null
                        $menuSecret | ConvertFrom-SecureString | Set-Content -LiteralPath $menuPasswordFile -Encoding ASCII
                        $env:MENU_ADMIN_PASSWORD = $menuPlain
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

    $env:PORT = '8080'
    $menuUsername = if ($env:MENU_ADMIN_USERNAME) { $env:MENU_ADMIN_USERNAME } else { 'admin' }
    Write-Host ('菜单：' + $menuUrl)
    Write-Host ('审核：' + $menuUrl + 'admin  账号：' + $menuUsername)
    Write-Host '启动后请保留此窗口；按 Ctrl+C 停止网站。'

    if (-not $NoBrowser) {
        $menuBrowserJob = Start-Job -ArgumentList $menuUrl -ScriptBlock {
            param($Url)
            for ($menuAttempt = 0; $menuAttempt -lt 90; $menuAttempt++) {
                try {
                    $menuResponse = Invoke-WebRequest -Uri ($Url + 'api/menu') -UseBasicParsing -TimeoutSec 2
                    if ($menuResponse.StatusCode -eq 200 -and $menuResponse.Headers['Content-Type'] -like 'application/json*') {
                        Start-Process -FilePath $Url
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
    $env:MENU_ADMIN_PASSWORD = $menuOriginalPassword
}
