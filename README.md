# FatiWeb Market

FatiWeb Market is an Android commerce app focused on a Tunisia-first marketplace experience with cash on delivery, a Firebase-backed catalog, and an internal admin back office.

## Development Setup

- Android Studio Ladybug or newer
- JDK 17 for Gradle and Android builds
- Android SDK for API 36

On Windows, a reliable local setup is:

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
.\gradlew.bat assembleDebug
```

## Quality Gates

Run these before shipping a candidate build:

```powershell
.\gradlew.bat assembleDebug
.\gradlew.bat assembleRelease
.\gradlew.bat testDebugUnitTest
.\gradlew.bat lintDebug
```

## Repeatable Live-Flow QA

The repo includes a basic Maestro-based shopper QA loop that runs against the current Firebase project with test-only writes.

One-time local prerequisites:

- Install Maestro locally so `C:\Users\ta\maestro\bin\maestro.bat` exists, or pass a custom path to the runner.
- Start an Android emulator or connect a device and confirm `adb devices` shows it.
- Use JDK 17. The runner defaults to Android Studio JBR.

Run the default shopper suite:

```powershell
.\scripts\qa-liveflow.ps1
```

Optional environment variables:

- `QA_SHOPPER_NAME`
- `QA_SHOPPER_EMAIL`
- `QA_SHOPPER_PASSWORD`
- `QA_ADDRESS_LABEL`
- `QA_ADDRESS_RECIPIENT`
- `QA_ADDRESS_PHONE`
- `QA_ADDRESS_GOVERNORATE`
- `QA_ADDRESS_CITY`
- `QA_ADDRESS_LINE1`
- `QA_ADMIN_EMAIL`
- `QA_ADMIN_PASSWORD`

Notes:

- Default runs cover onboarding, guest auth-gate behavior, registration, address creation, and COD checkout.
- Admin smoke and read-only admin navigation checks are added when `QA_ADMIN_EMAIL` and `QA_ADMIN_PASSWORD` are provided.
- Reports and screenshots are written under `artifacts/qa-liveflow/`.

## Current Product Scope

- Cash on delivery only
- Firebase Auth with email/password and Google
- Firestore-backed products, orders, shipping settings, and in-app announcements
- Structured delivery addresses with per-order snapshots
- Customer order history and order details timeline
- Internal admin flows for products, orders, clients, notifications, and shipping settings

## Launch Notes

- Do not expose unsupported payment methods in production copy or UI.
- Keep Facebook auth hidden unless it is fully configured and tested.
- Use real product image URLs for newly created admin products.
