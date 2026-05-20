import {logger} from "firebase-functions";
import {HttpsError, onCall} from "firebase-functions/v2/https";
import {COLLECTIONS, USER_ROLES, USER_SUBCOLLECTIONS} from "../shared/constants";
import {assertAuthenticated} from "../shared/auth";
import {admin, auth, db} from "../shared/firestore";
import {trustedCallableOptions} from "../shared/callableOptions";
import {asString} from "../shared/domain";

/**
 * Self-service account deletion (Play Store Data Safety requirement).
 *
 * Removes the caller's Firebase Auth record and scrubs their user document,
 * subcollections, in-app inbox, conversations memberships, FCM tokens.
 * Existing orders are PII-redacted, not deleted, to preserve seller history.
 *
 * Admins cannot self-delete (must be removed by another admin).
 */
export const deleteUserAccount = onCall(trustedCallableOptions, async (request) => {
  const authContext = assertAuthenticated(request);
  const uid = authContext.uid;

  const userRef = db.collection(COLLECTIONS.USERS).doc(uid);
  const userDoc = await userRef.get();
  const role = asString(userDoc.get("role"), USER_ROLES.CLIENT).trim().toLowerCase();
  if (role === USER_ROLES.ADMIN) {
    throw new HttpsError(
      "failed-precondition",
      "Admin accounts cannot self-delete. Contact another admin."
    );
  }

  // 1. Drop user subcollections (addresses, favorites, cart, inbox, etc.).
  const subcollections = Object.values(USER_SUBCOLLECTIONS);
  for (const sub of subcollections) {
    await deleteCollectionInBatches(userRef.collection(sub));
  }

  // 2. PII-scrub orders this user placed (keep the order for seller history).
  const orderSnap = await db.collection(COLLECTIONS.ORDERS)
    .where("uid", "==", uid)
    .get();
  if (!orderSnap.empty) {
    const ordersBatch = db.batch();
    for (const doc of orderSnap.docs) {
      ordersBatch.update(doc.ref, {
        customerName: "[deleted]",
        customerEmail: null,
        customerPhone: null,
        deliveryAddress: null,
        accountDeletedAt: admin.firestore.FieldValue.serverTimestamp(),
      });
    }
    await ordersBatch.commit();
  }

  // 3. Hide user from conversations (best-effort — chats stay for the seller).
  const convSnap = await db.collection(COLLECTIONS.CONVERSATIONS)
    .where("participantIds", "array-contains", uid)
    .get();
  if (!convSnap.empty) {
    const convBatch = db.batch();
    for (const doc of convSnap.docs) {
      convBatch.update(doc.ref, {
        [`hiddenBy.${uid}`]: admin.firestore.FieldValue.serverTimestamp(),
      });
    }
    await convBatch.commit();
  }

  // 4. Delete the user document itself.
  await userRef.delete().catch((err: unknown) => {
    logger.warn("user doc delete failed (continuing)", {uid, err});
  });

  // 5. Delete the Firebase Auth record (irreversible).
  await auth.deleteUser(uid);

  logger.info("Account self-deleted", {uid});
  return {success: true};
});

async function deleteCollectionInBatches(
  ref: FirebaseFirestore.CollectionReference,
  batchSize = 200
): Promise<void> {
  for (;;) {
    const snap = await ref.limit(batchSize).get();
    if (snap.empty) return;
    const batch = db.batch();
    snap.docs.forEach((doc) => batch.delete(doc.ref));
    await batch.commit();
    if (snap.size < batchSize) return;
  }
}
