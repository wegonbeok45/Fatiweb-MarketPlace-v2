# FatiWeb Cloud Functions

This directory contains the trusted backend workflows for the app.

## What lives here

- Auth triggers
- admin/product callables
- checkout/order callables
- review aggregation
- announcement fan-out
- assistant gateway with the Gemini secret kept on the server
- shared validation and domain helpers

## Main exported functions

- `onAuthUserCreate`
- `syncUserRoleClaims`
- `createOrder`
- `updateOrderStatus`
- `adminUpsertProduct`
- `adminDeleteProduct`
- `adminSendAnnouncement`
- `submitReview`
- `assistantSendMessage`

## Local commands

```bash
cd firebase_functions_setup
npm install
npm run build
npm test
```

## Deploy

```bash
firebase deploy --only functions,firestore:rules,firestore:indexes,storage
```

## Required secret

Set the Gemini secret before deploying the assistant function:

```bash
firebase functions:secrets:set GEMINI_API_KEY
```

## Notes

- Deploy target is Firebase Functions on Node 18.
- The Android client now calls these functions for trusted operations instead of writing sensitive data directly.
