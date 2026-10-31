param([switch]$InitializeSigningKey, [switch]$SkipBuild, [string]$InstallRoot = 'D:\Android')

$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Split-Path -Parent $PSScriptRoot))
. "$PSScriptRoot\android-env.ps1" -InstallRoot $InstallRoot
$gitArgs = @('-c', "safe.directory=$($projectRoot.Replace('\', '/'))", '-C', $projectRoot)
$revision = & git @gitArgs rev-parse --verify HEAD
if ($LASTEXITCODE -ne 0) { throw 'Commit the release sources before packaging.' }
$changes = & git @gitArgs status --porcelain --untracked-files=normal
if ($LASTEXITCODE -ne 0 -or $changes) { throw 'Commit all release source changes before packaging.' }
$config = Get-Content -LiteralPath (Join-Path $projectRoot 'app\build.gradle.kts') -Raw
$version = [regex]::Match($config, 'versionName\s*=\s*"([A-Za-z0-9.-]+)"').Groups[1].Value
$versionCode = [int][regex]::Match($config, 'versionCode\s*=\s*(\d+)').Groups[1].Value
if (-not $version -or $versionCode -le 0) { throw 'Invalid application version.' }
$notes = Join-Path $projectRoot "docs\releases\$version.md"
if (-not (Test-Path -LiteralPath $notes)) { throw "Missing release notes: $notes" }

if (-not $SkipBuild) {
    Push-Location -LiteralPath $projectRoot
    try {
        & (Join-Path $projectRoot 'gradlew.bat') ':app:assembleRelease' '-x' ':app:lintVitalRelease'
        if ($LASTEXITCODE -ne 0) { throw 'Release build failed.' }
    } finally { Pop-Location }
}
$apkDirectory = Join-Path $projectRoot 'app\build\outputs\apk\release'
$metadata = Get-Content -LiteralPath (Join-Path $apkDirectory 'output-metadata.json') -Raw | ConvertFrom-Json
if ($metadata.applicationId -ne 'org.openbridge.app' -or $metadata.elements.Count -ne 1 -or
    $metadata.elements[0].versionName -ne $version -or $metadata.elements[0].versionCode -ne $versionCode) {
    throw 'Build a matching release APK before packaging.'
}
$unsignedApk = Join-Path $apkDirectory $metadata.elements[0].outputFile
$buildTools = Join-Path $env:ANDROID_HOME 'build-tools\36.0.0'
$aapt = Join-Path $buildTools 'aapt2.exe'
$signer = Join-Path $buildTools 'apksigner.bat'
$aligner = Join-Path $buildTools 'zipalign.exe'
$keytool = Join-Path $env:JAVA_HOME 'bin\keytool.exe'
$badging = (& $aapt dump badging $unsignedApk) -join "`n"
if ($LASTEXITCODE -ne 0 -or $badging -match 'application-debuggable') { throw 'Expected a non-debuggable release APK.' }
$manifest = (& $aapt dump xmltree $unsignedApk --file AndroidManifest.xml) -join "`n"
if ($LASTEXITCODE -ne 0 -or $manifest -match 'CameraDebugReceiver') { throw 'Release APK contains a debug entry point.' }
foreach ($abi in @('arm64-v8a', 'x86_64')) {
    if ($badging -notmatch [regex]::Escape("'$abi'")) { throw "Missing release ABI: $abi" }
}

$signingDirectory = Join-Path $projectRoot 'private\signing'
$keystore = Join-Path $signingDirectory 'openbridge-release.p12'
$passwordFile = Join-Path $signingDirectory 'store-password.txt'
$utf8 = [Text.UTF8Encoding]::new($false)
if ($InitializeSigningKey) {
    if (Test-Path -LiteralPath $signingDirectory) { throw 'Signing directory exists; never replace an existing release identity.' }
    New-Item -ItemType Directory -Path $signingDirectory | Out-Null
    $bytes = New-Object byte[] 32
    $random = [Security.Cryptography.RandomNumberGenerator]::Create()
    try { $random.GetBytes($bytes) } finally { $random.Dispose() }
    [IO.File]::WriteAllText($passwordFile, [Convert]::ToBase64String($bytes), $utf8)
    & $keytool -genkeypair -keystore $keystore -storetype PKCS12 -alias openbridge -keyalg RSA -keysize 3072 -validity 10000 `
        -dname 'CN=OpenBridge Release, O=OpenBridge' -storepass:file $passwordFile -keypass:file $passwordFile -noprompt
    if ($LASTEXITCODE -ne 0) { throw 'Signing key creation failed; preserve the directory for inspection.' }
}
if (-not (Test-Path -LiteralPath $keystore) -or -not (Test-Path -LiteralPath $passwordFile)) {
    throw 'Release signing key is missing. Restore private/signing, or explicitly use -InitializeSigningKey for the first release only.'
}
& git @gitArgs check-ignore --quiet -- $keystore
if ($LASTEXITCODE -ne 0) { throw 'Release signing material must be ignored by Git.' }

$name = "OpenBridge-$version"
$output = Join-Path $projectRoot "dist\$name"
if (Test-Path -LiteralPath $output) { throw "Release output already exists: $output" }
New-Item -ItemType Directory -Path $output | Out-Null
$intermediate = Join-Path $projectRoot "out\release\$version"
New-Item -ItemType Directory -Path $intermediate -Force | Out-Null
$aligned = Join-Path $intermediate 'aligned-unsigned.apk'
& $aligner -f -P 16 4 $unsignedApk $aligned
if ($LASTEXITCODE -ne 0) { throw 'APK alignment failed.' }
$apkName = "$name-release.apk"
$signedApk = Join-Path $output $apkName
# PKCS12 uses the store password for the key. Reading the same password file twice consumes its only line in apksigner.
& $signer sign --ks $keystore --ks-key-alias openbridge --ks-pass "file:$passwordFile" `
    --out $signedApk $aligned
if ($LASTEXITCODE -ne 0) { throw 'APK signing failed.' }
$verification = (& $signer verify --verbose --print-certs $signedApk) -join "`n"
if ($LASTEXITCODE -ne 0) { throw 'APK signature verification failed.' }
& $aligner -c -P 16 4 $signedApk
if ($LASTEXITCODE -ne 0) { throw 'Signed APK alignment verification failed.' }
$certificate = [regex]::Match($verification, 'certificate SHA-256 digest:\s*([0-9a-fA-F]+)').Groups[1].Value.ToLowerInvariant()
if ($certificate.Length -ne 64) { throw 'Could not read the release certificate fingerprint.' }
[IO.File]::WriteAllText((Join-Path $output 'SIGNATURE.txt'), $verification + "`n", $utf8)

$sourceName = "$name-source.zip"
& git @gitArgs archive --format=zip --prefix=OpenBridge/ "--output=$(Join-Path $output $sourceName)" $revision
if ($LASTEXITCODE -ne 0) { throw 'Source archive failed.' }
Copy-Item -LiteralPath $notes -Destination (Join-Path $output 'RELEASE_NOTES.md')
Copy-Item -LiteralPath (Join-Path $projectRoot 'LICENSE') -Destination $output
Copy-Item -LiteralPath (Join-Path $projectRoot 'THIRD_PARTY_NOTICES.md') -Destination $output
$buildInfo = [ordered]@{
    version = $version; versionCode = $versionCode; applicationId = 'org.openbridge.app'; commit = $revision.Trim()
    buildType = 'release'; debuggable = $false; minSdk = 29; targetSdk = 36; abis = @('arm64-v8a', 'x86_64')
    certificateSha256 = $certificate; packagedAtUtc = [DateTime]::UtcNow.ToString('o')
    deviceValidation = 'Z6 II + Xiaomi Android 16; protocol and deletion tested using the matching debug implementation.'
}
[IO.File]::WriteAllText((Join-Path $output 'BUILD_INFO.json'), ($buildInfo | ConvertTo-Json -Depth 3) + "`n", $utf8)
$packageFiles = @($apkName, $sourceName, 'LICENSE', 'RELEASE_NOTES.md', 'THIRD_PARTY_NOTICES.md', 'BUILD_INFO.json', 'SIGNATURE.txt')
$hashes = $packageFiles | ForEach-Object {
    "$((Get-FileHash -LiteralPath (Join-Path $output $_) -Algorithm SHA256).Hash.ToLowerInvariant())  $_"
}
[IO.File]::WriteAllText((Join-Path $output 'SHA256SUMS.txt'), ($hashes -join "`n") + "`n", $utf8)
$zipPath = Join-Path $projectRoot "dist\$name-release.zip"
if (Test-Path -LiteralPath $zipPath) { throw "Release ZIP already exists: $zipPath" }
$archiveFiles = @($packageFiles + 'SHA256SUMS.txt') | ForEach-Object { Join-Path $output $_ }
Compress-Archive -LiteralPath $archiveFiles -DestinationPath $zipPath -CompressionLevel Optimal
$zipHash = (Get-FileHash -LiteralPath $zipPath -Algorithm SHA256).Hash.ToLowerInvariant()
[IO.File]::WriteAllText("$zipPath.sha256", "$zipHash  $name-release.zip`n", $utf8)
Write-Host "Release APK: $signedApk"
Write-Host "Release package: $zipPath"
Write-Host "Source revision: $revision"
Write-Host "Certificate SHA-256: $certificate"
Write-Host 'Back up private/signing separately. It is excluded from Git and release artifacts.'
