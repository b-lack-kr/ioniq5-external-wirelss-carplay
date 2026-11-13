param([string]$Writer = (Join-Path $PSScriptRoot '../tools/WindowsUsbVolume.ps1'))
$ErrorActionPreference='Stop'
. $Writer
$tempRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath())
$root = Join-Path $tempRoot ('aio-winmock-' + [Guid]::NewGuid().ToString('N'))
[IO.Directory]::CreateDirectory($root) | Out-Null
function Check($b,$description) { if (!$b) { throw "FAIL $description" }; Write-Output "PASS $description" }
function ExpectsError([scriptblock]$f,[string]$description) { $caught=$false; try { & $f | Out-Null } catch { $caught=$true }; Check $caught $description }
# These stubs prevent any system volume, partition or disk access.
function Get-Disk { param($ErrorAction) return $script:fakeDisks }
function Get-Partition { param($DiskNumber,$DriveLetter,$ErrorAction) return $script:fakePartitions }
function Get-Volume { param($Partition,$ErrorAction) return $script:fakeVolumes }
function MakeDisk($n,$id,$bus='USB',$boot=$false) { [pscustomobject]@{BusType=$bus;IsBoot=$boot;IsSystem=$false;BootFromDisk=$false;IsOffline=$false;IsReadOnly=$false;IsClustered=$false;Number=$n;PartitionStyle='GPT';UniqueId=$id;Path="fake-$n";Size=1000000000;SerialNumber='fake';FriendlyName='fake'} }
$script:fakeDisks=@(MakeDisk 42 'fake-unique-id')
$script:fakePartitions=@([pscustomobject]@{IsSystem=$false;IsBoot=$false;IsHidden=$false;IsOffline=$false;IsReadOnly=$false;DriveLetter='E';GptType='ebd0a0a2-b9e5-4433-87c0-68b6b72699c7';Size=1000000000;PartitionNumber=1;Offset=1024})
$script:fakeVolumes=@([pscustomobject]@{DriveType='Removable';DriveLetter='E';FileSystem='exFAT';Path='\\?\Volume{01234567-89ab-cdef-0123-456789abcdef}\';FileSystemLabel='FAKE'})
try {
 Check (@(Get-SafeUsbVolumes @()).Count -eq 1) 'eligible fake USB enumerated'
 Check (@(Get-SafeUsbVolumes @(42)).Count -eq 0) 'protected disk excluded'
 $script:fakeDisks=@(MakeDisk 42 'fake-unique-id' 'NVMe')
 Check (@(Get-SafeUsbVolumes @()).Count -eq 0) 'internal NVMe excluded'
 $script:fakeDisks=@(MakeDisk 42 'fake-unique-id' 'USB' $true)
 Check (@(Get-SafeUsbVolumes @()).Count -eq 0) 'boot disk excluded'
 $script:fakeDisks=@((MakeDisk 42 'fake-unique-id'),(MakeDisk 43 'fake-unique-id'))
 Check (@(Get-SafeUsbVolumes @()).Count -eq 0) 'duplicate IDs excluded'
 $script:fakeDisks=@(MakeDisk 42 'fake-unique-id')
 $script:fakeVolumes[0].FileSystem='NTFS'
 Check (@(Get-SafeUsbVolumes @()).Count -eq 0) 'unsupported filesystem excluded'
 $script:fakeVolumes[0].FileSystem='exFAT'
 $script:fakeVolumes[0].Path='E:\'
 Check (@(Get-SafeUsbVolumes @()).Count -eq 0) 'drive letter path excluded'
 $script:fakeVolumes[0].Path='\\?\Volume{01234567-89ab-cdef-0123-456789abcdef}\'
 $fakeSelected=@(Get-SafeUsbVolumes @())[0]
 $actual=Confirm-UsbIdentity $fakeSelected @()
 Check ($actual.Id -ceq $fakeSelected.Id) 'identity check accepts same device'
 $script:fakeDisks[0].SerialNumber='changed'
 ExpectsError { Confirm-UsbIdentity $fakeSelected @() } 'changed USB identity rejected'
 $script:fakeDisks[0].SerialNumber='fake'
 # The stub resolves only this exact self-created temporary directory.
 $selected=[pscustomobject]@{VolumePath=$root}
 function Confirm-UsbIdentity($s,[uint32[]]$ignored) {
  if ($s.VolumePath -cne $script:root -or [IO.Path]::GetDirectoryName($s.VolumePath) -cne $script:tempRoot.TrimEnd([IO.Path]::DirectorySeparatorChar)) { throw 'outside fake root' }
  return $s
 }
 [IO.Directory]::CreateDirectory((Join-Path $root 'nested')) | Out-Null
 [IO.Directory]::CreateDirectory((Join-Path $root 'System Volume Information')) | Out-Null
 [IO.File]::WriteAllText((Join-Path $root 'NEMESIS.data'),'old')
 [IO.File]::WriteAllText((Join-Path $root 'nested/legacy.txt'),'old')
 [IO.File]::WriteAllText((Join-Path $root '$TXRAJNL.DAT'),'system')
 [IO.File]::WriteAllText((Join-Path $root 'System Volume Information/preserve'),'system')
 $plan=Get-UsbContentPlan $root
 Check ($plan.Files.Count -eq 2 -and $plan.Preserved.Count -eq 2) 'plan enumerates ordinary contents, preserves metadata'
 Remove-UsbContents $selected @()
 Check (!(Test-Path (Join-Path $root 'NEMESIS.data')) -and !(Test-Path (Join-Path $root 'nested')) -and (Test-Path (Join-Path $root '$TXRAJNL.DAT')) -and (Test-Path (Join-Path $root 'System Volume Information/preserve'))) 'cleanup removes only disposable files and preserves metadata'
 [IO.File]::WriteAllText((Join-Path $root 'untouched.txt'),'sentinel')
 $link=Join-Path $root 'link'
 $madeLink=$false
 try { New-Item -ItemType SymbolicLink -Path $link -Target $tempRoot -ErrorAction Stop | Out-Null; $madeLink=$true }
 catch { Write-Output 'SKIP symlink tests: creating symlinks is unavailable or requires permission' }
 if ($madeLink) {
  ExpectsError { Remove-UsbContents $selected @() } 'symlink rejected before deletion'
  Check (Test-Path (Join-Path $root 'untouched.txt')) 'preflight retains files on symlink failure'
  Remove-Item -LiteralPath $link -Force
 }
 ExpectsError { Assert-PlainUsbPath $root (Join-Path $tempRoot 'aio-outside-target') -AllowMissing } 'outside path rejected'
 [IO.File]::Delete((Join-Path $root 'untouched.txt'))
 [IO.Directory]::CreateDirectory((Join-Path $root 'payload')) | Out-Null
 $body=Join-Path $root 'payload/asset.txt'; $auto=Join-Path $root 'USB_AUTO.sh'; $descriptor=Join-Path $root 'NEMESIS.data'
 [IO.File]::WriteAllText($body,'body'); [IO.File]::WriteAllText($auto,'auto'); [IO.File]::WriteAllText($descriptor,'descriptor')
 $script:expected=@{ 'payload/asset.txt'=(Get-FileHash $body -Algorithm SHA256).Hash; 'USB_AUTO.sh'=(Get-FileHash $auto -Algorithm SHA256).Hash; 'NEMESIS.data'=(Get-FileHash $descriptor -Algorithm SHA256).Hash }
 $script:folders=[Collections.Generic.HashSet[string]]::new(); $null=$script:folders.Add('payload')
 Assert-UsbPayloadTree $root
 Check $true 'valid payload and descriptor accepted'
 [IO.File]::Delete($auto); [IO.File]::Delete($descriptor)
 Assert-UsbPayloadTree $root -WithoutDescriptor -WithoutAutostart
 Check $true 'partial payload accepted only in explicit pre-trigger validation'
 ExpectsError { Assert-UsbPayloadTree $root } 'missing trigger rejected for final payload'
 [IO.File]::WriteAllText($descriptor,'descriptor'); [IO.File]::WriteAllText($auto,'auto')
 [IO.File]::WriteAllText($body,'tampered')
 ExpectsError { Assert-UsbPayloadTree $root } 'tampered payload rejected'
 [IO.File]::WriteAllText($body,'body'); [IO.File]::WriteAllText((Join-Path $root 'unexpected'),'extra')
 ExpectsError { Assert-UsbPayloadTree $root } 'unexpected payload rejected'
} finally {
 $leftoverLink = Join-Path $root 'link'
 if (Test-Path -LiteralPath $leftoverLink) { Remove-Item -LiteralPath $leftoverLink -Force -ErrorAction SilentlyContinue }
 if ([IO.Path]::GetDirectoryName($root) -ceq $tempRoot.TrimEnd([IO.Path]::DirectorySeparatorChar) -and [IO.Path]::GetFileName($root) -match '^aio-winmock-[0-9a-f]{32}$') { Remove-Item -LiteralPath $root -Recurse -Force -ErrorAction SilentlyContinue }
}
