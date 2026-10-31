param([string]$InstallRoot = 'D:\Android')

$env:JAVA_HOME = Join-Path $InstallRoot 'jdk-17'
$env:ANDROID_HOME = Join-Path $InstallRoot 'sdk'
$env:ANDROID_USER_HOME = Join-Path $InstallRoot 'user-home'
$env:GRADLE_USER_HOME = Join-Path $InstallRoot 'gradle-cache'
$env:ANDROID_NDK_HOME = Join-Path $InstallRoot 'sdk\ndk\28.2.13676358'
$env:Path = "$env:JAVA_HOME\bin;$env:ANDROID_HOME\platform-tools;$env:ANDROID_HOME\cmdline-tools\latest\bin;$env:Path"
if (Test-Path -LiteralPath "$env:ANDROID_HOME\cmake\3.22.1\bin") {
    $env:Path = "$env:ANDROID_HOME\cmake\3.22.1\bin;$env:Path"
}
if (-not (Test-Path -LiteralPath "$env:JAVA_HOME\bin\java.exe")) {
    throw 'JDK 17 was not found under InstallRoot/jdk-17. See docs/building.md for environment setup.'
}
