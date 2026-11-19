# 生成原生模型并通过 StarUML CLI 导出；与 build_staruml.py、diagram-index.json 配套使用。
param(
    [string]$StarUml = 'C:\Program Files\StarUML\StarUML.exe',
    [switch]$ExportOnly
)

$ErrorActionPreference = 'Stop'
$modelPath = Join-Path $PSScriptRoot 'C2-RBAC1.mdj'
$exportPath = Join-Path $PSScriptRoot 'exports'
if (-not (Test-Path -LiteralPath $StarUml -PathType Leaf)) {
    throw "未找到 StarUML，请通过 -StarUml 指定安装路径：$StarUml"
}
if (-not $ExportOnly) {
    python (Join-Path $PSScriptRoot 'build_staruml.py')
    if ($LASTEXITCODE -ne 0) { throw '模型生成或自动检查失败' }
}
$diagramCount = @(Get-Content -LiteralPath (Join-Path $PSScriptRoot 'diagram-index.json') -Raw | ConvertFrom-Json).Count

foreach ($format in @('svg', 'png')) {
    $outputTemplate = Join-Path $exportPath "<%=filenamify(element.name)%>.$format"
    $logPath = Join-Path $env:TEMP "c2-staruml-$format.log"
    $errorPath = Join-Path $env:TEMP "c2-staruml-$format.err"
    # Start-Process 不自动为含空格的路径加引号，参数在这里逐项明确引用。
    $arguments = "image `"$modelPath`" -f $format -o `"$outputTemplate`""
    $process = Start-Process -FilePath $StarUml -ArgumentList $arguments -WindowStyle Hidden -PassThru `
        -RedirectStandardOutput $logPath -RedirectStandardError $errorPath
    $process.WaitForExit()
    $log = Get-Content -LiteralPath $logPath -Raw
    if ($process.ExitCode -ne 0 -or $log -notmatch "Total $diagramCount diagrams were exported") {
        throw "StarUML 导出失败。请检查 $logPath 和 $errorPath"
    }
    Write-Output "StarUML CLI：已导出 $diagramCount 张 $format"
}
# PNG 原生导出为透明背景，某些预览器会显示黑底。只合成白底，完整保留试用水印。
Add-Type -AssemblyName System.Drawing
foreach ($imagePath in Get-ChildItem -LiteralPath $exportPath -Filter '*.png' -File) {
    $original = [System.Drawing.Image]::FromFile($imagePath.FullName)
    $white = [System.Drawing.Bitmap]::new($original.Width, $original.Height)
    $graphics = [System.Drawing.Graphics]::FromImage($white)
    try {
        $graphics.Clear([System.Drawing.Color]::White)
        $graphics.DrawImageUnscaled($original, 0, 0)
        $original.Dispose()
        $white.Save($imagePath.FullName, [System.Drawing.Imaging.ImageFormat]::Png)
    }
    finally {
        $graphics.Dispose()
        $white.Dispose()
        $original.Dispose()
    }
}
python (Join-Path $PSScriptRoot 'build_staruml.py') --check
if ($LASTEXITCODE -ne 0) { throw '图源或导出校验失败' }
