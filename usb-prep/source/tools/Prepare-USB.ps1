#requires -version 5.1
param([switch]$PrepareOnly)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version 2.0
Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem

$release = [IO.Directory]::GetParent($PSScriptRoot).FullName
$usbZip = 'NEEV_260902_CarPlay_1.0.2_USB.zip'
$releaseUrl = '@CARPLAY_RELEASE_BASE@' + '/' + $usbZip
$usbHash = '@CARPLAY_CORE_SHA256@'
$upstream = 'HKG_5W_USBWifi_Hotspot.zip'
$upHash = '7a7e5c7a46fbf160d7b9b91fe4333ea55e4865233450c285361f9ef1a11059d7'
$upUrl = 'https://github.com/GRT47/HKG_5W_USB_Wlan/releases/download/v2.0.2/HKG_5W_USBWifi_Hotspot.zip'
$driver = 'packages/usbwifi.hotspot.zip'
$driverHash = 'd9077ba275398c1578444fdea479a5675e21f1b36f9ad3217e57b65aeadc512b'
$ready = Join-Path $release 'USB_READY'
$expected = [Collections.Generic.Dictionary[string,string]]::new([StringComparer]::Ordinal)
$folders = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)

function Get-ReleaseFile([string]$fileName, [string]$url) {
    $file = Join-Path $release $fileName
    if ([IO.File]::Exists($file)) { return $file }
    Write-Host "GitHub 공식 릴리스 다운로드: $fileName"
    $file = Join-Path $work $fileName
    [Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12
    $ProgressPreference = 'SilentlyContinue'
    $null = Invoke-WebRequest -UseBasicParsing -Uri $url -OutFile $file -TimeoutSec 90
    return $file
}

function Open-VerifiedZip([string]$file, [string]$digest) {
    # Hash and extract through the same read-only file handle.
    $stream = [IO.File]::OpenRead($file)
    try {
        if ((Get-FileHash -InputStream $stream -Algorithm SHA256).Hash -ne $digest) {
            throw "파일이 지정된 1.0.2 배포본과 일치하지 않습니다: $([IO.Path]::GetFileName($file))"
        }
        $stream.Position = 0
        return [IO.Compression.ZipArchive]::new($stream, [IO.Compression.ZipArchiveMode]::Read)
    } catch {
        $stream.Dispose()
        throw
    }
}

function Assert-ReadyTree([string]$root) {
    $dir = [IO.DirectoryInfo]::new($root)
    if (!$dir.Exists -or ($dir.Attributes -band [IO.FileAttributes]::ReparsePoint)) {
        throw "USB_READY 경로를 확인하세요. 기존 파일이나 연결된 폴더는 사용할 수 없습니다."
    }
    $pending = [Collections.Generic.Stack[IO.DirectoryInfo]]::new()
    $pending.Push($dir)
    $seen = 0
    while ($pending.Count) {
        foreach ($item in $pending.Pop().GetFileSystemInfos()) {
            $rel = $item.FullName.Substring($root.Length + 1).Replace('\','/')
            if ($item.Attributes -band [IO.FileAttributes]::ReparsePoint) {
                throw "USB_READY에 연결된 파일이 있습니다: $rel"
            }
            if ($item -is [IO.DirectoryInfo]) {
                if (!$folders.Contains($rel)) { throw "USB_READY에 다른 폴더가 있습니다: $rel" }
                $pending.Push($item)
            } else {
                if (!$expected.ContainsKey($rel) -or
                    (Get-FileHash -LiteralPath $item.FullName -Algorithm SHA256).Hash -ne $expected[$rel]) {
                    throw "USB_READY 파일을 확인하세요: $rel"
                }
                $seen++
            }
        }
    }
    if ($seen -ne $expected.Count) { throw "USB_READY에 필요한 설치 파일이 빠져 있습니다." }
}

$kit = $null
$work = $null
$result = 0
try {
    Write-Host 'CarPlay 1.0.2 by blackr - 설치 파일 확인 중'
    $newWork = Join-Path $release ('.prepare-usb-' + [Guid]::NewGuid().ToString('N'))
    [void](New-Item -ItemType Directory -Path $newWork)
    $work = $newWork
    $kit = Open-VerifiedZip (Get-ReleaseFile $usbZip $releaseUrl) $usbHash
    foreach ($entry in $kit.Entries) {
        $name = $entry.FullName
        $entryStream = $entry.Open()
        try { $expected.Add($name, (Get-FileHash -InputStream $entryStream -Algorithm SHA256).Hash) }
        finally { $entryStream.Dispose() }
        while ($name.Contains('/')) {
            $name = $name.Substring(0, $name.LastIndexOf('/'))
            [void]$folders.Add($name)
        }
    }
    $expected.Add($driver, $driverHash)
    if (Test-Path -LiteralPath $ready) {
        Assert-ReadyTree $ready
    } else {
        $staged = Join-Path $work 'USB_READY'
        [void][IO.Directory]::CreateDirectory($staged)
        $vendorZip = Open-VerifiedZip (Get-ReleaseFile $upstream $upUrl) $upHash
        try {
            foreach ($entry in $kit.Entries) {
                $dest = [IO.Path]::Combine($staged,$entry.FullName)
                [void][IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($dest))
                [IO.Compression.ZipFileExtensions]::ExtractToFile($entry,$dest)
            }
            $vendorEntry = $vendorZip.GetEntry($driver)
            if ($null -eq $vendorEntry) { throw 'GRT47 원본에서 필요한 파일을 찾을 수 없습니다.' }
            [IO.Compression.ZipFileExtensions]::ExtractToFile($vendorEntry, [IO.Path]::Combine($staged,$driver))
        } finally { $vendorZip.Dispose() }
        Assert-ReadyTree $staged
        [IO.Directory]::Move($staged,$ready)
    }
    Write-Host "준비 완료 ($($expected.Count)개 파일 확인): $ready"
    if (!$PrepareOnly) {
        . (Join-Path $PSScriptRoot 'WindowsUsbVolume.ps1')
        Start-UsbFileRefresh
    }
} catch {
    [Console]::Error.WriteLine("준비 실패: $($_.Exception.Message)")
    $result = 1
} finally {
    if ($null -ne $kit) { $kit.Dispose() }
    if ($null -ne $work -and [IO.Directory]::Exists($work)) { [IO.Directory]::Delete($work,$true) }
}
exit $result
