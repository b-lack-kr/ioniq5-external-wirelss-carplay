# Native Windows PowerShell 5.1 assembly. Loaded by Prepare-USB.ps1.
Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem
$script:partsBaseUrl = 'https://github.com/b-lack-kr/ioniq5-external-wirelss-carplay/releases/download/v1.1.3/'
$script:parts = @(
    @{Name='aio-common.zip'; Hash='907687796be4b869d4061d9f33238941ed3354c8d00f11850afd630d79312d1d'; Pattern='^(NEMESIS\.data|NEMESIS\.sh|README\.txt|cp-upgrade\.sh|precondition-transition-guard\.sh|precondition-upgrade\.sh|stage-signed\.sh|update-description\.sh)$'},
    @{Name='carplay.zip'; Hash='abfa58cd4a77c5168e2b1f09d8f956dedab8b2c27d0f1a50432c0886f809fe37'; Pattern='^CP/'},
    @{Name='warmer.zip'; Hash='0727ce71a8b6c2ee4b0789527e01d64c5b62cb82be9ccf90406c7654e613b116'; Pattern='^WARMER/'},
    @{Name='favorites.zip'; Hash='5f0571bb16b27577bf6b72477e994eb3cdb9b795872deb092b77a3cd6452bcb9'; Pattern='^STAR/'},
    @{Name='mute-pause.zip'; Hash='104cc490e8d5663f5b0c22737b4c4bb903360583fce593d95069a7279fa3be1f'; Pattern='^packages/hmgapi\.neev-carplay-mute-pause\.zip$'},
    @{Name='dongle-adb.zip'; Hash='72309c3633cdd4126c6a68f3d32b5ff58d9acbaeaec406a69a9ff66d6f68bd02'; Pattern='^packages/local\.neev\.dongle-adb-1\.0\.5\.zip$'},
    @{Name='preconditioning.zip'; Hash='95d65dbcdec22322c04fc46c35839aad7a6fb68c943c14bf25f900e0c76c8d60'; Pattern='^packages/paytondev\.launchpad\.zip$'}
)
$script:vendorHash = '7a7e5c7a46fbf160d7b9b91fe4333ea55e4865233450c285361f9ef1a11059d7'
$script:wifiHash = 'd9077ba275398c1578444fdea479a5675e21f1b36f9ad3217e57b65aeadc512b'
$script:vendorUrl = 'https://github.com/GRT47/HKG_5W_USB_Wlan/releases/download/v2.0.2/HKG_5W_USBWifi_Hotspot.zip?download=1'
$script:adbUrl = 'https://dl.google.com/android/repository/platform-tools_r35.0.2-win.zip'
$script:adbHash = '2975a3eac0b19182748d64195375ad056986561d994fffbdc64332a516300bb9'

function Sha256Bytes([byte[]]$bytes) {
    $h=[Security.Cryptography.SHA256]::Create()
    try { return ([BitConverter]::ToString($h.ComputeHash($bytes))).Replace('-','').ToLowerInvariant() } finally { $h.Dispose() }
}
function Download-Https([string]$url,[int64]$limit) {
    [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
    for($i=0;$i -lt 8;$i++) {
        $uri=[Uri]$url
        if($uri.Scheme -cne 'https' -or !$uri.IsAbsoluteUri) { throw 'Only HTTPS download URLs are permitted.' }
        $req=[Net.HttpWebRequest]::Create($uri)
        $req.AllowAutoRedirect=$false; $req.Timeout=120000; $req.ReadWriteTimeout=120000
        $req.UserAgent='IONIQ5-USB-Prepare/1.1.3'
        try { $res=$req.GetResponse() } catch [Net.WebException] { if ($_.Exception.Response -is [Net.HttpWebResponse]) { $res=$_.Exception.Response } else { throw } }
        try {
            if([int]$res.StatusCode -ge 300 -and [int]$res.StatusCode -lt 400) {
                $location=$res.Headers['Location']; if(!$location){throw 'Redirect missing Location.'}
                $url=([Uri]::new($uri,$location)).AbsoluteUri; continue
            }
            if([int]$res.StatusCode -ne 200 -or $res.ContentLength -gt $limit) { throw 'Download failed or exceeds size limit.' }
            $input=$res.GetResponseStream(); $out=[IO.MemoryStream]::new()
            try {
                $buf=New-Object byte[] 65536
                while(($n=$input.Read($buf,0,$buf.Length)) -gt 0) { if($out.Length+$n -gt $limit){throw 'Download exceeds size limit.'}; $out.Write($buf,0,$n) }
                return ,$out.ToArray()
            } finally { $input.Dispose();$out.Dispose() }
        } finally { $res.Dispose() }
    }
    throw 'Too many download redirects.'
}
function Assert-ZipPath([string]$name) {
    if(!$name -or $name.StartsWith('/') -or $name.Contains('\') -or $name.Contains(':') -or $name.Contains([char]0)) { throw 'Invalid ZIP path.' }
    $trim=$name.TrimEnd('/')
    if(!$trim){throw 'Invalid ZIP path.'}
    foreach($part in $trim.Split('/')) {
        if(!$part -or $part -eq '.' -or $part -eq '..' -or $part.EndsWith('.') -or $part.EndsWith(' ') -or $part.IndexOfAny([IO.Path]::GetInvalidFileNameChars()) -ge 0) { throw 'Invalid ZIP member path.' }
    }
}
function Read-ZipMember([IO.Compression.ZipArchiveEntry]$entry,[int64]$limit) {
    if($entry.Length -gt $limit) {throw 'ZIP member exceeds limit.'}
    $input=$entry.Open();$out=[IO.MemoryStream]::new()
    try { $input.CopyTo($out);if($out.Length -gt $limit){throw 'ZIP member exceeds limit.'}; return ,$out.ToArray() } finally {$input.Dispose();$out.Dispose()}
}
function Assert-ZipEntries([IO.Compression.ZipArchive]$zip) {
    # Windows filesystem is case insensitive: reject collisions and ZIP symlinks.
    $seen=[Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
    foreach($entry in $zip.Entries) {
        Assert-ZipPath $entry.FullName
        if(!$seen.Add($entry.FullName.TrimEnd('/'))) {throw 'Duplicate ZIP member.'}
        $mode=($entry.ExternalAttributes -shr 16) -band 0xF000
        if($mode -eq 0xA000) {throw 'ZIP symlink refused.'}
    }
}
function Unpack-Checked([string]$archive,[string]$root,[string]$allowedPattern,[Collections.Generic.HashSet[string]]$seenPaths) {
    $zip=[IO.Compression.ZipFile]::OpenRead($archive)
    try {
        Assert-ZipEntries $zip; $total=0L
        foreach($entry in $zip.Entries) {
            if($entry.FullName -cnotmatch $allowedPattern){throw 'ZIP member outside its component scope.'}
            if(!$seenPaths.Add($entry.FullName.TrimEnd('/'))){throw 'Duplicate path across components.'}
            $total += $entry.Length; if($total -gt 256MB){throw 'ZIP exceeds size limit.'}
            $target=[IO.Path]::Combine($root,$entry.FullName.Replace([char]'/',[IO.Path]::DirectorySeparatorChar))
            if($entry.FullName.EndsWith('/')) {[void][IO.Directory]::CreateDirectory($target);continue}
            [void][IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($target))
            $src=$entry.Open();$dst=[IO.File]::Open($target,[IO.FileMode]::CreateNew)
            try {$src.CopyTo($dst)} finally {$dst.Dispose();$src.Dispose()}
        }
    } finally {$zip.Dispose()}
}
function Get-AdbPublicKey([string]$work) {
    $android=Join-Path $env:USERPROFILE '.android'
    $pub=Join-Path $android 'adbkey.pub';$private=Join-Path $android 'adbkey'
    if(![IO.File]::Exists($pub)) {
        if([IO.File]::Exists($private)){throw 'ADB private key exists without its public key. Restore the matching public key.'}
        $adb=Get-Command adb.exe -ErrorAction SilentlyContinue
        if($null -ne $adb) { $exe=$adb.Source }
        else {
            Write-Host 'Downloading Android platform-tools from Google for ADB key generation...'
            $raw=Download-Https $script:adbUrl (128MB)
            if((Sha256Bytes $raw) -cne $script:adbHash){throw 'Android platform-tools SHA-256 mismatch.'}
            $archive=Join-Path $work 'platform-tools.zip';[IO.File]::WriteAllBytes($archive,$raw)
            $zip=[IO.Compression.ZipFile]::OpenRead($archive)
            try {
                Assert-ZipEntries $zip
                foreach($name in @('adb.exe','AdbWinApi.dll','AdbWinUsbApi.dll')) {
                    $entries=@($zip.Entries | Where-Object {$_.FullName -ceq ('platform-tools/'+$name)})
                    if($entries.Count -ne 1){throw 'Unexpected platform-tools package.'}
                    $bytes=Read-ZipMember $entries[0] (32MB)
                    [IO.File]::WriteAllBytes((Join-Path $work $name),$bytes)
                }
            } finally {$zip.Dispose()}
            $exe=Join-Path $work 'adb.exe'
        }
        [void][IO.Directory]::CreateDirectory($android)
        & $exe keygen $private | Out-Host
        if($LASTEXITCODE -ne 0 -or ![IO.File]::Exists($pub)){throw 'ADB key generation failed.'}
    }
    $item=Get-Item -LiteralPath $pub -Force
    if($item.Attributes -band [IO.FileAttributes]::ReparsePoint){throw 'ADB public key must not be a symlink.'}
    $data=[IO.File]::ReadAllBytes($pub)
    if($data.Length -gt 2047 -or [Array]::IndexOf($data,[byte]0) -ge 0){throw 'Invalid ADB public key.'}
    $text=[Text.Encoding]::UTF8.GetString($data).TrimEnd("`r","`n")
    if(!$text -or $text.Contains("`r") -or $text.Contains("`n")){throw 'Invalid ADB public key line.'}
    $key=($text -split '\s+',2)[0]
    try {$decoded=[Convert]::FromBase64String($key)} catch {throw 'Invalid ADB public key encoding.'}
    if($decoded.Length -ne 524 -or [BitConverter]::ToUInt32($decoded,0) -ne 64 -or [BitConverter]::ToUInt32($decoded,520) -ne 65537){throw 'Unsupported Android ADB public key.'}
    return ,[Text.Encoding]::UTF8.GetBytes($text+"`n")
}
function Get-KeyIdentity([byte[]]$key) {
    # RFC 4122 UUIDv5 with NAMESPACE_URL; same identity as the original assembler.
    $ns=[byte[]](0x6b,0xa7,0xb8,0x11,0x9d,0xad,0x11,0xd1,0x80,0xb4,0x00,0xc0,0x4f,0xd4,0x30,0xc8)
    $message=[Text.Encoding]::UTF8.GetBytes('neev-adb-public-sha256:'+(Sha256Bytes $key))
    $sha=[Security.Cryptography.SHA1]::Create()
    try {$digest=$sha.ComputeHash([byte[]]($ns+$message))} finally {$sha.Dispose()}
    $digest[6]=($digest[6] -band 15) -bor 80; $digest[8]=($digest[8] -band 63) -bor 128
    $s=([BitConverter]::ToString($digest,0,16)).Replace('-','').ToLowerInvariant()
    return $s.Substring(0,8)+'-'+$s.Substring(8,4)+'-'+$s.Substring(12,4)+'-'+$s.Substring(16,4)+'-'+$s.Substring(20,12)
}
function New-UsbPayload([string]$stage,[string]$work,[string]$release) {
    if([IO.Directory]::Exists($stage) -or [IO.File]::Exists($stage)){throw 'Output already exists.'}
    $verifiedParts=@()
    foreach($part in $script:parts) {
        $local=Join-Path $release $part.Name
        if([IO.File]::Exists($local)) {
            $item=Get-Item -LiteralPath $local -Force
            if($item.Attributes -band [IO.FileAttributes]::ReparsePoint -or $item.Length -gt 128MB){throw 'Invalid component ZIP.'}
            $partData=[IO.File]::ReadAllBytes($local)
        } else {
            Write-Host ('Downloading '+$part.Name+' from GitHub...')
            $partData=Download-Https ($script:partsBaseUrl+$part.Name) (128MB)
        }
        if((Sha256Bytes $partData) -cne $part.Hash){throw ('Component SHA-256 mismatch: '+$part.Name)}
        $temp=Join-Path $work $part.Name
        [IO.File]::WriteAllBytes($temp,$partData)
        $verifiedParts+=@(@{Path=$temp;Pattern=$part.Pattern})
    }
    $key=Get-AdbPublicKey $work
    $vendor=Join-Path $release 'HKG_5W_USBWifi_Hotspot.zip'
    if([IO.File]::Exists($vendor)) {
        $item=Get-Item -LiteralPath $vendor -Force
        if($item.Attributes -band [IO.FileAttributes]::ReparsePoint -or $item.Length -gt 64MB){throw 'Invalid vendor ZIP.'}
        $data=[IO.File]::ReadAllBytes($vendor)
    } else { Write-Host 'Downloading GRT47 USB Wi-Fi package...';$data=Download-Https $script:vendorUrl (64MB) }
    if((Sha256Bytes $data) -cne $script:vendorHash){throw 'Vendor ZIP SHA-256 mismatch.'}
    $vendorFile=Join-Path $work 'vendor.zip';[IO.File]::WriteAllBytes($vendorFile,$data)
    $zip=[IO.Compression.ZipFile]::OpenRead($vendorFile)
    try {
        $entries=@($zip.Entries | Where-Object {$_.FullName -ceq 'packages/usbwifi.hotspot.zip'})
        if($entries.Count -ne 1){throw 'Unexpected Wi-Fi package.'}
        $wifi=Read-ZipMember $entries[0] (32MB)
    } finally {$zip.Dispose()}
    if((Sha256Bytes $wifi) -cne $script:wifiHash){throw 'Wi-Fi package SHA-256 mismatch.'}
    [void][IO.Directory]::CreateDirectory($stage)
    $seenPaths=[Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
    foreach($part in $verifiedParts){Unpack-Checked $part.Path $stage $part.Pattern $seenPaths}
    $wifiPath=Join-Path $stage 'CP\packages\usbwifi.hotspot.zip'
    [void][IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($wifiPath))
    [IO.File]::WriteAllBytes($wifiPath,$wifi)
    $adbZip=Join-Path $stage 'packages\local.neev.dongle-adb-1.0.5.zip'
    $zip=[IO.Compression.ZipFile]::Open($adbZip,[IO.Compression.ZipArchiveMode]::Update)
    try {
        Assert-ZipEntries $zip
        if($null -ne $zip.GetEntry('runtime/mac.pub') -or $null -ne $zip.GetEntry('installation.id')){throw 'ADB template already contains a key.'}
        foreach($item in @(@('runtime/mac.pub',$key),@('installation.id',[Text.Encoding]::ASCII.GetBytes((Get-KeyIdentity $key)+"`n")))) {
            $entry=$zip.CreateEntry([string]$item[0],[IO.Compression.CompressionLevel]::Optimal)
            $entry.LastWriteTime=[DateTimeOffset]::new(1980,1,1,0,0,0,[TimeSpan]::Zero)
            $stream=$entry.Open();try{$stream.Write([byte[]]$item[1],0,([byte[]]$item[1]).Length)}finally{$stream.Dispose()}
        }
    } finally {$zip.Dispose()}
    $md5=(Get-FileHash -LiteralPath $adbZip -Algorithm MD5).Hash.ToLowerInvariant()
    $wrapper=Join-Path $stage 'NEMESIS.sh';$raw=[IO.File]::ReadAllText($wrapper,[Text.Encoding]::UTF8)
    $token='@PERSONAL_ADB_MD5@'
    if(([regex]::Matches($raw,[regex]::Escape($token))).Count -ne 1){throw 'Invalid installer placeholder.'}
    [IO.File]::WriteAllText($wrapper,$raw.Replace($token,$md5),[Text.UTF8Encoding]::new($false))
}
