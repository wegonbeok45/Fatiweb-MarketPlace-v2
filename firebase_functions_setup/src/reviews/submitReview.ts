import {logger} from "firebase-functions";
import {HttpsError, onCall} from "firebase-functions/v2/https";
import {COLLECTIONS, SCHEMA_VERSION} from "../shared/constants";
import {assertAuthenticated} from "../shared/auth";
import {admin, db} from "../shared/firestore";
import {trustedCallableOptions} from "../shared/callableOptions";
import {asMillis, asNumber, asRecord, asString} from "../shared/domain";

const MAX_REVIEW_COMMENT_LENGTH = 1000;

export const submitReview = onCall(trustedCallableOptions, async (request) => {
  const authContext = assertAuthenticated(request);
  const payload = asRecord(request.data) || {};
  const productId = asString(payload.productId).trim();
  const reviewPayload = asRecord(payload.review) || payload;
  const rating = Math.max(1, Math.min(5, Math.floor(asNumber(reviewPayload.rating))));
  const comment = asString(reviewPayload.comment).trim();

  if (!productId || comment.length < 3 || comment.length > MAX_REVIEW_COMMENT_LENGTH) {
    throw new HttpsError("invalid-argument", "A productId and review comment are required.");
  }

  const orderSnapshot = await db.collection(COLLECTIONS.ORDERS)
    .where("uid", "==", authContext.uid)
    .orderBy("createdAt", "desc")
    .limit(50)
    .get();
  const hasPurchased = orderSnapshot.docs.some((doc) => {
    const status = asString(doc.get("status")).trim().toLowerCase();
    if (status !== "delivered") {
      return false;
    }
    const items = doc.get("items");
    return Array.isArray(items) && items.some((item) => {
      const record = asRecord(item);
      return asString(record?.productId).trim() === productId;
    });
  });
  if (!hasPurchased) {
    throw new HttpsError("failed-precondition", "Only verified purchasers can review this product.");
  }

  const productRef = db.collection(COLLECTIONS.PRODUCTS).doc(productId);
  const existingReviewSnapshot = await productRef.collection("reviews")
    .where("userId", "==", authContext.uid)
    .limit(1)
    .get();
  const reviewRef = existingReviewSnapshot.docs[0]?.ref ??
    productRef.collection("reviews").doc(authContext.uid);
  const userRef = db.collection(COLLECTIONS.USERS).doc(authContext.uid);
  const nowMs = Date.now();
  let responseReview: Record<string, unknown> | null = null;

  await db.runTransaction(async (transaction) => {
    const productDoc = await transaction.get(productRef);
    const existingReviewDoc = await transaction.get(reviewRef);
    const userDoc = await transaction.get(userRef);

    if (!productDoc.exists) {
      throw new HttpsError("not-found", "Product not found.");
    }
    if (asString(productDoc.get("sellerId")).trim() === authContext.uid) {
      throw new HttpsError("failed-precondition", "You cannot review your own product.");
    }

    const currentRating = asNumber(productDoc.get("rating"), 0);
    const currentCount = Math.max(0, Math.floor(asNumber(productDoc.get("reviewsCount"), 0)));
    const existingRating = existingReviewDoc.exists ?
      Math.max(1, Math.min(5, Math.floor(asNumber(existingReviewDoc.get("rating"), rating)))) :
      null;

    const nextCount = existingRating == null ? currentCount + 1 : currentCount;
    const nextRating = existingRating == null ?
      ((currentRating * currentCount) + rating) / nextCount :
      (((currentRating * currentCount) - existingRating + rating) / Math.max(nextCount, 1));

    const userName = asString(
      request.auth?.token?.name,
      asString(userDoc.get("name"), "Client"),
    );

    const reviewRecord = {
      reviewId: reviewRef.id,
      userId: authContext.uid,
      uid: authContext.uid,
      userName,
      productId,
      rating,
      comment,
      schemaVersion: SCHEMA_VERSION,
      createdAt: existingReviewDoc.get("createdAt") || admin.firestore.FieldValue.serverTimestamp(),
      updatedAt: admin.firestore.FieldValue.serverTimestamp(),
    };

    transaction.set(reviewRef, reviewRecord, {merge: true});
    transaction.set(productRef, {
      rating: nextRating,
      ratingAvg: nextRating,
      reviewsCount: nextCount,
      ratingCount: nextCount,
      updatedAt: admin.firestore.FieldValue.serverTimestamp(),
      schemaVersion: SCHEMA_VERSION,
    }, {merge: true});

    responseReview = {
      ...reviewRecord,
      createdAt: asMillis(existingReviewDoc.get("createdAt")) || nowMs,
      updatedAt: nowMs,
    };
  });

  logger.info("Review submitted through trusted callable", {
    productId,
    uid: authContext.uid,
    reviewId: responseReview?.["reviewId"],
  });

  return {review: responseReview};
});
