#requires -version 5.1
param([Parameter(Mandatory=$true)][string]$Script)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version 2.0
if ($env:OS -ne 'Windows_NT') { throw 'This integration test requires Windows.' }
$principal = [Security.Principal.WindowsPrincipal]::new([Security.Principal.WindowsIdentity]::GetCurrent())
if (!$principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) { throw 'Run this disposable-VHD test as administrator.' }
. $Script
$tokens = $null; $parseErrors = $null
$prepAst = [Management.Automation.Language.Parser]::ParseFile((Join-Path (Split-Path $Script) 'Prepare-USB.ps1'), [ref]$tokens, [ref]$parseErrors)
if ($parseErrors.Count) { throw 'Prepare-USB.ps1 failed to parse.' }
$readyFunction = $prepAst.Find({param($node) $node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq 'Assert-ReadyTree'}, $true)
if ($null -eq $readyFunction) { throw 'Assert-ReadyTree not found.' }
Invoke-Expression $readyFunction.Extent.Text
function Assert([bool]$condition, [string]$message) { if (!$condition) { throw $message } }
function ExpectFailure([scriptblock]$action, [string]$message) {
    $failed = $false
    try { & $action | Out-Null } catch { $failed = $true }
    Assert $failed $message
}
function Put([string]$path, [string]$contents) {
    [void][IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($path))
    [IO.File]::WriteAllText($path, $contents)
}
$script:failAfterPayload = $false
$script:observedPayloadBeforeDescriptor = $false
$script:payloadPhase = $false
$script:realRemove = (Get-Item Function:Remove-UsbContents).ScriptBlock
function Remove-UsbContents($selected, [uint32[]]$protectedDisks) {
    & $script:realRemove $selected $protectedDisks
    $script:payloadPhase = $true
}
# Discovery is deliberately stubbed: the only writable volume is our newly
# created VHD. Check the VHD association and GUID before every identity return.
function Confirm-UsbIdentity($selected, [uint32[]]$protectedDisks) {
    $image = Get-DiskImage -ImagePath $script:testImage -ErrorAction Stop
    Assert $image.Attached 'Test VHD is no longer attached.'
    $disk = $image | Get-Disk -ErrorAction Stop
    Assert ($disk.Number -eq $selected.Number -and !$disk.IsBoot -and !$disk.IsSystem) 'Test VHD disk identity changed.'
    $partition = Get-Partition -DiskNumber $disk.Number -PartitionNumber $selected.Part -ErrorAction Stop
    $volume = Get-Volume -Partition $partition -ErrorAction Stop
    Assert ($volume.Path -ceq $selected.VolumePath -and [string]$volume.DriveLetter -ceq $selected.Letter) 'Test VHD GUID or drive letter changed.'
    $payload = [IO.Path]::Combine($selected.VolumePath, 'NEMESIS.sh')
    $descriptor = [IO.Path]::Combine($selected.VolumePath, 'NEMESIS.data')
    if ($script:payloadPhase -and [IO.File]::Exists($payload) -and [IO.File]::ReadAllText($payload) -ceq 'new NEMESIS.sh') {
        Assert (![IO.File]::Exists($descriptor)) 'Installer descriptor appeared before the final identity check.'
        $script:observedPayloadBeforeDescriptor = $true
        if ($script:failAfterPayload) { throw 'Injected identity failure after payload copy.' }
    }
    return $selected
}
$scratch = Join-Path ([IO.Path]::GetTempPath()) ('carplay-guid-test-' + [guid]::NewGuid().ToString('N'))
[void][IO.Directory]::CreateDirectory($scratch)
$images = [Collections.Generic.List[string]]::new()
try {
    $ready = Join-Path $scratch 'ready'
    [void][IO.Directory]::CreateDirectory($ready)
    $expected = [Collections.Generic.Dictionary[string,string]]::new([StringComparer]::Ordinal)
    $folders = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    # ASCII root files plus spaces/non-ASCII and multiple missing parent levels.
    foreach ($name in 'NEMESIS.data','USB_AUTO.sh','NEMESIS.sh','LICENSE','packages/usbwifi.hotspot.zip','NEEV_CP/nested/한글 폴더/payload.txt') {
        $path = Join-Path $ready $name
        Put $path ('new ' + $name)
        $expected.Add($name, (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash)
        $parent = $name
        while ($parent.Contains('/')) {
            $parent = $parent.Substring(0, $parent.LastIndexOf('/'))
            [void]$folders.Add($parent)
        }
    }
    foreach ($fileSystem in 'FAT32','exFAT') {
        $script:testImage = Join-Path $scratch ($fileSystem + '.vhd')
        $images.Add($script:testImage)
        $diskpartScript = Join-Path $scratch ($fileSystem + '-create.txt')
        # Diskpart only creates/attaches a unique test file; no disk is selected
        # by a guessed number, and no existing physical disk is formatted.
        $commands = 'create vdisk file="{0}" maximum=128 type=expandable' -f $script:testImage
        $commands += "`r`n" + ('select vdisk file="{0}"' -f $script:testImage) + "`r`nattach vdisk`r`nexit`r`n"
        [IO.File]::WriteAllText($diskpartScript, $commands, [Text.Encoding]::ASCII)
        & diskpart.exe /s $diskpartScript | Out-Host
        if ($LASTEXITCODE -ne 0) { throw 'Diskpart failed to create the disposable VHD.' }
        $image = Get-DiskImage -ImagePath $script:testImage -ErrorAction Stop
        Assert $image.Attached 'Disposable VHD was not attached.'
        $disk = $image | Get-Disk -ErrorAction Stop
        Assert (!$disk.IsBoot -and !$disk.IsSystem -and $disk.Size -le 256MB -and $disk.PartitionStyle -eq 'RAW') 'Refusing to initialize an unexpected disk.'
        Assert ([string]$disk.BusType -in 'File Backed Virtual','Virtual') 'Refusing to initialize a non-virtual disk.'
        $null = Initialize-Disk -InputObject $disk -PartitionStyle MBR -PassThru -ErrorAction Stop
        $partition = New-Partition -DiskNumber $disk.Number -UseMaximumSize -AssignDriveLetter -ErrorAction Stop
        $volume = Format-Volume -Partition $partition -FileSystem $fileSystem -NewFileSystemLabel 'CP_TEST' -Force -Confirm:$false -ErrorAction Stop
        $volume = Get-Volume -Partition $partition -ErrorAction Stop
        $root = [string]$volume.Path
        Assert ($root -match '^\\\\\?\\Volume\{[0-9A-Fa-f-]{36}\}\\$') 'Expected a real Windows volume GUID root.'
        $driveRoot = [string]$volume.DriveLetter + ':\'
        $selected = [pscustomobject]@{Number=$disk.Number;Part=$partition.PartitionNumber;VolumePath=$root;Letter=[string]$volume.DriveLetter;FileSystem=[string]$volume.FileSystem}
        Write-Host ("Testing {0} on PowerShell {1} via GUID {2}" -f $fileSystem, $PSVersionTable.PSVersion, $root)
        # Reproduce the exact pre-fix expression on the real GUID root. Newer
        # .NET runtimes may already handle it; Windows PowerShell 5.1 must expose
        # the reported bug for this test to claim native regression coverage.
        $oldFailed = $false
        try { [void][IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName([IO.Path]::Combine($root,'NEMESIS.sh'))) }
        catch { $oldFailed = $true; Write-Host ('Original expression fails: ' + $_.Exception.Message) }
        if ($PSVersionTable.PSVersion.Major -eq 5) { Assert $oldFailed 'Original GUID-root CreateDirectory failure was not reproduced.' }
        Put (Join-Path $driveRoot 'System Volume Information/test-metadata') 'preserve metadata'
        $journalPath = Join-Path $driveRoot '$TXRAJNL.DAT'
        # A directory with the metadata file's name is not an exemption.
        Put (Join-Path $journalPath 'ordinary.txt') 'ordinary folder content'
        $shapePlan = Get-UsbContentPlan $driveRoot
        Assert ($shapePlan.Preserved -cnotcontains '$TXRAJNL.DAT') 'A metadata-named directory was exempted.'
        Assert (@($shapePlan.Directories | Where-Object { $_.FullName -ceq $journalPath }).Count -eq 1) 'Metadata-named directory was omitted from cleanup.'
        [IO.Directory]::Delete($journalPath, $true)
        Put $journalPath ('preserve journal ' * 65536)
        [IO.File]::SetAttributes($journalPath, [IO.FileAttributes]([int][IO.FileAttributes]::ReadOnly -bor [int][IO.FileAttributes]::Hidden))
        $journalAttributes = [IO.File]::GetAttributes($journalPath)
        $journalHash = (Get-FileHash -LiteralPath $journalPath -Algorithm SHA256).Hash
        Put (Join-Path $driveRoot 'old/$TXRAJNL.DAT') 'nested ordinary file'
        Put (Join-Path $driveRoot '$TXRAJNL.DAT.bak') 'lookalike ordinary file'
        $journalPlan = Get-UsbContentPlan $driveRoot
        Assert ($journalPlan.Preserved -ccontains '$TXRAJNL.DAT') 'Root journal was not preserved.'
        Assert (@($journalPlan.Files | Where-Object { $_.FullName -ceq $journalPath }).Count -eq 0) 'Root journal remained a deletion target.'
        Assert (@($journalPlan.Files | Where-Object { $_.Name -ceq '$TXRAJNL.DAT' }).Count -eq 1) 'Nested journal-named file was incorrectly exempted.'
        Assert (@($journalPlan.Files | Where-Object { $_.Name -ceq '$TXRAJNL.DAT.bak' }).Count -eq 1) 'Lookalike filename was incorrectly exempted.'
        Put (Join-Path $driveRoot 'old/old.txt') 'delete old'
        Put (Join-Path $driveRoot '.hidden') 'delete hidden'
        Put (Join-Path $driveRoot 'NEMESIS.data') 'old trigger'
        $script:payloadPhase = $false
        # Invalid staged input must be refused before old USB data is removed.
        $sourceLicense = Join-Path $ready 'LICENSE'
        [IO.File]::WriteAllText($sourceLicense, 'corrupted')
        ExpectFailure { Write-SelectedVolume $selected @() } 'Corrupt staged input was accepted.'
        Assert ([IO.File]::Exists((Join-Path $driveRoot 'old/old.txt'))) 'Old data was removed before source validation.'
        [IO.File]::WriteAllText($sourceLicense, 'new LICENSE')
        # Fail after payload copy: descriptor must remain absent until a retry.
        $script:payloadPhase = $false
        $script:failAfterPayload = $true
        $script:observedPayloadBeforeDescriptor = $false
        ExpectFailure { Write-SelectedVolume $selected @() } 'Injected identity failure did not stop the writer.'
        Assert $script:observedPayloadBeforeDescriptor 'Writer failed before reaching the injected failure.'
        Assert (![IO.File]::Exists([IO.Path]::Combine($root,'NEMESIS.data'))) 'Failed preparation published the installer descriptor.'
        Assert (![IO.File]::Exists([IO.Path]::Combine($root,'USB_AUTO.sh'))) 'Failed payload preparation published USB_AUTO.sh.'
        $script:failAfterPayload = $false
        # Retry and then repeat a complete write with the previous payload intact.
        for ($iteration=1; $iteration -le 2; $iteration++) {
            $script:payloadPhase = $false
            $script:observedPayloadBeforeDescriptor = $false
            Write-SelectedVolume $selected @() | Out-Host
            Assert $script:observedPayloadBeforeDescriptor 'Descriptor-last stage was not observed.'
            foreach ($name in $expected.Keys) {
                Assert ((Get-FileHash -LiteralPath (Join-Path $driveRoot $name) -Algorithm SHA256).Hash -eq $expected[$name]) ('Hash mismatch: ' + $name)
            }
            Assert (![IO.File]::Exists((Join-Path $driveRoot 'old/old.txt'))) 'Old file survived.'
            Assert (![IO.File]::Exists((Join-Path $driveRoot '.hidden'))) 'Old hidden file survived.'
            Assert ([IO.File]::ReadAllText((Join-Path $driveRoot 'System Volume Information/test-metadata')) -ceq 'preserve metadata') 'OS metadata changed.'
            Assert ((Get-FileHash -LiteralPath $journalPath -Algorithm SHA256).Hash -ceq $journalHash) 'Root journal contents changed.'
            Assert ([IO.File]::GetAttributes($journalPath) -eq $journalAttributes) 'Root journal attributes changed.'
            Assert (![IO.File]::Exists((Join-Path $driveRoot 'old/$TXRAJNL.DAT'))) 'Nested journal-named file survived cleanup.'
            Assert (![IO.File]::Exists((Join-Path $driveRoot '$TXRAJNL.DAT.bak'))) 'Lookalike filename survived cleanup.'
            $after = Get-Volume -Partition (Get-Partition -DiskNumber $disk.Number -PartitionNumber $partition.PartitionNumber)
            Assert ($after.Path -ceq $root -and $after.FileSystem -ieq $fileSystem -and $after.FileSystemLabel -ceq 'CP_TEST') 'Writer changed volume identity, filesystem or label.'
        }
        Dismount-DiskImage -ImagePath $script:testImage -ErrorAction Stop
        Assert (!(Get-DiskImage -ImagePath $script:testImage).Attached) 'Test VHD did not detach.'
        Write-Host ('PASS: ' + $fileSystem + ' real GUID I/O, nested paths, staged-input rejection, descriptor-last failure, retry and repeated write.')
    }
} finally {
    $allDetached = $true
    foreach ($imagePath in $images) {
        if ([IO.File]::Exists($imagePath)) {
            try {
                $image = Get-DiskImage -ImagePath $imagePath -ErrorAction Stop
                if ($image.Attached) { Dismount-DiskImage -ImagePath $imagePath -ErrorAction Stop }
                if ((Get-DiskImage -ImagePath $imagePath -ErrorAction Stop).Attached) { throw 'VHD is still attached.' }
            } catch { $allDetached = $false; Write-Warning ('Retaining test directory because VHD cleanup failed: ' + $_.Exception.Message) }
        }
    }
    if ($allDetached -and [IO.Directory]::Exists($scratch)) { [IO.Directory]::Delete($scratch,$true) }
}
