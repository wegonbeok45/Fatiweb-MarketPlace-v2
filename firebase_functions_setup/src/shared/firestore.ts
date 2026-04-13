import admin from "firebase-admin";

if (admin.apps.length === 0) {
  admin.initializeApp();
}

const db = admin.firestore();
const auth = admin.auth();
const storage = admin.storage();

export {admin, auth, db, storage};
