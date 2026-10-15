#!/usr/bin/env bash
set -euo pipefail
# Run instrumentation once directly. Gradle's connectedAndroidTest removes app data
# during teardown, which destroys the screenshots and generated PDF samples.
# A second instrumentation run also triggers test cleanup that can delete them.
mkdir -p verification
adb wait-for-device
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -r org.karuk.gasreceipttracker.preview.test/androidx.test.runner.AndroidJUnitRunner | tee verification-instrumentation.txt
grep -q 'OK (75 tests)' verification-instrumentation.txt
adb pull /sdcard/Android/data/org.karuk.gasreceipttracker.preview/files/verification verification
# adb pull <directory> <existing-directory> may nest a verification directory.
if [ -d verification/verification ]; then
  cp -a verification/verification/. verification/
fi
test -s verification/UI_Home.png
test -s verification/UI_Signed_Pdf.png
test -s verification/Sample_Gas_Requisition.xls
test -s verification/Sample_Gas_Requisition.pdf
test -s verification/In_App_PDF_Preview.png
test -s verification/UI_Receipt_Highlight.png
test -s verification/UI_Card_Mismatch.png
test -s verification/UI_Saved_Card_Verification.png
test -s verification/UI_Receipt_Guided_Search.png
test -s verification/UI_Finance_Guided_Search.png
# Prove Android can install a newer version over the same package and signer,
# while retaining a private on-device marker. This uses only the runner's
# ephemeral debug signer, NOT the permanent private release key.
old_debug_apk=$(mktemp /tmp/grt-old-XXXXXX.apk)
cp app/build/outputs/apk/debug/app-debug.apk "$old_debug_apk"
adb install -r "$old_debug_apk"
adb shell run-as org.karuk.gasreceipttracker.preview mkdir -p files
printf 'update-marker-preserved\n' > verification/upgrade_marker.txt
adb push verification/upgrade_marker.txt /data/local/tmp/grt-upgrade-marker.txt
adb shell run-as org.karuk.gasreceipttracker.preview cp /data/local/tmp/grt-upgrade-marker.txt files/upgrade_marker.txt
adb shell run-as org.karuk.gasreceipttracker.preview cat files/upgrade_marker.txt | grep -q 'update-marker-preserved'
gradle --no-daemon --stacktrace :app:assembleDebug -PtestVersionCodeOverride=21
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell run-as org.karuk.gasreceipttracker.preview cat files/upgrade_marker.txt | grep -q 'update-marker-preserved'
cp "$old_debug_apk" app/build/outputs/apk/debug/app-debug.apk
rm -f "$old_debug_apk"
echo 'In-place version 20-to-21 update retained on-device app data.' | tee verification/IN_PLACE_UPDATE_RESULT.txt

signer=$(find "$ANDROID_HOME/build-tools" -name apksigner -type f | sort -V | tail -n 1)
"$signer" verify --verbose --print-certs app/build/outputs/apk/debug/app-debug.apk > verification/APK_SIGNATURE_VERIFICATION.txt
sha256sum app/build/outputs/apk/debug/app-debug.apk > verification/APK_SHA256.txt