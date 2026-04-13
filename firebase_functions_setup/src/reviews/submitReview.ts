import {logger} from "firebase-functions";
import {HttpsError, onCall} from "firebase-functions/v2/https";
import {COLLECTIONS, SCHEMA_VERSION} from "../shared/constants";
import {assertAuthenticated} from "../shared/auth";
import {admin, db} from "../shared/firestore";
import {asMillis, asNumber, asRecord, asString} from "../shared/domain";

export const submitReview = onCall(async (request) => {
  const authContext = assertAuthenticated(request);
  const payload = asRecord(request.data) || {};
  const productId = asString(payload.productId).trim();
  const reviewPayload = asRecord(payload.review) || payload;
  const rating = Math.max(1, Math.min(5, Math.floor(asNumber(reviewPayload.rating))));
  const comment = asString(reviewPayload.comment).trim();

  if (!productId || comment.length < 3) {
    throw new HttpsError("invalid-argument", "A productId and review comment are required.");
  }

  const productRef = db.collection(COLLECTIONS.PRODUCTS).doc(productId);
  const reviewRef = productRef.collection("reviews").doc(authContext.uid);
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
