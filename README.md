# FatiWeb Market

FatiWeb Market is an Android commerce app focused on a Tunisia-first marketplace experience with cash on delivery, a Firebase-backed catalog, and an internal admin back office.

## UI/UX Source of Truth

All FatiWeb mobile UI work must follow this guide. When implementing or changing any screen, do not invent a new design language. Use this system first, then extend it consistently only when the screen needs a missing component.

### Objective

Recreate and extend the FatiWeb mobile app using a clean, premium, minimal ecommerce design system inspired by CURATOR-style UI.

All implementations must be:

- Pixel-perfect
- Consistent across screens
- Reusable and component-based

### Layout and Screen Rules

- Aspect ratio: 9:16
- Base iPhone size: 390 x 844
- Base Android size: 360 x 800
- Export size: 1080 x 1920
- Safe-area horizontal padding: 24px
- Vertical spacing between sections: 32px

### Color System

Use these colors strictly:

```css
--bg: #F7F9FA;
--card: #FFFFFF;
--soft-bg: #F0F3F5;

--text-primary: #11181C;
--text-secondary: #6B767C;
--text-muted: #9AA4A9;

--border: #E3E8EA;

--accent-main: #2F3A3D;
--accent-premium: #C9A272;
--accent-light: #E8D8C2;
```

Rules:

- Use neutral tones 90% of the time.
- Use accent colors only for CTA buttons, highlights, and active states.
- Do not use random colors outside this system.

### Typography

Font: Inter. This is mandatory.

| Usage | Size | Weight |
| --- | --- | --- |
| Header / Hero | 26-28px | Bold |
| Section Title | 20-22px | Semibold |
| Card Title | 15-16px | Semibold |
| Price | 14-16px | Medium |
| Small Text | 12-13px | Regular |
| Nav Labels | 11-12px | Regular |

- Line height: 1.4
- Do not mix in other font families unless the user explicitly updates this guide.

### Spacing System

- Screen padding: 24px
- Section gap: 32px
- Grid gap: 16px
- Card padding: 12-14px
- Text spacing: 6-8px

Never reduce spacing just to fit more content. Preserve breathing room.

### Core Components

#### Search Bar

- Height: 52px
- Radius: 20px
- Left icon plus placeholder
- Background: soft gray

#### Hero Banner

- Height: 160-180px
- Radius: 24px
- Image-based background
- Includes title, subtitle, and CTA button

#### Product Card

- Reusable component
- Grid: 2 columns
- Width: 48%
- Radius: 20px
- Includes product image at top, title, category small text, price, and top-right wishlist icon

#### Category Card

Replace icon-only categories with image-based cards.

- Grid: 2 columns
- Height: 100-120px
- Radius: 18px
- Includes category image, label, and "Explore ->" CTA

#### Featured Section

Use one of these layouts:

- Large card plus 2 small stacked cards
- Horizontal scroll

#### Bottom Navigation

- Height: 88-96px
- Items: Home, Explore, Cart, Profile
- Active color: #2F3A3D
- Inactive color: #9AA4A9

### Required Home Screen Sections

1. Header with logo and icons
2. Search bar
3. Hero banner
4. Categories, either horizontal or cards
5. Featured section
6. New Arrivals with multiple rows
7. Shop by Category image cards
8. Why FatiWeb trust section
9. Bottom navigation

### Mandatory Extensions

#### New Arrivals

- Include at least 2-3 rows.
- Maintain the same product card style throughout.

#### Why FatiWeb

Add 3-4 feature cards:

- Curated Quality
- Secure Checkout
- Fast Delivery
- Easy Returns

Each card includes an icon, title, and short description.

### Visual Style Rules

- Use soft shadows only.
- Avoid heavy borders.
- Always use rounded corners.
- Use clean product images with no clutter.
- Keep the UI minimal and breathable.

### Development Principles

- Build reusable components:
  - ProductCard
  - CategoryCard
  - SectionHeader
- Maintain the spacing system.
- Do not improvise colors or sizes.
- Follow the design system strictly.

### What Not To Do

- Do not use random colors.
- Do not reduce spacing to fit more items.
- Do not mix different UI styles.
- Do not use sharp edges or 0 radius.
- Do not overcrowd screens.

### Final Goal

The app must feel like a premium ecommerce experience: clean, modern, high-end, and inspired by Apple Store, Zara, and CURATOR-style layouts.

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

Or use the repo helper so Gradle always runs against Android Studio JBR:

```powershell
.\scripts\gradlew-jbr.ps1 testDebugUnitTest
```

## Quality Gates

These gates are mandatory before shipping any candidate build:

```powershell
.\gradlew.bat testDebugUnitTest
.\gradlew.bat lintDebug
.\gradlew.bat assembleRelease
npm --prefix firebase_functions_setup run build
npm --prefix firebase_functions_setup test
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
- Keep `GEMINI_API_KEY` in Firebase Functions secrets only. Do not commit it in app or Gradle config.
