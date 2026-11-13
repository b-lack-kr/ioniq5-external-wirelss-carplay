# Reproducible offline assembly simulation. Never touches a USB device or user key.
# Default fixtures are sibling 20261113-aio-github/release and the older
# 20261112-cp-113/receiver-preparer. Supply explicit paths to use other fixtures.
param([string]$PartsDirectory='', [string]$VendorZip='', [string]$OriginalTemplate='')
$ErrorActionPreference='Stop'
Set-StrictMode -Version 2.0
$repo=Split-Path $PSScriptRoot -Parent
$task=Split-Path (Split-Path $repo -Parent) -Parent
$improvements=Split-Path $task -Parent
if(!$PartsDirectory){$PartsDirectory=Join-Path $task 'release'}
if(!$VendorZip){$VendorZip=Join-Path $improvements '20261112-cp-113/receiver-preparer/HKG_5W_USBWifi_Hotspot.zip'}
if(!$OriginalTemplate){$OriginalTemplate=Join-Path $improvements '20261112-cp-113/receiver-preparer/bundle-template.zip'}
. (Join-Path $repo 'tools/Assemble-USB.ps1')
$base=Join-Path ([IO.Path]::GetTempPath()) ('windows-assembly-sim-'+[Guid]::NewGuid().ToString('N'))
$oldProfile=$env:USERPROFILE
function Ensure([bool]$condition,[string]$description){if(!$condition){throw "Failed: $description"};Write-Host "PASS $description"}
function MustFail([scriptblock]$action,[string]$description,[string]$expectedMessage=''){
 $failed=$false;try{& $action}catch{$failed=$true;if($expectedMessage -and $_.Exception.Message -notmatch $expectedMessage){throw}}
 Ensure $failed $description
}
try {
 $env:USERPROFILE=Join-Path $base 'profile'
 $android=Join-Path $env:USERPROFILE '.android';[void][IO.Directory]::CreateDirectory($android)
 $rsa=New-Object byte[] 524
 [Array]::Copy([BitConverter]::GetBytes([uint32]64),0,$rsa,0,4)
 [Array]::Copy([BitConverter]::GetBytes([uint32]65537),0,$rsa,520,4)
 $key=[Text.Encoding]::ASCII.GetBytes([Convert]::ToBase64String($rsa)+" fixture`n")
 [IO.File]::WriteAllBytes((Join-Path $android 'adbkey.pub'),$key)
 $release=Join-Path $base 'input';$work=Join-Path $base 'work';$out=Join-Path $base 'USB_READY'
 [void][IO.Directory]::CreateDirectory($release);[void][IO.Directory]::CreateDirectory($work)
 [IO.File]::Copy($VendorZip,(Join-Path $release 'HKG_5W_USBWifi_Hotspot.zip'))
 $script:tamperName='';$script:downloadNames=[Collections.Generic.List[string]]::new()
 function Download-Https([string]$url,[int64]$limit){
   if(!$url.StartsWith($script:partsBaseUrl,[StringComparison]::Ordinal)){throw 'Unexpected download URL.'}
   $name=$url.Substring($script:partsBaseUrl.Length)
   $script:downloadNames.Add($name)
   $bytes=[IO.File]::ReadAllBytes((Join-Path $PartsDirectory $name))
   if($name -eq $script:tamperName){$bytes[0]=$bytes[0] -bxor 1}
   return ,$bytes
 }
 New-UsbPayload $out $work $release
 Ensure ($script:downloadNames.Count -eq 7 -and @($script:downloadNames | Select-Object -Unique).Count -eq 7) 'seven distinct components downloaded'
 Ensure ((Get-KeyIdentity $key) -ceq 'c6cdbfe9-0682-542e-b1ad-c3f6d42254cc') 'ADB identity matches UUIDv5 fixture'
 $zip=[IO.Compression.ZipFile]::OpenRead($OriginalTemplate)
 try {
  $originalCount=0
  foreach($entry in $zip.Entries){
   if($entry.FullName.EndsWith('/')){continue}
   $originalCount++
   if($entry.FullName -in @('NEMESIS.sh','packages/local.neev.dongle-adb-1.0.5.zip')){continue}
   $target=Join-Path $out $entry.FullName
   Ensure ([IO.File]::Exists($target) -and (Sha256Bytes ([IO.File]::ReadAllBytes($target))) -ceq (Sha256Bytes (Read-ZipMember $entry (128MB)))) ('template contents: '+$entry.FullName)
  }
  $oldAdb=Read-ZipMember $zip.GetEntry('packages/local.neev.dongle-adb-1.0.5.zip') (64MB)
  $oldWrapper=[Text.Encoding]::UTF8.GetString((Read-ZipMember $zip.GetEntry('NEMESIS.sh') (1MB)))
  $oldAdbPath=Join-Path $work 'original-adb.zip';[IO.File]::WriteAllBytes($oldAdbPath,$oldAdb)
 } finally {$zip.Dispose()}
 Ensure ($originalCount -eq 56) 'original template has expected member count'
 $outputFiles=@(Get-ChildItem -LiteralPath $out -Recurse -File)
 Ensure ($outputFiles.Count -eq 57) 'assembled output has expected member count'
 $adbPath=Join-Path $out 'packages/local.neev.dongle-adb-1.0.5.zip'
 $old=[IO.Compression.ZipFile]::OpenRead($oldAdbPath);$new=[IO.Compression.ZipFile]::OpenRead($adbPath)
 try {
  foreach($entry in $old.Entries){$other=$new.GetEntry($entry.FullName);Ensure ($null -ne $other -and (Sha256Bytes (Read-ZipMember $entry (64MB))) -ceq (Sha256Bytes (Read-ZipMember $other (64MB)))) ('ADB content retained: '+$entry.FullName)}
  Ensure ($new.Entries.Count -eq $old.Entries.Count+2) 'ADB contains exactly two personal entries'
  Ensure ((Sha256Bytes (Read-ZipMember $new.GetEntry('runtime/mac.pub') 2048)) -ceq (Sha256Bytes $key)) 'only synthetic public key injected'
  $id=[Text.Encoding]::ASCII.GetString((Read-ZipMember $new.GetEntry('installation.id') 1024))
  Ensure ($id -ceq "c6cdbfe9-0682-542e-b1ad-c3f6d42254cc`n") 'installation identity injected'
 } finally {$old.Dispose();$new.Dispose()}
 $wrapper=[IO.File]::ReadAllText((Join-Path $out 'NEMESIS.sh'))
 $md5=(Get-FileHash -LiteralPath $adbPath -Algorithm MD5).Hash.ToLowerInvariant()
 Ensure ($wrapper.Contains($md5) -and !$wrapper.Contains('@PERSONAL_ADB_MD5@')) 'installer contains the generated ADB hash'
 Ensure ($wrapper -ceq $oldWrapper.Replace('@PERSONAL_ADB_MD5@',$md5)) 'installer is otherwise identical to original'
 Ensure ((Get-FileHash -LiteralPath (Join-Path $out 'CP/packages/usbwifi.hotspot.zip') -Algorithm SHA256).Hash.ToLowerInvariant() -ceq $script:wifiHash) 'verified USB Wi-Fi package included'
 $script:tamperName='warmer.zip';$work2=Join-Path $base 'work2';[void][IO.Directory]::CreateDirectory($work2)
 $out2=Join-Path $base 'tampered-output'
 MustFail {New-UsbPayload $out2 $work2 $release} 'tampered component rejected' 'SHA-256 mismatch'
 Ensure (![IO.Directory]::Exists($out2)) 'tampered download blocked before staging'
 $seen=[Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
 $extracted=Join-Path $base 'extract';[void][IO.Directory]::CreateDirectory($extracted)
 $part=Join-Path $PartsDirectory 'aio-common.zip'
 Unpack-Checked $part $extracted '.*' $seen
 MustFail {Unpack-Checked $part $extracted '.*' $seen} 'overlapping component path rejected' 'Duplicate path'
 $seen2=[Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
 MustFail {Unpack-Checked $part $extracted '^CP/' $seen2} 'out-of-scope component member rejected' 'outside its component scope'
 foreach($name in @('../evil','C:/escape','dir\escape','a/../b','/absolute')) {MustFail {Assert-ZipPath $name} ('unsafe ZIP path rejected: '+$name)}
} finally {
 $env:USERPROFILE=$oldProfile
 if([IO.Directory]::Exists($base)){[IO.Directory]::Delete($base,$true)}
}
