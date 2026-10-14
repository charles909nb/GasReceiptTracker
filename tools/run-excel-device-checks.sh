#!/usr/bin/env bash
set -euo pipefail
# Gradle produces the JUnit reports and uninstalls its test application during teardown.
gradle --no-daemon --stacktrace :app:connectedDebugAndroidTest
# Run once more without Gradle teardown so generated documents can be visually reviewed.
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -r org.karuk.gasreceipttracker.preview.test/androidx.test.runner.AndroidJUnitRunner | tee verification-instrumentation.txt
grep -q 'OK (4 tests)' verification-instrumentation.txt
adb pull /sdcard/Android/data/org.karuk.gasreceipttracker.preview/files/verification verification
test -s verification/Sample_Gas_Requisition.xls
test -s verification/Sample_Gas_Requisition.pdf
test -s verification/In_App_PDF_Preview.png
signer=$(find "$ANDROID_HOME/build-tools" -name apksigner -type f | sort -V | tail -n 1)
"$signer" verify --verbose --print-certs app/build/outputs/apk/debug/app-debug.apk > verification/APK_SIGNATURE_VERIFICATION.txt
sha256sum app/build/outputs/apk/debug/app-debug.apk > verification/APK_SHA256.txt
