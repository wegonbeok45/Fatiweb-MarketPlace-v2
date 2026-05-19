import {logger} from "firebase-functions";
import {HttpsError, onCall} from "firebase-functions/v2/https";
import {COLLECTIONS, USER_ROLES} from "../shared/constants";
import {assertAdmin} from "../shared/auth";
import {admin, auth, db} from "../shared/firestore";
import {trustedCallableOptions} from "../shared/callableOptions";
import {asRecord, asString} from "../shared/domain";

export const adminPromoteUserToVendeur = onCall(trustedCallableOptions, async (request) => {
  const adminContext = await assertAdmin(request);
  const payload = asRecord(request.data) || {};
  const userId = asString(payload.userId).trim();

  if (!userId) {
    throw new HttpsError("invalid-argument", "userId is required.");
  }

  const userRef = db.collection(COLLECTIONS.USERS).doc(userId);
  const userDoc = await userRef.get();
  if (!userDoc.exists) {
    throw new HttpsError("not-found", "User was not found.");
  }

  const currentRole = asString(userDoc.get("role"), USER_ROLES.CLIENT).trim().toLowerCase();
  if (currentRole === USER_ROLES.ADMIN) {
    throw new HttpsError("failed-precondition", "Admin users cannot be promoted to vendeur.");
  }

  if (currentRole !== USER_ROLES.VENDEUR) {
    await userRef.set({
      role: USER_ROLES.VENDEUR,
      sellerAccessGrantedAt: admin.firestore.FieldValue.serverTimestamp(),
      sellerAccessGrantedBy: adminContext.uid,
      updatedAt: admin.firestore.FieldValue.serverTimestamp(),
    }, {merge: true});
  }

  const userRecord = await auth.getUser(userId);
  const existingClaims = userRecord.customClaims || {};
  await auth.setCustomUserClaims(userId, {
    ...existingClaims,
    admin: false,
    role: USER_ROLES.VENDEUR,
  });

  logger.info("User promoted to vendeur", {
    userId,
    previousRole: currentRole,
    promotedBy: adminContext.uid,
    claimsSynced: true,
  });

  return {
    userId,
    role: USER_ROLES.VENDEUR,
    claimsSynced: true,
  };
});
