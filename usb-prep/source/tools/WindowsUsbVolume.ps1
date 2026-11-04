# This file is loaded by Prepare-USB.ps1 after the 31 release inputs have been verified.
function Get-SafeUsbVolumes([uint32[]]$protectedDisks) {
    $disks = @(Get-Disk -ErrorAction Stop)
    foreach ($disk in $disks) {
        if ($disk.BusType -ne 'USB' -or $disk.IsBoot -or $disk.IsSystem -or $disk.BootFromDisk -or
            $disk.IsOffline -or $disk.IsReadOnly -or $disk.IsClustered -or
            $disk.Number -in $protectedDisks -or $disk.PartitionStyle -notin 'MBR','GPT') { continue }
        $id = [string]$disk.UniqueId
        if ([string]::IsNullOrWhiteSpace($disk.Path) -or [string]::IsNullOrWhiteSpace($id) -or
            $id -match '^[\s0:-]+$' -or @($disks | Where-Object { $_.UniqueId -eq $id }).Count -ne 1) { continue }

        $parts = @(Get-Partition -DiskNumber $disk.Number -ErrorAction Stop)
        if ($parts.Count -ne 1) { continue }
        $p = $parts[0]
        if ($p.IsSystem -or $p.IsBoot -or $p.IsHidden -or $p.IsOffline -or $p.IsReadOnly -or
            $p.DriveLetter -notmatch '^[D-Z]$') { continue }
        if ($disk.PartitionStyle -eq 'GPT' -and ([string]$p.GptType).Trim('{}') -ne 'ebd0a0a2-b9e5-4433-87c0-68b6b72699c7') { continue }
        if ($disk.PartitionStyle -eq 'MBR' -and [int]$p.MbrType -notin 1,4,6,7,11,12,14) { continue }

        $volumes = @(Get-Volume -Partition $p -ErrorAction Stop)
        if ($volumes.Count -ne 1) { continue }
        $v = $volumes[0]
        if ($v.DriveType -ne 'Removable' -or $v.DriveLetter -ne $p.DriveLetter -or
            $v.Path -notmatch '^\\\\\?\\Volume\{[0-9A-Fa-f-]{36}\}\\$' -or $p.Size -lt 64MB) { continue }
        [pscustomobject]@{
            Letter = [string]$p.DriveLetter; Number = [uint32]$disk.Number; Size = [uint64]$disk.Size
            Id = $id; Path = $disk.Path; Serial = [string]$disk.SerialNumber
            Style = [string]$disk.PartitionStyle; Part = $p.PartitionNumber; Offset = $p.Offset; PartSize = $p.Size
            VolumePath = [string]$v.Path; Volume = $v; Model = [string]$disk.FriendlyName
        }
    }
}

function Confirm-UsbIdentity($selected, [uint32[]]$protectedDisks, [switch]$AfterFormat) {
    $matchesNow = @(Get-SafeUsbVolumes $protectedDisks | Where-Object { $_.Letter -eq $selected.Letter })
    if ($matchesNow.Count -eq 1) {
        $current = $matchesNow[0]
        foreach ($field in 'Number','Size','Id','Path','Serial','Style','Part','Offset','PartSize') {
            if ($current.$field -cne $selected.$field) { throw 'USB 신원이 바뀌었습니다. 작업을 중단합니다.' }
        }
        # Windows may assign a new volume GUID when the existing partition is formatted.
        if (!$AfterFormat -and $current.VolumePath -cne $selected.VolumePath) { throw 'USB 볼륨 신원이 바뀌었습니다.' }
        return $current
    }
    throw '선택한 USB 볼륨을 확인할 수 없습니다.'
}

function Get-ProtectedDiskNumbers {
    # Resolve physical disks rather than comparing drive letters. A junction or
    # volume mount point in the extracted build would invalidate this mapping.
    $here = [IO.DirectoryInfo]::new($release)
    while ($null -ne $here) {
        if ($here.Attributes -band [IO.FileAttributes]::ReparsePoint) { throw '배포 ZIP을 PC 내부 드라이브의 일반 폴더에 풀어 주세요.' }
        $here = $here.Parent
    }
    $letters = [IO.Path]::GetPathRoot($release), $env:SystemDrive
    foreach ($path in $letters) {
        if ($path -notmatch '^[A-Z]:\\?$') { throw '배포 ZIP을 PC 내부 드라이브에 풀어 주세요.' }
        $parts = @(Get-Partition -DriveLetter $path[0] -ErrorAction Stop)
        if ($parts.Count -ne 1) { throw 'PC 드라이브를 확인할 수 없어 포맷을 중단합니다.' }
        [uint32]$parts[0].DiskNumber
    }
}

function Assert-DeviceFile([string]$file, [string]$digest) {
    # A volume GUID path keeps writes bound to the selected volume even if a
    # different device takes its drive letter during a removal/replug.
    $info = [IO.FileInfo]::new($file)
    if (!$info.Exists -or ($info.Attributes -band [IO.FileAttributes]::ReparsePoint)) { throw "USB 파일을 확인할 수 없습니다: $file" }
    $inputFile = [IO.File]::OpenRead($file)
    try {
        if ((Get-FileHash -InputStream $inputFile -Algorithm SHA256).Hash -ne $digest) { throw "USB 복사 검증 실패: $file" }
    } finally { $inputFile.Dispose() }
}

function Write-SelectedVolume($selected,[uint32[]]$protectedDisks,[string]$fs) {
    $current = Confirm-UsbIdentity $selected $protectedDisks
    $root = $current.VolumePath
    # Refuse systems without device-path support before formatting anything.
    if (![IO.Directory]::Exists($root)) { throw '이 Windows에서 선택한 USB 볼륨 경로를 열 수 없습니다.' }
    $null = Format-Volume -InputObject $current.Volume -FileSystem $fs -NewFileSystemLabel 'CARPLAY' -Confirm:$false -ErrorAction Stop
    $current = Confirm-UsbIdentity $selected $protectedDisks -AfterFormat
    $root = $current.VolumePath
    if ($current.Volume.FileSystem -ne $fs -or $current.Volume.FileSystemLabel -ne 'CARPLAY') { throw '포맷 결과를 확인할 수 없습니다.' }
    # A freshly formatted Windows volume may already have OS directories.
    foreach ($item in ([IO.DirectoryInfo]::new($root)).GetFileSystemInfos()) {
        if ($item.Name -notin 'System Volume Information','$RECYCLE.BIN','IndexerVolumeGuid') {
            throw '포맷한 USB에 예상하지 않은 파일이 있습니다. 복사를 중단합니다.'
        }
    }
    foreach ($name in $expected.Keys) {
        if ($name -ceq 'NEMESIS.data') { continue }
        $dest = [IO.Path]::Combine($root,$name.Replace([char]'/',[IO.Path]::DirectorySeparatorChar))
        [void][IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($dest))
        [IO.File]::Copy([IO.Path]::Combine($ready,$name),$dest,$false)
        Assert-DeviceFile $dest $expected[$name]
    }
    $null = Confirm-UsbIdentity $current $protectedDisks
    # Activate the installer only after every other file passed its USB readback.
    $descriptor = [IO.Path]::Combine($root,'NEMESIS.data')
    $tempDescriptor = [IO.Path]::Combine($root,'.neev-descriptor.pending')
    [IO.File]::Copy([IO.Path]::Combine($ready,'NEMESIS.data'),$tempDescriptor,$false)
    Assert-DeviceFile $tempDescriptor $expected['NEMESIS.data']
    [IO.File]::Move($tempDescriptor,$descriptor)
    Assert-DeviceFile $descriptor $expected['NEMESIS.data']
    Write-Host "USB 제작 완료: $($selected.Letter): ($fs), 설치 파일 31개 확인. Windows에서 안전하게 제거하세요."
}

function Start-UsbFormatter {
    if ($env:OS -ne 'Windows_NT') { throw 'USB 포맷은 Windows 10/11에서 실행하세요.' }
    $user = [Security.Principal.WindowsPrincipal]::new([Security.Principal.WindowsIdentity]::GetCurrent())
    if (!$user.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
        throw 'Prepare-USB-Windows.cmd를 마우스 오른쪽 버튼으로 눌러 관리자 권한으로 실행하세요. USB_READY 파일은 준비됐습니다.'
    }
    $protected = @(Get-ProtectedDiskNumbers)
    $choices = @(Get-SafeUsbVolumes $protected)
    $selected = Select-UsbVolume $choices
    if ($null -eq $selected) { return }
    Assert-ReadyTree $ready
    $fs = if ($selected.PartSize -le 32GB) { 'FAT32' } else { 'exFAT' }
    Write-SelectedVolume $selected $protected $fs
}

function Select-UsbVolume([object[]]$choices) {
    if ($choices.Count -eq 0) { throw '대상 USB가 없습니다. 드라이브 문자가 있는 단일 파티션 이동식 USB 메모리를 연결하세요.' }
    Write-Host '포맷할 USB를 선택하세요. 이 USB 안의 파일은 모두 지워집니다. 취소하려면 Enter를 누르세요.'
    for ($i=0; $i -lt $choices.Count; $i++) {
        $c = $choices[$i]
        Write-Host ('[{0}] {1}:  {2}  {3:N1} GiB  {4}  (디스크 {5})' -f ($i+1),$c.Letter,$c.Model,($c.Size / 1GB),$c.Volume.FileSystemLabel,$c.Number)
    }
    $inputNumber = Read-Host 'USB 번호'
    if (!$inputNumber) { Write-Host '취소했습니다.'; return }
    $n = 0
    if (![int]::TryParse($inputNumber,[ref]$n) -or $n -lt 1 -or $n -gt $choices.Count) { throw '목록에 있는 번호를 선택하세요.' }
    $selected = $choices[$n-1]
    $fs = if ($selected.PartSize -le 32GB) { 'FAT32' } else { 'exFAT' }
    $phrase = 'FORMAT ' + $selected.Letter + ':'
    Write-Host ("선택한 USB {0}: {1} ({2:N1} GiB)의 모든 파일을 지우고 {3}으로 포맷합니다." -f $selected.Letter,$selected.Model,($selected.Size / 1GB),$fs)
    if ((Read-Host "계속하려면 정확히 $phrase 입력") -cne $phrase) { Write-Host '취소했습니다.'; return }
    return $selected
}
