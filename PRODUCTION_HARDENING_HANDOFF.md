# Production Hardening — Autonomous Session Handoff

**Session date:** 2026-05-20
**Branch:** `main`
**Build status:** `./gradlew compileDebugKotlin` → **BUILD SUCCESSFUL** at HEAD
**Cloud Functions deployed:** `deleteUserAccount(europe-west1)` is live in `fatiweb-marketplace`.

## ✅ Shipped this session (11 commits)

| Commit | Item | Summary |
|---|---|---|
| `3c1f7bb` | wip snapshot | Preserved your in-progress phone-auth work before the sweep. |
| `b73ea5c` | **B-1** | `ProfileTabFragment.refreshUserInfo()` now force-refreshes role on every resume. Admin/vendor entry appears without restart. Dead commented block removed. |
| `7e32db8` | **B-5 / B-6 / B-7** | Storage upload cap aligned at 4.5 MB; App Check uses direct `DebugAppCheckProviderFactory` import (no reflection); Firestore migrated to non-deprecated `PersistentCacheSettings`. |
| `25f2736` | **B-2** | Captured `currentUser` once before deref to eliminate the checkout `!!` race. |
| `d5fd0a0` | **H-1 / H-3** | Killed `restoreAvatar()` duplicate Firestore read; throttled `MainActivity.onResume` heavy work to once per 5 min. |
| `c419822` | **H-5** | Admin dashboard fetches now gated on `_isVerified == true`; retry triggered by isVerified observer. |
| `54b300a` | **F-17** | **Play Store Data Safety blocker:** self-service account deletion. New `deleteUserAccount` callable function (deployed) + "Supprimer mon compte" button on PersonalDetailsActivity with confirmation dialog. |
| `1f52eef` | **H-2** | CartTabFragment refreshes from cloud on first visit per process — fixes stale cart when user taps the tab inside the 1.2 s startup delay. |
| `29bb596` | **M-3** | Replaced all 18 `showToast` call sites with `showMotionSnackbar`; dropped the deprecated wrapper. |

**Net:** 9 audit items fully addressed across 5 Phase A blockers, 4 Phase H stability/perf items, 1 Phase F policy item, and 1 Phase M cleanup. Build is green.

## ❌ Audit findings that were already fixed (no action needed)

- **B-3 buyer reviews.** The audit said writes are blocked. In reality the `allow write: if false` rule is **correct** — submission flows through the `submitReview` Cloud Function (admin SDK, bypasses rules), with purchase verification, transactional aggregate updates, and dedupe-by-user. Client UI exists at `ProductDetailsScreen.kt:801` (`submitReview`) and wires through `BackendFunctionsService.submitReview` → deployed function. The feature works.
- **M-10 checkout form survives process death.** EditText fields with `android:id` auto-save via `View.onSaveInstanceState`. Form already survives.
- **H-7 custom auth claims** — `syncUserRoleClaims` Firestore trigger function already exists and propagates `role`/`admin` claims on user doc writes. (Remaining work: rewrite `firestore.rules` / `storage.rules` to **read** the claims instead of doing Firestore lookups. Risky to do autonomously — see below.)

## ⚠️ Not addressed — requires focused effort or your input

Sorted by Play-readiness priority.

### Hard blockers needing your decisions

| Item | Why not done | What you owe |
|---|---|---|
| **Privacy policy URL** | Play Console requires a hosted URL. | Host a privacy policy. Add `<meta-data android:name="android.intent.action.VIEW_PRIVACY_POLICY"/>` if you go that route, or just include the URL in Play Console. |
| **Account-deletion web URL** | Play Data Safety requires both an in-app and a web-facing URL where users can request deletion. In-app side is done. | Host a public web page that explains the deletion flow. Tell Play Console its URL. |
| **B-4 string renames (193 keys)** | 4–8 hours of mechanical work renaming `auto_text_*` / `str_*` to semantic names + updating every Kotlin/XML reference. High regression risk if done in a rush. | Schedule a dedicated session — I can do it cleanly when it's the focus. |
| **Arabic localization (F-10)** | Depends on B-4. After rename, draft `values-ar/strings.xml` in MSA; you review. | Same — needs B-4 first. |
| **PDF invoices (F-16)** | Needs Tunisian legal entity name, address, matricule fiscal. | Provide legal details. |

### Big items I deliberately did NOT touch (high risk of breakage)

- **H-9 god-class breakup** — `HomeTabFragment` (900+ lines), `SearchActivity` (1400+), `ProfileTabFragment` (740+). Refactor requires careful testing; ~1 week.
- **H-12 GoogleSignIn → Credential Manager migration.** Touch the wrong thing and Google sign-in breaks for everyone. Needs dedicated session + manual device QA.
- **H-7 rules rewrite to use auth claims.** Custom claims sync function exists, but rewriting `firestore.rules` + `storage.rules` to consume `request.auth.token.role` instead of Firestore lookups risks denying legitimate users whose claims haven't propagated yet. Needs careful migration: deploy new rules in parallel with old, monitor denial rate, cut over.
- **M-2 `overridePendingTransition` → `overrideActivityTransition` migration.** 20+ call sites, requires API 34 + SDK gating. Mechanical but each call site needs manual verification of the transition still feels right.
- **H-17 audience backfill migration.** Data migration via Cloud Function — irreversible. Should be scripted, dry-run on staging first.
- **F-4 vendor payouts dashboard, F-5 vendor analytics** — multi-day features each.

### Polish/feature work for later sessions

- **H-4 chat history persistence** (~30 min — Room or JSON-on-disk; pick one).
- **H-8 better error mapping in AdminProductEditorActivity** — separate Firestore vs Storage vs validation errors in Crashlytics.
- **H-11 chat keyboard `WindowInsetsCompat.Type.ime()`** — small but needs device testing.
- **H-15 admin stats retry UI** — small.
- **H-16 shimmer skeletons** — 1–2 days across product grid / orders / cart / favorites / search.
- **F-1 buyer review submission UI polish** (the function works; UI could be improved post-delivery).
- **F-2 order tracking timeline** — `trackingEvents` exists in data, needs a step UI.
- **F-3 re-order button** — small (CartStore loop + nav), about 30 min including layout XML.
- **F-15 product/category/promo deep links** — MainActivity intent extras already handles `conversationId` / `orderId`; extend to `productId` etc.
- **F-19 search filters (brand / rating / in-stock)**, **F-20 restock notifications**, **F-7 wishlist sharing**, **F-8 coupons**, **F-9 loyalty** — each a feature.
- **Phase D polish** — typography/spacing unification, dark-mode pass, tablet layouts, ViewBinding sweep, motion polish, swipe-to-mark-read.
- **Phase F security hardening** — App Check enforcement on Firestore, audit logs collection, anonymous-user cleanup scheduler, PII redaction in Crashlytics.

## Recommended next session prompts

To resume efficiently, give me one focused ask per session. Examples:

1. *"Do B-4: rename all auto_text_*/str_* strings to semantic names. Verify all Kotlin/XML references update. Commit per logical group."*
2. *"Do H-12: migrate GoogleSignIn to Credential Manager in LoginActivity and RegisterActivity. Don't break the sign-up flow."*
3. *"Do F-3 + F-2: add re-order button and order tracking timeline on OrderDetailsActivity."*
4. *"Do H-9 partial: extract HomeTabFragment sections into separate presenters."*

## How to test what shipped

1. **B-1:** Promote a user to `vendeur` in Firestore. Open the app cold; tap Profile. Vendor dashboard button should appear without restart.
2. **B-2:** Smoke test checkout flow as a logged-in and anonymous user.
3. **F-17:** Settings → Personal details → "Supprimer mon compte" → confirm. User should be signed out and routed to Login. Firestore user doc + auth record should be gone. Past orders should remain but with `customerName: "[deleted]"`.
4. **H-2:** Add an item to cart on device A. On device B (same account), kill and re-open the app, immediately tap Cart. Item should appear.
5. **H-3:** Open the app, navigate to a product, return to home, repeat 5×. Should see only one FCM token sync + one notifications cloud refresh in logs.
6. **M-3:** All success/error feedback should use the bottom slide-in snackbar, not Android toasts.

## Realistic finish-line estimate

To get this app Play-Store-approvable: **3–5 focused sessions** like this one for the remaining Phase A + critical Phase B items.
To get it to "Airbnb-quality" per your original audit: **10–14 weeks of focused engineering**, no shortcuts.
