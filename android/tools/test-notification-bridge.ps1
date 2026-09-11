param(
    [string]$Serial = 'emulator-5554',
    [switch]$AllTests,
    [string]$Classes = '',
    [string]$Adb = "$env:LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe",
    [string]$Output = "$PSScriptRoot/../../docs/superpowers/reviews/phase-e/bridge-instrumentation.txt"
)
$ErrorActionPreference = 'Stop'
if (-not $Serial.StartsWith('emulator-')) {
    throw 'This fixture runner grants temporary notification access and is restricted to emulators.'
}
$component = 'com.smartview.glassai/com.smartview.glassai.services.NotificationBridgeService'
$existing = (& $Adb -s $Serial shell settings get secure enabled_notification_listeners) -join ''
if ($LASTEXITCODE -ne 0) { throw 'Cannot read emulator listener grants.' }
$hadGrant = ($existing -split ':') -contains $component
$package = 'com.smartview.glassai'
$preferences = 'shared_prefs/notification_bridge.xml'
$backup = 'files/phase_e_' + [guid]::NewGuid().ToString('N') + '.xml'
& $Adb -s $Serial shell run-as $package test -f $preferences
$hadPreferences = $LASTEXITCODE -eq 0
if ($hadPreferences) {
    $savedHash = ((& $Adb -s $Serial shell run-as $package sha256sum $preferences) -split '\s+')[0]
    & $Adb -s $Serial shell run-as $package cp $preferences $backup
    if ($LASTEXITCODE -ne 0) { throw 'Cannot back up fixture preferences.' }
}
try {
    if (-not $hadGrant) {
        & $Adb -s $Serial shell cmd notification allow_listener $component
        if ($LASTEXITCODE -ne 0) { throw 'Cannot grant test listener access.' }
    }
    $defaultClasses = 'com.smartview.glassai.bridge.MediaBridgeInstrumentedTest,com.smartview.glassai.bridge.WeChatNotificationParserInstrumentedTest,com.smartview.glassai.glasses.DisplayImageInstrumentedTest'
    $testArgs = @('-s', $Serial, 'shell', 'am', 'instrument', '-w', '-r')
    if ($Classes) { $testArgs += @('-e', 'class', $Classes) }
    elseif (-not $AllTests) { $testArgs += @('-e', 'class', $defaultClasses) }
    $testArgs += 'com.smartview.glassai.test/androidx.test.runner.AndroidJUnitRunner'
    $result = & $Adb @testArgs 2>&1
    $text = $result -join "`n"
    $text | Set-Content -LiteralPath $Output -Encoding utf8
    if ($LASTEXITCODE -ne 0 -or $text -notmatch 'OK \(\d+ tests?\)' -or $text -match 'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed') {
        throw "Bridge instrumentation did not pass; see $Output"
    }
    ($text -split "`n" | Select-Object -Last 8) -join "`n"
} finally {
    if (-not $hadGrant) {
        & $Adb -s $Serial shell cmd notification disallow_listener $component
        if ($LASTEXITCODE -ne 0) { Write-Warning 'Restore the temporary emulator notification grant manually.' }
    }
    # Stop the test process before restoring this one non-secret preference file.
    # This also covers asynchronous writes/aborts in instrumentation teardown.
    & $Adb -s $Serial shell am force-stop $package
    & $Adb -s $Serial shell run-as $package rm -f "$preferences.bak"
    if ($hadPreferences) {
        & $Adb -s $Serial shell run-as $package cp $backup $preferences
        if ($LASTEXITCODE -ne 0) { throw 'Cannot restore fixture preferences; backup retained in app files.' }
        $restoredHash = ((& $Adb -s $Serial shell run-as $package sha256sum $preferences) -split '\s+')[0]
        if ($savedHash -ne $restoredHash) { throw 'Fixture preference restoration verification failed.' }
        & $Adb -s $Serial shell run-as $package rm $backup
    } else {
        & $Adb -s $Serial shell run-as $package rm -f $preferences
        & $Adb -s $Serial shell run-as $package test -f $preferences
        if ($LASTEXITCODE -eq 0) { throw 'Fixture preference file remains after cleanup.' }
    }
}
