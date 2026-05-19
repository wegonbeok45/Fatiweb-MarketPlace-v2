const admin = require("firebase-admin");
const path = require("path");

const serviceAccount = process.env.FIREBASE_CONFIG
  ? undefined
  : { projectId: "fatiweb-marketplace" };

admin.initializeApp({
  credential: admin.credential.applicationDefault(),
  projectId: "fatiweb-marketplace",
});

const USER_ID = "yyXYoPZV7aO5hcr86NvLqv1O2d93";
const db = admin.firestore();

async function triggerClaimsSync() {
  const userRef = db.collection("users").doc(USER_ID);
  const doc = await userRef.get();

  if (!doc.exists) {
    console.error("User document not found");
    return;
  }

  const role = doc.get("role");
  console.log("Current role from Firestore:", role);

  // Touch the document to trigger syncUserRoleClaims
  await userRef.set({ _claimsSync: admin.firestore.FieldValue.delete() }, { merge: true });
  console.log("Cleaned up _claimsSync marker");

  // Set the claims synchronously using Admin SDK as well
  const isAdmin = role === "admin";
  await admin.auth().setCustomUserClaims(USER_ID, {
    admin: isAdmin,
    role: role || "client",
  });

  console.log("Custom claims set via Admin SDK:", { admin: isAdmin, role: role || "client" });

  // Verify
  const user = await admin.auth().getUser(USER_ID);
  console.log("Verified custom claims:", user.customClaims);
}

triggerClaimsSync().catch(console.error);
