# Builds and signs a debug APK with the portable JDK on this PC.
$ErrorActionPreference = 'Stop'

$project = $PSScriptRoot
$cache = Join-Path $project '.gradle-user-home'
$sdk = Join-Path $cache 'android-sdk'
$tools = Join-Path $sdk 'cmdline-tools\15859902'
$gradle = Join-Path $cache 'gradle-8.11.1'
$java = $env:JAVA_HOME
if (-not $java -or -not (Test-Path -LiteralPath (Join-Path $java 'bin\javac.exe') -PathType Leaf)) {
    $javac = Get-Command javac.exe -CommandType Application -ErrorAction SilentlyContinue
    if ($javac) {
        $java = Split-Path -Parent (Split-Path -Parent $javac.Source)
    }
}
if (-not $java -or -not (Test-Path -LiteralPath (Join-Path $java 'bin\javac.exe') -PathType Leaf)) {
    throw 'Set JAVA_HOME to a JDK 17 or 21 installation or put javac.exe on PATH.'
}

function Import-VerifiedArchive($url, $expected, $folderInArchive, $destination) {
    if (Test-Path -LiteralPath $destination) { return }
    $stage = Join-Path $cache ('extract-' + [guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Path $stage | Out-Null
    $archive = Join-Path $stage 'tools.zip'
    Write-Host "Downloading $url"
    & curl.exe --fail --show-error --silent --location --output $archive $url
    if ($LASTEXITCODE -ne 0) { throw "Download failed: $url" }
    if ((Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash -ne $expected) {
        throw "SHA-256 mismatch: $url"
    }
    $unpacked = Join-Path $stage 'unpacked'
    Expand-Archive -LiteralPath $archive -DestinationPath $unpacked
    $source = Join-Path $unpacked $folderInArchive
    if (-not (Test-Path -LiteralPath $source -PathType Container)) { throw "Invalid archive layout: $url" }
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $destination) | Out-Null
    Move-Item -LiteralPath $source -Destination $destination
    Remove-Item -LiteralPath $archive
    if (-not (Get-ChildItem -LiteralPath $unpacked -Force)) {
        [IO.Directory]::Delete($unpacked)
        [IO.Directory]::Delete($stage)
    }
}

New-Item -ItemType Directory -Force -Path $cache | Out-Null
Import-VerifiedArchive 'https://dl.google.com/android/repository/commandlinetools-win-15859902_latest.zip' '90ae805d20434428bffcb699c290860f19bb5f66a67e6b330067e3de801fb04a' 'cmdline-tools' $tools
Import-VerifiedArchive 'https://downloads.gradle.org/distributions/gradle-8.11.1-bin.zip' 'f397b287023acdba1e9f6fc5ea72d22dd63669d59ed4a289a29b1a76eee151c6' 'gradle-8.11.1' $gradle

$env:JAVA_HOME = $java
$env:ANDROID_HOME = $sdk
$env:ANDROID_SDK_ROOT = $sdk
$env:GRADLE_USER_HOME = $cache
# A local.properties set by Android Studio can point to another SDK.
$sdkProperty = $sdk.Replace('\', '/')
$propertyFile = Join-Path $project 'local.properties'
if (-not (Test-Path -LiteralPath $propertyFile)) {
    Set-Content -LiteralPath $propertyFile -Value ('sdk.dir=' + $sdkProperty) -Encoding ascii
} elseif ((Get-Content -LiteralPath $propertyFile -Raw).Trim() -ne ('sdk.dir=' + $sdkProperty)) {
    throw "A different SDK is configured in $propertyFile. Use Android Studio to build this project."
}

$sdkManager = Join-Path $tools 'bin\sdkmanager.bat'
$sdkArgs = @("--sdk_root=$sdk", 'platforms;android-35', 'build-tools;35.0.0')
& $sdkManager @sdkArgs
if ($LASTEXITCODE -ne 0) { throw 'Android SDK installation failed. Check the license prompts and SDK output.' }
if (-not (Test-Path -LiteralPath (Join-Path $sdk 'platforms\android-35\android.jar'))) {
    throw 'SDK Platform 35 is missing. Confirm that its license was accepted.'
}

& (Join-Path $gradle 'bin\gradle.bat') --no-daemon --project-dir $project :app:assembleDebug
if ($LASTEXITCODE -ne 0) { throw 'Android build failed.' }

$apk = Join-Path $project 'app\build\outputs\apk\debug\app-debug.apk'
if (-not (Test-Path -LiteralPath $apk -PathType Leaf)) { throw 'The expected debug APK was not generated.' }
& (Join-Path $sdk 'build-tools\35.0.0\apksigner.bat') verify $apk
if ($LASTEXITCODE -ne 0) { throw 'APK signature verification failed.' }
$badging = & (Join-Path $sdk 'build-tools\35.0.0\aapt.exe') dump badging $apk
if ($LASTEXITCODE -ne 0) { throw 'Could not read the APK version.' }
$package = $badging | Where-Object { $_.StartsWith('package: ') } | Select-Object -First 1
if ($package -notmatch "versionName='(?<version>[0-9][A-Za-z0-9._+-]*)'") {
    throw 'The signed APK does not contain a valid version name.'
}
$export = Join-Path (Split-Path -Parent $project) "FramePlayerRemote-$($Matches.version)-debug.apk"
Copy-Item -LiteralPath $apk -Destination $export -Force
Write-Host "APK_READY $export"
