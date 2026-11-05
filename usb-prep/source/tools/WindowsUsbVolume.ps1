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

        # Every eligible existing volume is selectable. Other partitions are untouched.
        foreach ($p in @(Get-Partition -DiskNumber $disk.Number -ErrorAction Stop)) {
            if ($p.IsSystem -or $p.IsBoot -or $p.IsHidden -or $p.IsOffline -or $p.IsReadOnly -or
                $p.DriveLetter -notmatch '^[D-Z]$') { continue }
            if ($disk.PartitionStyle -eq 'GPT' -and ([string]$p.GptType).Trim('{}') -ne 'ebd0a0a2-b9e5-4433-87c0-68b6b72699c7') { continue }
            if ($disk.PartitionStyle -eq 'MBR' -and [int]$p.MbrType -notin 1,4,6,7,11,12,14) { continue }

            $volumes = @(Get-Volume -Partition $p -ErrorAction Stop)
            if ($volumes.Count -ne 1) { continue }
            $v = $volumes[0]
            if ($v.DriveType -ne 'Removable' -or $v.DriveLetter -ne $p.DriveLetter -or
                $v.FileSystem -notin 'FAT32','exFAT' -or
                $v.Path -notmatch '^\\\\\?\\Volume\{[0-9A-Fa-f-]{36}\}\\$' -or $p.Size -lt 64MB) { continue }
            [pscustomobject]@{
                Letter = [string]$p.DriveLetter; Number = [uint32]$disk.Number; Size = [uint64]$disk.Size
                Id = $id; Path = $disk.Path; Serial = [string]$disk.SerialNumber
                Style = [string]$disk.PartitionStyle; Part = $p.PartitionNumber; Offset = $p.Offset; PartSize = $p.Size
                VolumePath = [string]$v.Path; FileSystem = [string]$v.FileSystem
                Label = [string]$v.FileSystemLabel; Volume = $v; Model = [string]$disk.FriendlyName
            }
        }
    }
}

function Confirm-UsbIdentity($selected, [uint32[]]$protectedDisks) {
    $matchesNow = @(Get-SafeUsbVolumes $protectedDisks | Where-Object { $_.Letter -eq $selected.Letter })
    if ($matchesNow.Count -eq 1) {
        $current = $matchesNow[0]
        foreach ($field in 'Number','Size','Id','Path','Serial','Style','Part','Offset','PartSize','VolumePath','FileSystem','Label') {
            if ($current.$field -cne $selected.$field) { throw 'USB 식별 정보가 바뀌었습니다. 작업을 중단합니다.' }
        }
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
        if ($parts.Count -ne 1) { throw 'PC 드라이브를 확인할 수 없어 작업을 중단합니다.' }
        [uint32]$parts[0].DiskNumber
    }
}

function Assert-PlainUsbPath([string]$root, [string]$path, [switch]$AllowMissing) {
    $rootInfo = [IO.DirectoryInfo]::new($root)
    if (!$rootInfo.Exists -or ($rootInfo.Attributes -band [IO.FileAttributes]::ReparsePoint)) {
        throw 'USB 루트가 일반 볼륨 경로가 아닙니다.'
    }
    $prefix = $rootInfo.FullName.TrimEnd([char[]]@('\','/')) + [IO.Path]::DirectorySeparatorChar
    $full = [IO.Path]::GetFullPath($path)
    if (($full.TrimEnd([char[]]@('\','/')) + [IO.Path]::DirectorySeparatorChar) -ceq $prefix) { return }
    if (!$full.StartsWith($prefix,[StringComparison]::OrdinalIgnoreCase)) { throw 'USB 볼륨 밖의 경로는 처리하지 않습니다.' }
    $cursor = $prefix
    foreach ($part in $full.Substring($prefix.Length).Split([char[]]@('\','/'),[StringSplitOptions]::RemoveEmptyEntries)) {
        $cursor = [IO.Path]::Combine($cursor,$part)
        try { $attributes = [IO.File]::GetAttributes($cursor) }
        catch [IO.FileNotFoundException] { if ($AllowMissing) { return }; throw }
        catch [IO.DirectoryNotFoundException] { if ($AllowMissing) { return }; throw }
        if ($attributes -band [IO.FileAttributes]::ReparsePoint) {
            throw "USB에 연결된 폴더/파일 또는 다른 볼륨의 탑재 지점이 있습니다: $cursor"
        }
    }
}

function Get-UsbContentPlan([string]$root) {
    Assert-PlainUsbPath $root $root
    $rootInfo = [IO.DirectoryInfo]::new($root)
    $files = [Collections.Generic.List[IO.FileInfo]]::new()
    $directories = [Collections.Generic.List[IO.DirectoryInfo]]::new()
    $preserved = [Collections.Generic.List[string]]::new()
    $pending = [Collections.Generic.Stack[IO.DirectoryInfo]]::new()
    $pending.Push($rootInfo)
    while ($pending.Count) {
        $directory = $pending.Pop()
        Assert-PlainUsbPath $root $directory.FullName
        foreach ($item in $directory.GetFileSystemInfos()) {
            # Enumerates hidden files too; never recursively follows reparse points.
            if ($item.Attributes -band [IO.FileAttributes]::ReparsePoint) {
                throw "연결된 폴더/파일이 있어 기존 파일을 지우지 않았습니다: $($item.FullName)"
            }
            if ($item -is [IO.DirectoryInfo]) {
                # Windows owns this protected metadata directory. Do not take
                # ownership or change its ACL. No other folder is exempted.
                if ($directory.FullName -ceq $rootInfo.FullName -and $item.Name -ieq 'System Volume Information') {
                    $preserved.Add($item.Name)
                    continue
                }
                $directories.Add($item)
                $pending.Push($item)
            } else {
                $files.Add($item)
            }
        }
    }
    [pscustomobject]@{ Files = $files.ToArray(); Directories = $directories.ToArray(); Preserved = $preserved.ToArray() }
}

function Remove-UsbContents($selected, [uint32[]]$protectedDisks) {
    $current = Confirm-UsbIdentity $selected $protectedDisks
    $root = $current.VolumePath
    # Inspect the complete tree before deleting anything. Paths remain bound to
    # this volume GUID, not to a drive letter another device could acquire.
    $plan = Get-UsbContentPlan $root
    $null = Confirm-UsbIdentity $current $protectedDisks
    $descriptor = [IO.Path]::Combine($root,'NEMESIS.data')
    $orderedFiles = @($plan.Files | Sort-Object @{Expression={if ($_.FullName -ieq $descriptor) { 0 } else { 1 }}},FullName)
    foreach ($file in $orderedFiles) {
        Assert-PlainUsbPath $root $file.FullName
        $attributes = [IO.File]::GetAttributes($file.FullName)
        if ($attributes -band [IO.FileAttributes]::ReadOnly) {
            [IO.File]::SetAttributes($file.FullName,[IO.FileAttributes]([int]$attributes -band (-bnot [int][IO.FileAttributes]::ReadOnly)))
        }
        [IO.File]::Delete($file.FullName)
    }
    foreach ($directory in @($plan.Directories | Sort-Object {$_.FullName.Length} -Descending)) {
        Assert-PlainUsbPath $root $directory.FullName
        $attributes = [IO.File]::GetAttributes($directory.FullName)
        if ($attributes -band [IO.FileAttributes]::ReadOnly) {
            [IO.File]::SetAttributes($directory.FullName,[IO.FileAttributes]([int]$attributes -band (-bnot [int][IO.FileAttributes]::ReadOnly)))
        }
        [IO.Directory]::Delete($directory.FullName,$false)
    }
    $null = Confirm-UsbIdentity $current $protectedDisks
    $remaining = Get-UsbContentPlan $root
    if ($remaining.Files.Count -or $remaining.Directories.Count) {
        throw '기존 USB 파일이 남아 있어 설치 파일을 복사하지 않습니다.'
    }
    if ($remaining.Preserved.Count) {
        Write-Host 'Windows 보호 메타데이터 폴더 System Volume Information은 그대로 유지했습니다.'
    }
}

function Assert-DeviceFile([string]$file, [string]$digest) {
    $info = [IO.FileInfo]::new($file)
    if (!$info.Exists -or ($info.Attributes -band [IO.FileAttributes]::ReparsePoint)) { throw "USB 파일을 확인할 수 없습니다: $file" }
    $inputFile = [IO.File]::OpenRead($file)
    try {
        if ((Get-FileHash -InputStream $inputFile -Algorithm SHA256).Hash -ne $digest) { throw "USB 복사 검증 실패: $file" }
    } finally { $inputFile.Dispose() }
}

function Assert-UsbPayloadTree([string]$root, [switch]$WithoutDescriptor) {
    $plan = Get-UsbContentPlan $root
    $prefix = ([IO.DirectoryInfo]::new($root)).FullName.TrimEnd([char[]]@('\','/')) + [IO.Path]::DirectorySeparatorChar
    foreach ($file in $plan.Files) {
        $relative = $file.FullName.Substring($prefix.Length).Replace('\','/')
        if (!$expected.ContainsKey($relative) -or ($WithoutDescriptor -and $relative -ceq 'NEMESIS.data')) {
            throw "USB에 예상하지 않은 파일이 있습니다: $relative"
        }
        Assert-DeviceFile $file.FullName $expected[$relative]
    }
    foreach ($directory in $plan.Directories) {
        $relative = $directory.FullName.Substring($prefix.Length).Replace('\','/')
        if (!$folders.Contains($relative)) { throw "USB에 예상하지 않은 폴더가 있습니다: $relative" }
    }
    $count = $expected.Count
    if ($WithoutDescriptor) { $count-- }
    if ($plan.Files.Count -ne $count) { throw 'USB 설치 파일 일부가 빠졌습니다.' }
}

function Write-SelectedVolume($selected, [uint32[]]$protectedDisks) {
    # All downloads and source verification finish before old user files are removed.
    Assert-ReadyTree $ready
    $current = Confirm-UsbIdentity $selected $protectedDisks
    $root = $current.VolumePath
    if (![IO.Directory]::Exists($root)) { throw '이 Windows에서 선택한 USB 볼륨 경로를 열 수 없습니다.' }
    Remove-UsbContents $current $protectedDisks
    $null = Confirm-UsbIdentity $current $protectedDisks
    foreach ($name in $expected.Keys) {
        if ($name -ceq 'NEMESIS.data') { continue }
        $dest = [IO.Path]::Combine($root,$name.Replace([char]'/',[IO.Path]::DirectorySeparatorChar))
        Assert-PlainUsbPath $root $dest -AllowMissing
        [void][IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($dest))
        Assert-PlainUsbPath $root ([IO.Path]::GetDirectoryName($dest))
        [IO.File]::Copy([IO.Path]::Combine($ready,$name),$dest,$false)
        Assert-DeviceFile $dest $expected[$name]
    }
    Assert-UsbPayloadTree $root -WithoutDescriptor
    $null = Confirm-UsbIdentity $current $protectedDisks
    # Activate the installer only after every other file passed its USB readback.
    $descriptor = [IO.Path]::Combine($root,'NEMESIS.data')
    $tempDescriptor = [IO.Path]::Combine($root,'.neev-descriptor.pending')
    Assert-PlainUsbPath $root $tempDescriptor -AllowMissing
    [IO.File]::Copy([IO.Path]::Combine($ready,'NEMESIS.data'),$tempDescriptor,$false)
    Assert-DeviceFile $tempDescriptor $expected['NEMESIS.data']
    $null = Confirm-UsbIdentity $current $protectedDisks
    [IO.File]::Move($tempDescriptor,$descriptor)
    Assert-DeviceFile $descriptor $expected['NEMESIS.data']
    Assert-UsbPayloadTree $root
    Write-Host "USB 준비 완료: $($selected.Letter): ($($current.FileSystem)), 설치 파일 31개 확인. 파티션과 파일 시스템은 유지했습니다. Windows에서 안전하게 제거하세요."
}

function Start-UsbFileRefresh {
    if ($env:OS -ne 'Windows_NT') { throw 'USB 파일 교체는 Windows 10/11에서 실행하세요.' }
    $user = [Security.Principal.WindowsPrincipal]::new([Security.Principal.WindowsIdentity]::GetCurrent())
    if (!$user.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
        throw 'Prepare-USB-Windows.cmd를 마우스 오른쪽 버튼으로 눌러 관리자 권한으로 실행하세요. USB_READY 파일은 준비됐습니다.'
    }
    $protected = @(Get-ProtectedDiskNumbers)
    $choices = @(Get-SafeUsbVolumes $protected)
    $selected = Select-UsbVolume $choices
    if ($null -eq $selected) { return }
    Write-SelectedVolume $selected $protected
}

function Select-UsbVolume([object[]]$choices) {
    if ($choices.Count -eq 0) { throw '대상 USB가 없습니다. 드라이브 문자가 있는 FAT32/exFAT 이동식 USB 볼륨을 연결하세요.' }
    Write-Host '파일을 교체할 USB 볼륨을 선택하세요. 선택한 볼륨의 기존 파일과 폴더가 삭제됩니다. Enter로 취소합니다.'
    Write-Host '파티션, 파일 시스템, 볼륨 이름은 유지합니다. Windows 보호 폴더 System Volume Information은 남겨 둡니다.'
    for ($i=0; $i -lt $choices.Count; $i++) {
        $c = $choices[$i]
        Write-Host ('[{0}] {1}:  {2}  {3:N1} GiB  {4}  {5}  (디스크 {6}, 파티션 {7})' -f ($i+1),$c.Letter,$c.Model,($c.PartSize / 1GB),$c.Label,$c.FileSystem,$c.Number,$c.Part)
    }
    $inputNumber = Read-Host 'USB 볼륨 번호'
    if (!$inputNumber) { Write-Host '취소했습니다.'; return }
    $n = 0
    if (![int]::TryParse($inputNumber,[ref]$n) -or $n -lt 1 -or $n -gt $choices.Count) { throw '목록에 있는 번호를 선택하세요.' }
    $selected = $choices[$n-1]
    $phrase = 'DELETE ' + $selected.Letter + ':'
    Write-Host ("선택한 USB {0}: {1} ({2:N1} GiB)의 기존 파일과 폴더를 삭제하고 설치 파일을 복사합니다." -f $selected.Letter,$selected.Model,($selected.PartSize / 1GB))
    if ((Read-Host "계속하려면 정확히 $phrase 입력") -cne $phrase) { Write-Host '취소했습니다.'; return }
    return $selected
}
