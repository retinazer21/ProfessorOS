# Developer packaging entry point. End users need only the generated EXE installer.
$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
if (-not $env:JAVA_HOME -or -not (Test-Path (Join-Path $env:JAVA_HOME 'bin/jpackage.exe'))) {
    throw 'Set JAVA_HOME to a JDK 25 installation before packaging.'
}
if (-not (Test-Path (Join-Path $env:JAVA_HOME 'jmods/java.base.jmod'))) {
    throw 'Install the matching Windows x64 JDK module archive in JAVA_HOME/jmods before packaging.'
}
$wixDirectory = Join-Path $projectRoot 'prototype-compose/.gradle/packaging-tools/wix-3.14.1'
$archive = Join-Path $wixDirectory 'wix314-binaries.zip'
New-Item -ItemType Directory -Force -Path $wixDirectory | Out-Null
if (-not (Test-Path -LiteralPath $archive)) {
    Invoke-WebRequest 'https://github.com/wixtoolset/wix3/releases/download/wix3141rtm/wix314-binaries.zip' -OutFile $archive
}
$expected = '6ac824e1642d6f7277d0ed7ea09411a508f6116ba6fae0aa5f2c7daa2ff43d31'
if ((Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash.ToLowerInvariant() -ne $expected) {
    throw 'WiX download checksum mismatch; investigate before retrying.'
}
Expand-Archive -LiteralPath $archive -DestinationPath $wixDirectory -Force
$installerResources = Join-Path $projectRoot 'prototype-compose/.gradle/packaging-tools/windows-resources'
$jdkResources = Join-Path $projectRoot 'prototype-compose/.gradle/packaging-tools/jdk-resources'
New-Item -ItemType Directory -Force -Path $jdkResources | Out-Null
New-Item -ItemType Directory -Force -Path $installerResources | Out-Null
& (Join-Path $env:JAVA_HOME 'bin/jmod.exe') extract --dir $jdkResources (Join-Path $env:JAVA_HOME 'jmods/jdk.jpackage.jmod')
if ($LASTEXITCODE -ne 0) { throw 'Cannot extract the Windows WiX template from the JDK.' }
$template = @(Get-ChildItem -LiteralPath $jdkResources -Recurse -Filter 'main.wxs')
if ($template.Count -ne 1) { throw 'Expected one Windows WiX template in the JDK.' }
$wix = Get-Content -LiteralPath $template[0].FullName -Raw
$upgradeAnchor = '<RemoveExistingProducts Before="CostInitialize"/>'
if ([regex]::Matches($wix, [regex]::Escape($upgradeAnchor)).Count -ne 1 -or -not $wix.Contains('<InstallExecuteSequence>')) {
    throw 'The JDK changed its upgrade sequence. Review the installer before building.'
}
$migration = @'
<CustomAction Id="PreserveProfessorOsSession" Script="vbscript" Return="check"><![CDATA[
__SESSION_PRESERVATION_SCRIPT__
If PreserveProfessorOsSession() <> 1 Then Err.Raise 5, "Professor OS", "Could not safely preserve the existing Windows session."
]]></CustomAction>
    <InstallExecuteSequence>
'@
$script = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'windows-preserve-session.vbs') -Raw
$migration = $migration.Replace('__SESSION_PRESERVATION_SCRIPT__', $script)
$wix = $wix.Replace('<InstallExecuteSequence>', $migration).Replace($upgradeAnchor,
    '<Custom Action="PreserveProfessorOsSession" Before="RemoveExistingProducts">NOT Installed</Custom>' + "`n      " + $upgradeAnchor)
[IO.File]::WriteAllText((Join-Path $installerResources 'main.wxs'), $wix)
Push-Location $projectRoot
try {
    & cmd /d /c prototype-compose\gradlew.bat -p prototype-compose test packageWindowsRelease "-PwixToolsetDir=$wixDirectory" "-PwindowsInstallerResources=$installerResources"
    if ($LASTEXITCODE -ne 0) { throw 'Packaging failed; inspect the Gradle diagnostics.' }
} finally { Pop-Location }
