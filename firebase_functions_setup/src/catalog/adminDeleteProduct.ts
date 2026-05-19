import {logger} from "firebase-functions";
import {HttpsError, onCall} from "firebase-functions/v2/https";
import {COLLECTIONS, USER_ROLES} from "../shared/constants";
import {assertAdminOrVendeur} from "../shared/auth";
import {db} from "../shared/firestore";
import {trustedCallableOptions} from "../shared/callableOptions";
import {asRecord, asString} from "../shared/domain";

export const adminDeleteProduct = onCall(trustedCallableOptions, async (request) => {
  const actor = await assertAdminOrVendeur(request);
  const payload = asRecord(request.data) || {};
  const productId = asString(payload.productId).trim();

  if (!productId) {
    throw new HttpsError("invalid-argument", "productId is required.");
  }

  const productRef = db.collection(COLLECTIONS.PRODUCTS).doc(productId);
  const productDoc = await productRef.get();
  if (!productDoc.exists) {
    throw new HttpsError("not-found", "Product was not found.");
  }
  const sellerId = asString(productDoc.get("sellerId")).trim();
  if (actor.role === USER_ROLES.VENDEUR && sellerId !== actor.uid) {
    throw new HttpsError("permission-denied", "You can delete only your own products.");
  }

  await productRef.delete();
  logger.info("Product deleted through admin callable", {
    productId,
    actorUid: actor.uid,
    actorRole: actor.role,
    sellerId,
  });
  return {deleted: true, productId};
});
