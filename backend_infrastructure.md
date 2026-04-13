# FatiWeb Marketplace Backend Architecture

This project now uses a Firebase-native backend with clear trust boundaries:

- Firestore for real-time app state
- Firebase Auth for identity
- Cloud Storage for media
- Cloud Functions for trusted workflows
- Firestore Rules and Storage Rules for enforcement

## Current production model

### Canonical collections
- `/users/{uid}`
- `/users/{uid}/addresses/{addressId}`
- `/users/{uid}/favorites/{productId}`
- `/users/{uid}/cart/active`
- `/users/{uid}/inbox/{notificationId}`
- `/orders/{orderId}`
- `/products/{productId}`
- `/products/{productId}/reviews/{reviewId}`
- `/config/commerce`
- `/in_app_notifications/{notificationId}`

### Compatibility paths still supported
- `/users/{uid}/orders/{orderId}`
- `/carts/{uid}`

These legacy paths still exist so older reads do not break while the app finishes migration.

## Trusted backend workflows

The Android app no longer owns the sensitive logic for these flows:

- `createOrder`
  Server validates stock, recomputes totals, decrements inventory, clears cart, writes the order, and creates an inbox event.

- `updateOrderStatus`
  Server validates admin access and allowed status transitions before updating the order and notifying the customer.

- `adminUpsertProduct`
  Server owns privileged product create/update writes.

- `adminDeleteProduct`
  Server owns privileged product deletion.

- `adminSendAnnouncement`
  Server creates public announcements and fans them out into user inboxes.

- `submitReview`
  Server owns review writes and aggregate rating updates.

- `assistantSendMessage`
  Gemini requests now go through Cloud Functions, so the Android client no longer carries the model secret.

## Security model

### Client-writable
- own profile safe fields
- own addresses
- own favorites
- own cart
- own notification read state

### Server-only writes
- orders
- product mutations
- review aggregates
- admin stats
- announcements
- inbox event creation
- assistant rate-limit documents
- role and privilege synchronization

### Admin authorization
- Primary trust source: Firebase custom claims
- Compatibility fallback: `users/{uid}.role`

Cloud Functions sync the `admin` claim from the user profile role so the project can migrate safely without breaking existing admins.

## Real-time boundaries

Firestore listeners remain appropriate for:

- published catalog updates
- cart sync
- favorites sync
- order detail updates
- notification inbox
- admin order dashboards

The most sensitive mutations are now backend-owned even when the app still listens in real time.

## Schema conventions

Important backend-owned documents now standardize on:

- `schemaVersion`
- `createdAt`
- `updatedAt`
- explicit status fields
- server-verified monetary fields

Orders and products also keep legacy-compatible fields so the existing Android UI can continue working during migration.

## Migration status

### Implemented
- TypeScript Functions workspace under `firebase_functions_setup/src`
- trusted checkout
- trusted admin product writes
- trusted order status updates
- trusted announcement fan-out
- trusted review aggregation
- trusted AI assistant gateway
- hardened Firestore Rules
- hardened Storage Rules
- expanded Firestore indexes

### Still planned
- fuller inbox-driven notification UI on Android
- more real-time order and inbox listeners on all screens
- App Check enforcement in production
- emulator-backed Rules tests
- cleanup of remaining legacy read fallbacks once data is migrated

## Deployment notes

- Functions target Node 18 in Firebase
- Local development may run on newer Node, but deploy target remains Node 18
- Android builds should use Android Studio JBR for Gradle

## Quick verification checklist

- shopper sign-up creates a user profile doc
- checkout totals cannot be forged from the client
- stock changes happen inside trusted backend code
- admins cannot rely only on client-side role assumptions
- Gemini key is no longer embedded in the Android client
- app builds with the new callable integration

Last updated: April 2, 2026
