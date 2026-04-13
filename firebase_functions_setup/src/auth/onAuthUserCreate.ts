import {logger} from "firebase-functions";
import * as functionsV1 from "firebase-functions/v1";
import {COLLECTIONS, SCHEMA_VERSION, USER_ROLES} from "../shared/constants";
import {admin, db} from "../shared/firestore";

export const onAuthUserCreate = functionsV1.auth.user().onCreate(async (user) => {
  if (!user?.uid) {
    return;
  }

  const userRef = db.collection(COLLECTIONS.USERS).doc(user.uid);
  const snapshot = await userRef.get();
  if (snapshot.exists) {
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

  logger.info("Created user profile from Auth trigger", {uid: user.uid});
});
