# FatiWeb Marketplace - Server-Side Security

This directory contains the Firebase Cloud Functions necessary to secure the FatiWeb Marketplace checkout process.

## Why is this needed?
Currently, the mobile app calculates the order total and writes it to Firestore. A malicious user could tamper with the app (or use the REST API) to send an order total of `0.0 DT`. 

The `verifyOrderTotal` function deployed here intercepts every new order, reads the *actual* product prices from the database, recalculates the subtotal and shipping, and forcefully corrects the order document.

## How to deploy

Since the `firebase` CLI is not installed globally on your machine, follow these steps when you are ready to secure the backend:

1. **Install Node.js** (Version 18+ is recommended).
2. **Install Firebase CLI** globally:
   ```bash
   npm install -g firebase-tools
   ```
3. **Login to Firebase**:
   ```bash
   firebase login
   ```
4. **Initialize Project** (if not already done, link this to your Firebase project):
   ```bash
   firebase use --add
   ```
5. **Install Dependencies**:
   ```bash
   cd firebase_functions_setup
   npm install
   ```
6. **Deploy**:
   ```bash
   firebase deploy --only functions
   ```

*Note: Firebase Cloud Functions requires your Firebase Project to be on the **Blaze (Pay-as-you-go)** billing plan. You will not be charged unless you exceed the massive free tier, but a credit card is required by Google for Node environments.*
