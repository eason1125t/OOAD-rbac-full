# 文件职责：启动、查看或停止本机成套JWT后端/前端，不初始化数据，不读取部署集群配置。
# 协作关系：使用用户私有目录内的func/oo.json和稳定密钥；只管理自己记录且身份匹配的进程。
param(
    [ValidateSet('Start', 'Stop', 'Status')][string]$Action = 'Status',
    [ValidateSet('both', 'func', 'oo')][string]$Edition = 'both',
    [switch]$Preview
)
$ErrorActionPreference = 'Stop'
$c2Root = Split-Path $PSScriptRoot -Parent
$runtimeRoot = Join-Path $env:LOCALAPPDATA 'OOAD-RBAC/c2-jwt'
$selectedEditions = if ($Edition -eq 'both') { @('func', 'oo') } else { @($Edition) }
$suffix = if ($Preview) { '-preflight' } else { '' }

function Read-ManagedProcess($Manifest) {
    if (-not (Test-Path -LiteralPath $Manifest)) { return $null }
    $record = Get-Content -LiteralPath $Manifest -Raw | ConvertFrom-Json
    if (-not $record.entryPoint.StartsWith($c2Root + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
        throw "记录属于另一份工作目录，不管理该进程：$Manifest"
    }
    $process = Get-CimInstance Win32_Process -Filter "ProcessId = $($record.pid)"
    if (-not $process) { return $null }
    # PID可能复用，必须同时核对可执行入口及创建时间，不能凭旧PID停止其他进程。
    if (-not $process.CommandLine.Contains($record.entryPoint, [StringComparison]::OrdinalIgnoreCase) -or
        $process.CreationDate.ToUniversalTime().Ticks -ne ([DateTime]$record.createdUtc).ToUniversalTime().Ticks) {
        throw "进程身份与记录不符：$Manifest"
    }
    return $record
}

function Start-ManagedProcess($Name, $Executable, $Arguments, $EntryPoint, $WorkingDirectory, $Port, $Variables) {
    $manifest = Join-Path $runtimeRoot "$Name.json"
    $existing = Read-ManagedProcess $manifest
    if ($existing) { Write-Output "已运行：$Name PID=$($existing.pid) port=$Port"; return }
    if (Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue) {
        throw "端口$Port已占用；不会停止未由本脚本管理的进程。"
    }
    $stamp = [DateTime]::UtcNow.ToString('yyyyMMdd-HHmmss-fff')
    $stdout = Join-Path $runtimeRoot "$Name-$stamp.stdout.log"
    $stderr = Join-Path $runtimeRoot "$Name-$stamp.stderr.log"
    $saved = @{}
    foreach ($entry in [Environment]::GetEnvironmentVariables('Process').GetEnumerator()) {
        if ($entry.Key -like 'RBAC_*' -or $entry.Key -eq 'SERVER_PORT') {
            $saved[$entry.Key] = $entry.Value
            [Environment]::SetEnvironmentVariable($entry.Key, $null, 'Process')
        }
    }
    try {
        foreach ($key in $Variables.Keys) { [Environment]::SetEnvironmentVariable($key, [string]$Variables[$key], 'Process') }
        $started = Start-Process -FilePath $Executable -ArgumentList $Arguments -WorkingDirectory $WorkingDirectory `
            -WindowStyle Hidden -RedirectStandardOutput $stdout -RedirectStandardError $stderr -PassThru
    }
    finally {
        foreach ($key in $Variables.Keys) { [Environment]::SetEnvironmentVariable($key, $null, 'Process') }
        foreach ($key in $saved.Keys) { [Environment]::SetEnvironmentVariable($key, $saved[$key], 'Process') }
    }
    $native = Get-CimInstance Win32_Process -Filter "ProcessId = $($started.Id)"
    if (-not $native) { throw "进程未能启动：$Name；请查看用户私有目录日志。" }
    $record = [ordered]@{ name = $Name; pid = $started.Id; port = $Port; entryPoint = $EntryPoint;
        createdUtc = $native.CreationDate.ToUniversalTime().ToString('o'); stdout = $stdout; stderr = $stderr }
    $record | ConvertTo-Json | Set-Content -LiteralPath $manifest -Encoding utf8
    $deadline = [DateTime]::UtcNow.AddSeconds(75)
    $url = if ($Name -eq 'frontend') { "http://127.0.0.1:$Port/" } else { "http://127.0.0.1:$Port/api/auth/csrf" }
    while ($true) {
        if (-not (Get-Process -Id $started.Id -ErrorAction SilentlyContinue)) { throw "$Name已退出；请查看私有日志。" }
        try { $null = Invoke-WebRequest -Uri $url -UseBasicParsing -TimeoutSec 2; break } catch {
            if ([DateTime]::UtcNow -ge $deadline) { throw "$Name启动超时；进程及日志保留供排查。" }
            Start-Sleep -Milliseconds 300
        }
    }
    Write-Output "已启动：$Name PID=$($started.Id) port=$Port"
}

foreach ($code in $selectedEditions) {
    $name = "backend-$code$suffix"
    $manifest = Join-Path $runtimeRoot "$name.json"
    if ($Action -eq 'Start') {
        $configuration = Join-Path $runtimeRoot "$code.json"
        if (-not (Test-Path -LiteralPath $configuration)) { throw "缺少本机私有配置：$configuration；配置方式见部署说明。" }
        $variables = Get-Content -LiteralPath $configuration -Raw | ConvertFrom-Json -AsHashtable
        $port = if ($Preview) { if ($code -eq 'func') { 28181 } else { 28182 } } else { if ($code -eq 'func') { 18181 } else { 18182 } }
        $variables.SERVER_PORT = [string]$port
        if ($Preview) { $variables.RBAC_AUTH_NAMESPACE += ':preflight' }
        $jar = Join-Path $c2Root "backend-$code/target/jwt-final/rbac1-$code-0.1.0-SNAPSHOT.jar"
        if (-not (Test-Path -LiteralPath $jar -PathType Leaf)) { throw "缺少JWT新包：$jar" }
        Start-ManagedProcess $name (Get-Command java -CommandType Application | Select-Object -First 1).Source @('-Dfile.encoding=UTF-8', '-jar', "`"$jar`"") `
            $jar (Join-Path $c2Root "backend-$code") $port $variables
    } else {
        $record = Read-ManagedProcess $manifest
        if ($record -and $Action -eq 'Stop') { Stop-Process -Id $record.pid; Write-Output "已停止：$name PID=$($record.pid)" }
        elseif ($record) { Write-Output "运行中：$name PID=$($record.pid) port=$($record.port)" }
        else { Write-Output "未运行：$name" }
    }
}

if (-not $Preview -and $Edition -eq 'both') {
    $manifest = Join-Path $runtimeRoot 'frontend.json'
    if ($Action -eq 'Start') {
        $vite = Join-Path $c2Root 'frontend/node_modules/vite/bin/vite.js'
        if (-not (Test-Path -LiteralPath $vite)) { throw '请先在frontend安装锁定依赖。' }
        $variables = @{ RBAC_FUNC_API_TARGET = 'http://127.0.0.1:18181'; RBAC_OO_API_TARGET = 'http://127.0.0.1:18182' }
        Start-ManagedProcess 'frontend' (Get-Command node -CommandType Application | Select-Object -First 1).Source `
            @("`"$vite`"", '--host', '127.0.0.1', '--port', '5173', '--strictPort') $vite (Join-Path $c2Root 'frontend') 5173 $variables
    } else {
        $record = Read-ManagedProcess $manifest
        if ($record -and $Action -eq 'Stop') { Stop-Process -Id $record.pid; Write-Output "已停止：frontend PID=$($record.pid)" }
        elseif ($record) { Write-Output "运行中：frontend PID=$($record.pid) port=5173" }
        else { Write-Output '未运行：frontend' }
    }
}
