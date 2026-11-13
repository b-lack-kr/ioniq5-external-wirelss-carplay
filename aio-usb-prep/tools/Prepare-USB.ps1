#requires -version 5.1
param([switch]$PrepareOnly)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version 2.0
$release = [IO.Directory]::GetParent($PSScriptRoot).FullName
$expected = [Collections.Generic.Dictionary[string,string]]::new([StringComparer]::Ordinal)
$folders = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
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


$work=$null
$result=0
try {
 $work=Join-Path $release ('.prepare-usb-'+[Guid]::NewGuid().ToString('N'))
 [void][IO.Directory]::CreateDirectory($work)
 $stage=Join-Path $work 'USB_READY'
 . (Join-Path $PSScriptRoot 'Assemble-USB.ps1')
 New-UsbPayload $stage $work $release
 $ready=Join-Path $release 'USB_READY'
 foreach($item in Get-ChildItem -LiteralPath $stage -Recurse -Force) {
  if($item.Attributes -band [IO.FileAttributes]::ReparsePoint){throw '연결된 파일은 사용할 수 없습니다.'}
  $rel=$item.FullName.Substring($stage.Length+1).Replace('\','/')
  if($item -is [IO.DirectoryInfo]) {[void]$folders.Add($rel)}
  else {$expected.Add($rel,(Get-FileHash -LiteralPath $item.FullName -Algorithm SHA256).Hash)}
 }
 Assert-ReadyTree $stage
 if(Test-Path -LiteralPath $ready){Assert-ReadyTree $ready}
 else {[IO.Directory]::Move($stage,$ready)}
 Write-Host "준비 완료: $ready"
 if(!$PrepareOnly){. (Join-Path $PSScriptRoot 'WindowsUsbVolume.ps1');Start-UsbFileRefresh}
} catch {[Console]::Error.WriteLine("준비 실패: $($_.Exception.Message)");$result=1}
finally {if($null -ne $work -and [IO.Directory]::Exists($work)){[IO.Directory]::Delete($work,$true)}}
exit $result
