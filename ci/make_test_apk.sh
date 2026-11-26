#!/usr/bin/env bash
# make_test_apk.sh <out.apk> : builds a tiny app with no code (dev.liquid.vault.testapp) for the emulator test of
# "copy an app you downloaded into the Vault" (D55). Built here with the SDK's own tools, so the test needs no download
# and no change to the workflow. Its launcher entry starts Android's empty Activity.
set -euo pipefail
out=$1
sdk=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/usr/local/lib/android/sdk}}
bt=$(ls -d "$sdk"/build-tools/* | sort -V | tail -1)
jar=$(ls "$sdk"/platforms/android-*/android.jar | sort -V | tail -1)
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
cat > "$work/AndroidManifest.xml" <<'XML'
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="dev.liquid.vault.testapp" android:versionCode="1" android:versionName="1">
    <uses-sdk android:minSdkVersion="26" android:targetSdkVersion="34" />
    <application android:label="LV test app" android:hasCode="false">
        <activity android:name="android.app.Activity" android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
XML
"$bt/aapt2" link -o "$work/unsigned.apk" -I "$jar" --manifest "$work/AndroidManifest.xml"
"$bt/zipalign" -f 4 "$work/unsigned.apk" "$work/aligned.apk"
keytool -genkeypair -keystore "$work/test.jks" -storepass testpass -keypass testpass -alias test \
  -keyalg RSA -keysize 2048 -validity 1000 -dname "CN=Liquid Vault test app" >/dev/null 2>&1
"$bt/apksigner" sign --ks "$work/test.jks" --ks-pass pass:testpass --key-pass pass:testpass --out "$out" "$work/aligned.apk"
echo "test app: $out ($(stat -c %s "$out") bytes, build-tools $(basename "$bt"), $(basename "$(dirname "$jar")"))"
