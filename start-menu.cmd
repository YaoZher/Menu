@echo off
setlocal
title Menu Website
set "PSModulePath="
set "MENU_PROJECT=%~dp0"
if not exist "%MENU_PROJECT%start-desktop.ps1" set "MENU_PROJECT=E:\Projects\Web\Menu\"
if not exist "%MENU_PROJECT%start-desktop.ps1" (
    echo Menu project not found: E:\Projects\Web\Menu
    pause
    exit /b 1
)
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%MENU_PROJECT%start-desktop.ps1" %*
if errorlevel 1 (
    pause
    exit /b 1
)
