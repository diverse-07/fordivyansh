# Netra Saathi Android

The built module is app/ under this directory. Requires JDK 17 and Android SDK Platform 36.
Set sdk.dir in ignored local.properties, then run:

    ./gradlew testDebugUnitTest lintDebug assembleDebug

APK: app/build/outputs/apk/debug/app-debug.apk (ARM64, Android 8.0+).

Controls: hold Volume Up for two seconds for voice; three releases in 1.2 seconds
for a local distress alarm; Volume Up releases also stop an active alarm.
Ordinary single/double volume increments are deferred until the gesture window expires.
The supported offline wake phrase is Hey Vision. Netra Saathi is the product name.

Startup checks the Android text-to-speech engine connection. If the preferred engine
and enabled installed alternatives fail, Google Play opens Speech Recognition &
Synthesis from Google for installation or update (with a browser fallback). The
redirect is limited to once per activity session. Returning retries the connection;
Test speech explicitly reconnects. Speech still needs installed offline language
data, available in Android Text-to-speech settings.

Scanning uses RF-DETR COCO. Potholes, damaged surfaces and overhead hazards are not
reliably detected. The unused pothole asset does not imply enabled coverage.
Distance is an unvalidated estimate; unknown distance remains unknown.
Sonar indicates the side of an observed obstacle, not a safe steering direction.

The alarm sends no guardian notifications. Device-specific Bluetooth/speaker routing,
TTS, volume restoration and emergency behavior need physical-device verification.
Scanning/navigation stops in the background.

gateway.properties contains the deployed Gemini Worker's HTTPS origin. No key or
shared access token is bundled. Local development can override the URL in debug
Settings; clear any old override to return to the bundled origin. Release signing reads
NETRASAATHI_KEYSTORE_FILE, NETRASAATHI_KEYSTORE_PASSWORD, NETRASAATHI_KEY_ALIAS and
NETRASAATHI_KEY_PASSWORD; version properties are netraSaathiVersionCode/Name.
Release builds intentionally require signing and a configured gateway.

Preserve upstream attribution in LICENSE, NOTICE and licenses/.
