# Host-side simulation: this never touches a block device or real USB volume.
# Real Windows Storage cmdlets, access controls, GUID paths and Win32 are not tested.
$ErrorActionPreference='Stop'
Set-StrictMode -Version 2.0
. (Join-Path (Split-Path $PSScriptRoot -Parent) 'tools/WindowsUsbVolume.ps1')
$base=Join-Path ([IO.Path]::GetTempPath()) ('usb-volume-sim-'+[Guid]::NewGuid().ToString('N'))
$disk=Join-Path $base 'fake-usb';$ready=Join-Path $base 'ready'
function Ensure([bool]$condition,[string]$description) { if(!$condition){throw "Failed: $description"};Write-Host "PASS $description" }
function MustFail([scriptblock]$action,[string]$description) { $failed=$false;try{& $action}catch{$failed=$true};Ensure $failed $description }
function Put([string]$path,[string]$value) {[void][IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($path));[IO.File]::WriteAllText($path,$value)}
$expected=[Collections.Generic.Dictionary[string,string]]::new([StringComparer]::Ordinal)
$folders=[Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
try {
 [void][IO.Directory]::CreateDirectory($disk)
 Put (Join-Path $disk 'NEMESIS.data') 'old-descriptor'
 Put (Join-Path $disk 'old/sub/file.txt') 'old-content'
 Put (Join-Path $disk 'System Volume Information/system.dat') 'protected'
 Put (Join-Path $disk '$TXRAJNL.DAT') 'protected-root'
 $choice=[pscustomobject]@{ Letter='Z'; Number=[uint32]9; Size=[uint64]1024; Id='FAKE_ID'; Path='FAKE_PATH'; Serial='FAKE_SERIAL'; Style='MBR'; Part=1; Offset=0; PartSize=512; VolumePath=$disk; FileSystem='FAT32'; Label='TEST'; Model='SIM' }
 $global:simDevices=@($choice)
 function Get-SafeUsbVolumes([uint32[]]$protectedDisks) { return $global:simDevices }
 $confirmed=Confirm-UsbIdentity $choice @(); Ensure ($confirmed.Id -eq 'FAKE_ID') 'matching USB identity accepted'
 $other=$choice.PSObject.Copy();$other.Serial='CHANGED';$global:simDevices=@($other)
 MustFail {Confirm-UsbIdentity $choice @()} 'changed USB identity refused'
 $global:simDevices=@($choice)
 $plan=Get-UsbContentPlan $disk
 Ensure ($plan.Files.Count -eq 2 -and $plan.Preserved.Count -eq 2) 'old files planned and Windows metadata preserved'
 if($PSVersionTable.PSVersion.Major -ge 6) {
   $link=Join-Path $disk 'link-to-other'
   New-Item -ItemType SymbolicLink -Path $link -Target $ready | Out-Null
   MustFail {Get-UsbContentPlan $disk} 'symlink refused before deletion'
   Remove-Item -LiteralPath $link -Force
 }
 Remove-UsbContents $choice @()
 Ensure (![IO.File]::Exists((Join-Path $disk 'NEMESIS.data')) -and ![IO.Directory]::Exists((Join-Path $disk 'old'))) 'old descriptor and nested contents removed'
 Ensure ([IO.File]::Exists((Join-Path $disk 'System Volume Information/system.dat')) -and [IO.File]::Exists((Join-Path $disk '$TXRAJNL.DAT'))) 'protected Windows metadata retained'
 Put (Join-Path $ready 'NEMESIS.data') 'new-descriptor'
 Put (Join-Path $ready 'USB_AUTO.sh') 'new-autostart'
 Put (Join-Path $ready 'CP/payload.bin') 'payload-content'
 [void]$folders.Add('CP')
 foreach($name in @('NEMESIS.data','USB_AUTO.sh','CP/payload.bin')) { $expected.Add($name,(Get-FileHash -LiteralPath (Join-Path $ready $name) -Algorithm SHA256).Hash) }
 function Assert-ReadyTree([string]$root) { foreach($name in $expected.Keys){Assert-DeviceFile (Join-Path $root $name) $expected[$name]} }
 $script:rawDeviceCheck=${function:Assert-DeviceFile};$script:activationOrderChecked=$false
 function Assert-DeviceFile([string]$file,[string]$digest) {
   & $script:rawDeviceCheck $file $digest
   if([IO.Path]::GetFileName($file) -eq '.neev-descriptor.pending') {
     if(![IO.File]::Exists((Join-Path $disk 'CP/payload.bin')) -or ![IO.File]::Exists((Join-Path $disk 'USB_AUTO.sh'))){throw 'Descriptor activated too early.'}
     & $script:rawDeviceCheck (Join-Path $disk 'CP/payload.bin') $expected['CP/payload.bin']
     & $script:rawDeviceCheck (Join-Path $disk 'USB_AUTO.sh') $expected['USB_AUTO.sh']
     $script:activationOrderChecked=$true
   }
 }
 # Two hooks are substituted because the actual implementation calls kernel32.dll.
 function Initialize-UsbDirectoryApi {}
 function New-UsbPayloadParent([string]$root,[string]$relativeFile) { $target=Join-Path $root $relativeFile;[void][IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($target)) }
 Write-SelectedVolume $choice @()
 Assert-UsbPayloadTree $disk
 Ensure (([IO.File]::ReadAllText((Join-Path $disk 'CP/payload.bin'))) -eq 'payload-content') 'payload copied and verified'
 Ensure ($script:activationOrderChecked -and ([IO.File]::ReadAllText((Join-Path $disk 'NEMESIS.data'))) -eq 'new-descriptor') 'descriptor activation occurs after verified payload'
 Put (Join-Path $disk 'CP/payload.bin') 'tampered'
 MustFail {Assert-UsbPayloadTree $disk} 'readback hash detects tampering'
} finally {if([IO.Directory]::Exists($base)){[IO.Directory]::Delete($base,$true)}}
