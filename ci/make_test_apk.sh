#!/usr/bin/env bash
# make_test_apk.sh <out.apk> : builds dev.liquid.vault.testapp for the emulator test, with the SDK's own tools (no
# download, no change to the workflow). It stands for any app you installed: copied into the Vault without the shell
# (D55), and as an app the access rules allow or refuse (D60): its Probe activity lists, reads or writes one content
# URI it was lent and logs "PROBE u<user> <op> OK|FAIL ..." under Liquid Vault's log tag, so the test reads one log.
set -euo pipefail
out=$1
sdk=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/usr/local/lib/android/sdk}}
bt=$(ls -d "$sdk"/build-tools/* | sort -V | tail -1)
jar=$(ls "$sdk"/platforms/android-*/android.jar | sort -V | tail -1)
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
mkdir -p "$work/src" "$work/classes" "$work/dex"
cat > "$work/AndroidManifest.xml" <<'XML'
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="dev.liquid.vault.testapp" android:versionCode="2" android:versionName="2">
    <uses-sdk android:minSdkVersion="26" android:targetSdkVersion="34" />
    <application android:label="LV test app">
        <activity android:name="android.app.Activity" android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
        <activity android:name=".Probe" android:exported="true" android:theme="@android:style/Theme.NoDisplay" />
    </application>
</manifest>
XML
cat > "$work/src/Probe.java" <<'JAVA'
package dev.liquid.vault.testapp;

import android.app.Activity;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Process;
import android.util.Log;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/** Any app, for the emulator test: lists, reads or writes one URI it was lent, and logs how it went. */
public class Probe extends Activity {
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        String op = getIntent().getStringExtra("op");
        String uri = getIntent().getStringExtra("uri");
        String text = getIntent().getStringExtra("text");
        String result;
        try {
            Uri u = Uri.parse(uri);
            if ("list".equals(op)) {
                StringBuilder names = new StringBuilder();
                Cursor c = getContentResolver().query(u, new String[] {"_display_name"}, null, null, null);
                if (c != null) {
                    try {
                        while (c.moveToNext()) {
                            if (names.length() > 0) names.append(',');
                            names.append(c.getString(0));
                        }
                    } finally {
                        c.close();
                    }
                }
                result = "OK " + names;
            } else if ("read".equals(op)) {
                InputStream in = getContentResolver().openInputStream(u);
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int n;
                try {
                    while ((n = in.read(buf)) > 0) bytes.write(buf, 0, n);
                } finally {
                    in.close();
                }
                result = "OK " + bytes.toString("UTF-8").trim().replace('\n', ' ');
            } else if ("write".equals(op)) {
                OutputStream o = getContentResolver().openOutputStream(u, "wt");
                try {
                    o.write(text.getBytes("UTF-8"));
                } finally {
                    o.close();
                }
                result = "OK wrote " + text.length() + " characters";
            } else {
                result = "FAIL unknown op " + op;
            }
        } catch (Throwable t) {
            result = "FAIL " + t.getClass().getSimpleName() + ": " + t.getMessage();
        }
        Log.i("LiquidVault", "PROBE u" + (Process.myUid() / 100000) + " " + op + " " + result);
        finish();
    }
}
JAVA
javac -nowarn -source 8 -target 8 -classpath "$jar" -d "$work/classes" "$work/src/Probe.java" 2>&1 | grep -v "^warning\|^1 warning\|bootstrap" || true
"$bt/d8" --release --min-api 26 --lib "$jar" --output "$work/dex" "$work"/classes/dev/liquid/vault/testapp/*.class
"$bt/aapt2" link -o "$work/unsigned.apk" -I "$jar" --manifest "$work/AndroidManifest.xml"
python3 - "$work/unsigned.apk" "$work/dex/classes.dex" <<'PY'
import sys, zipfile
with zipfile.ZipFile(sys.argv[1], "a", zipfile.ZIP_DEFLATED) as z:
    z.write(sys.argv[2], "classes.dex")
PY
"$bt/zipalign" -f 4 "$work/unsigned.apk" "$work/aligned.apk"
keytool -genkeypair -keystore "$work/test.jks" -storepass testpass -keypass testpass -alias test \
  -keyalg RSA -keysize 2048 -validity 1000 -dname "CN=Liquid Vault test app" >/dev/null 2>&1
"$bt/apksigner" sign --ks "$work/test.jks" --ks-pass pass:testpass --key-pass pass:testpass --out "$out" "$work/aligned.apk"
echo "test app: $out ($(stat -c %s "$out") bytes, build-tools $(basename "$bt"), $(basename "$(dirname "$jar")"))"
