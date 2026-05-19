import {logger} from "firebase-functions";
import {HttpsError, onCall} from "firebase-functions/v2/https";
import {COLLECTIONS, USER_ROLES} from "../shared/constants";
import {assertAdmin} from "../shared/auth";
import {admin, auth, db} from "../shared/firestore";
import {trustedCallableOptions} from "../shared/callableOptions";
import {asRecord, asString} from "../shared/domain";

export const adminRevokeVendeurAccess = onCall(trustedCallableOptions, async (request) => {
  const adminContext = await assertAdmin(request);
  const payload = asRecord(request.data) || {};
  const userId = asString(payload.userId).trim();

  if (!userId) {
    throw new HttpsError("invalid-argument", "userId is required.");
  }
  if (userId === adminContext.uid) {
    throw new HttpsError("failed-precondition", "Admins cannot revoke their own access.");
  }

  const userRef = db.collection(COLLECTIONS.USERS).doc(userId);
  const userDoc = await userRef.get();
  if (!userDoc.exists) {
    throw new HttpsError("not-found", "User was not found.");
  }

  const currentRole = asString(userDoc.get("role"), USER_ROLES.CLIENT).trim().toLowerCase();
  if (currentRole === USER_ROLES.ADMIN) {
    throw new HttpsError("failed-precondition", "Admin users cannot be revoked as vendeur.");
  }

  await userRef.set({
    role: USER_ROLES.CLIENT,
    sellerAccessRevokedAt: admin.firestore.FieldValue.serverTimestamp(),
    sellerAccessRevokedBy: adminContext.uid,
    updatedAt: admin.firestore.FieldValue.serverTimestamp(),
  }, {merge: true});

  const userRecord = await auth.getUser(userId);
  const existingClaims = userRecord.customClaims || {};
  await auth.setCustomUserClaims(userId, {
    ...existingClaims,
    admin: false,
    role: USER_ROLES.CLIENT,
  });

  logger.info("Vendeur access revoked", {
    userId,
    previousRole: currentRole,
    revokedBy: adminContext.uid,
    claimsSynced: true,
  });

  return {
    userId,
    role: USER_ROLES.CLIENT,
    claimsSynced: true,
  };
});
