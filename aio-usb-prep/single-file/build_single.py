#!/usr/bin/env python3
"""Build the self-contained Windows launcher from the reviewed PowerShell sources."""
import base64
import hashlib
import io
from pathlib import Path
import zipfile

source = Path(__file__).resolve().parent.parent / 'tools'
out = Path(__file__).resolve().parent / 'Prepare-USB-Windows.cmd'
buf = io.BytesIO()
with zipfile.ZipFile(buf, 'w', zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
    for name in ('Prepare-USB.ps1', 'Assemble-USB.ps1', 'WindowsUsbVolume.ps1'):
        info = zipfile.ZipInfo('tools/' + name, (1980, 1, 1, 0, 0, 0))
        info.compress_type = zipfile.ZIP_DEFLATED
        info.external_attr = 0o100644 << 16
        contents = (source / name).read_bytes()
        if name == 'WindowsUsbVolume.ps1':
            # The helpers run from a temporary system folder, so protect both
            # that folder and the disk holding the original launcher.
            before = b'''    $here = [IO.DirectoryInfo]::new($release)\r\n    while ($null -ne $here) {\r\n        if ($here.Attributes -band [IO.FileAttributes]::ReparsePoint) { throw '\xeb\xb0\xb0\xed\x8f\xac ZIP\xec\x9d\x84 PC \xeb\x82\xb4\xeb\xb6\x80 \xeb\x93\x9c\xeb\x9d\xbc\xec\x9d\xb4\xeb\xb8\x8c\xec\x9d\x98 \xec\x9d\xbc\xeb\xb0\x98 \xed\x8f\xb4\xeb\x8d\x94\xec\x97\x90 \xed\x92\x80\xec\x96\xb4 \xec\xa3\xbc\xec\x84\xb8\xec\x9a\x94.' }\r\n        $here = $here.Parent\r\n    }\r\n    $letters = [IO.Path]::GetPathRoot($release), $env:SystemDrive'''
            after = '''    $protectedRoots = @($release)
    if ($env:HDNV_USB_SINGLE) {
        $launcher = [IO.FileInfo]::new([IO.Path]::GetFullPath($env:HDNV_USB_SINGLE))
        if (!$launcher.Exists -or ($launcher.Attributes -band [IO.FileAttributes]::ReparsePoint)) { throw 'Launcher must be a regular file.' }
        $protectedRoots += $launcher.DirectoryName
    }
    foreach ($protectedRoot in $protectedRoots) {
        $here = [IO.DirectoryInfo]::new($protectedRoot)
        while ($null -ne $here) {
            if ($here.Attributes -band [IO.FileAttributes]::ReparsePoint) { throw 'Use a regular folder on an internal PC drive.' }
            $here = $here.Parent
        }
    }
    $letters = @($protectedRoots | ForEach-Object { [IO.Path]::GetPathRoot($_) }) + @($env:SystemDrive)'''.replace('\n', '\r\n').encode('utf-8')
            if contents.count(before) != 1:
                raise ValueError('USB disk-protection code changed; review before building')
            contents = contents.replace(before, after)
        archive.writestr(info, contents)
data = buf.getvalue()
checksum = hashlib.sha256(data).hexdigest()
bootstrap = r'''
$ErrorActionPreference='Stop'
$result=1
$dir=$null
try {
  $builder=New-Object System.Text.StringBuilder
  foreach($line in [System.IO.File]::ReadLines($env:HDNV_USB_SINGLE,[System.Text.Encoding]::ASCII)) {
    if($line.StartsWith('::HDNV_DATA:')) { [void]$builder.Append($line.Substring(12)) }
  }
  $payload=[Convert]::FromBase64String($builder.ToString())
  $sha=[Security.Cryptography.SHA256]::Create()
  try { $digest=([BitConverter]::ToString($sha.ComputeHash($payload))).Replace('-','').ToLowerInvariant() } finally { $sha.Dispose() }
  if($digest -cne '@SHA256@') { throw 'Embedded preparation code failed integrity verification.' }
  $dir=Join-Path ([IO.Path]::GetTempPath()) ('hdnv-usb-prep-'+[Guid]::NewGuid().ToString('N'))
  [void][IO.Directory]::CreateDirectory($dir)
  $zip=Join-Path $dir 'embedded.zip'
  [IO.File]::WriteAllBytes($zip,$payload)
  Add-Type -AssemblyName System.IO.Compression.FileSystem
  [IO.Compression.ZipFile]::ExtractToDirectory($zip,$dir)
  Remove-Item -LiteralPath $zip -Force
  & (Join-Path $PSHOME 'powershell.exe') -NoLogo -NoProfile -ExecutionPolicy Bypass -File (Join-Path $dir 'tools\Prepare-USB.ps1')
  $result=$LASTEXITCODE
} catch { [Console]::Error.WriteLine('USB preparation failed: '+$_.Exception.Message); $result=1 }
finally { if($dir -and [IO.Directory]::Exists($dir)) { try { [IO.Directory]::Delete($dir,$true) } catch { [Console]::Error.WriteLine('Could not clean temporary preparation files: '+$_.Exception.Message) } } }
exit $result
'''.replace('@SHA256@', checksum)
encoded_bootstrap = base64.b64encode(bootstrap.encode('utf-16-le')).decode('ascii')
encoded_payload = base64.b64encode(data).decode('ascii')
lines = [
    '@echo off',
    'setlocal DisableDelayedExpansion',
    'set "HDNV_USB_SINGLE=%~f0"',
    '"%SystemRoot%\\System32\\WindowsPowerShell\\v1.0\\powershell.exe" -NoLogo -NoProfile -ExecutionPolicy Bypass -EncodedCommand ' + encoded_bootstrap,
    'set "prep_result=%errorlevel%"',
    'pause',
    'exit /b %prep_result%',
]
lines += ['::HDNV_DATA:' + encoded_payload[i:i+160] for i in range(0, len(encoded_payload), 160)]
out.write_bytes(('\r\n'.join(lines) + '\r\n').encode('ascii'))
print(f'{out}: embedded archive sha256={checksum}, bytes={len(data)}')
