import {logger} from "firebase-functions";
import * as functionsV1 from "firebase-functions/v1";
import {COLLECTIONS, SCHEMA_VERSION, USER_ROLES} from "../shared/constants";
import {admin, auth, db} from "../shared/firestore";
import {FUNCTIONS_REGION} from "../shared/callableOptions";

export const onAuthUserCreate = functionsV1.region(FUNCTIONS_REGION).auth.user().onCreate(async (user) => {
  if (!user?.uid) {
    return;
  }

  const userRef = db.collection(COLLECTIONS.USERS).doc(user.uid);
  const snapshot = await userRef.get();
  if (snapshot.exists) {
    await setRoleClaims(user.uid, normalizeRole(snapshot.get("role")), user.customClaims || {});
    logger.info("User profile already exists", {uid: user.uid});
    return;
  }

  const displayName = user.displayName || user.email?.split("@")[0] || "Client";
  await userRef.set({
    uid: user.uid,
    name: displayName,
    displayName,
    email: user.email || "",
    role: USER_ROLES.CLIENT,
    status: "active",
    avatarUrl: user.photoURL || null,
    avatar: user.photoURL || null,
    schemaVersion: SCHEMA_VERSION,
    createdAt: admin.firestore.FieldValue.serverTimestamp(),
    updatedAt: admin.firestore.FieldValue.serverTimestamp(),
  }, {merge: true});
  await setRoleClaims(user.uid, USER_ROLES.CLIENT, user.customClaims || {});

  logger.info("Created user profile from Auth trigger", {uid: user.uid});
});

async function setRoleClaims(
  uid: string,
  role: string,
  existingClaims: Record<string, unknown>,
): Promise<void> {
  await auth.setCustomUserClaims(uid, {
    ...existingClaims,
    admin: role === USER_ROLES.ADMIN,
    role,
  });
}

function normalizeRole(role: unknown): string {
  const value = typeof role === "string" ? role.trim().toLowerCase() : "";
  if (value === USER_ROLES.ADMIN) return USER_ROLES.ADMIN;
  if (value === USER_ROLES.VENDEUR || value === "vendor" || value === "seller") {
    return USER_ROLES.VENDEUR;
  }
  return USER_ROLES.CLIENT;
}
