import {logger} from "firebase-functions";
import {HttpsError, onCall} from "firebase-functions/v2/https";
import {COLLECTIONS} from "../shared/constants";
import {assertAdmin} from "../shared/auth";
import {db} from "../shared/firestore";
import {asRecord, asString} from "../shared/domain";

export const adminDeleteProduct = onCall(async (request) => {
  await assertAdmin(request);
  const payload = asRecord(request.data) || {};
  const productId = asString(payload.productId).trim();

  if (!productId) {
    throw new HttpsError("invalid-argument", "productId is required.");
  }

  await db.collection(COLLECTIONS.PRODUCTS).doc(productId).delete();
  logger.info("Product deleted through admin callable", {productId});
  return {deleted: true, productId};
});
