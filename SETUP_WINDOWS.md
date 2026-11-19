# Windows development setup

Run these PowerShell commands from the repository root. Keep credentials in local
environment files; they are ignored by Git.

## Backend

Use Python 3.11 or newer and an isolated virtual environment:

```powershell
cd backend
python -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r requirements.lock
.\.venv\Scripts\python.exe -m unittest discover -s tests -v
.\.venv\Scripts\python.exe -m uvicorn app.main:app --reload --port 8000
```

Open http://localhost:8000/docs for the API explorer. AI routes require the server-side
access token and Gemini credentials/model configured in the ignored .env file.
Missing configuration returns an explicit unavailable error; it never simulates hazards.

## Caretaker dashboard

Use Node.js 22.13 or newer. In a separate terminal:

```powershell
cd dashboard
npm ci
npm run build
npm run dev
```

Open http://localhost:3000. The current page shows unknown/unavailable data and an explicitly local alert preview.
Live telemetry, notifications and guardian authentication are not implemented.

## Android app

Install JDK 17 and Android SDK Platform 36 through Android Studio's SDK Manager.
Set `JAVA_HOME` to your JDK directory. Gradle uses that JDK rather than a
machine-specific path committed to the repository.

Set the SDK path in the ignored `android-app/local.properties` file. For example,
with the standard Android Studio SDK location (replace the username):

```properties
sdk.dir=C\:/Users/YOUR_USERNAME/AppData/Local/Android/Sdk
```

Then run:

```powershell
cd android-app
.\gradlew.bat testDebugUnitTest
.\gradlew.bat assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.
Android tests and APK builds require the SDK even though the unit tests run on
the JVM. Test counts in the handoff are historical until verified locally.

## Optional Cloudflare gateway

The Android app also includes a separate Node.js gateway under `android-app/worker`.
Its mocked unit tests require no API keys or dependency installation:

```powershell
cd android-app/worker
npm test
```

For local gateway execution and credentials, follow its [README](android-app/worker/README.md).

## Restricted development environments

If the environment prevents writes to the default Gradle or temporary directory,
point the corresponding process variables at writable local directories before
running the commands. These are local settings, not repository configuration:

```powershell
$env:GRADLE_USER_HOME = Join-Path $env:LOCALAPPDATA 'NetraSaathi/gradle'
```

Dependency installation and the first Gradle build need internet access.
