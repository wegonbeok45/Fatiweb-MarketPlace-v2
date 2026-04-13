import {logger} from "firebase-functions";
import {HttpsError, onCall} from "firebase-functions/v2/https";
import {COLLECTIONS, SCHEMA_VERSION, USER_SUBCOLLECTIONS} from "../shared/constants";
import {assertAdmin} from "../shared/auth";
import {admin, db} from "../shared/firestore";
import {
  appendOrderTrackingEvent,
  asMillis,
  asString,
  canTransitionOrderStatus,
  normalizeOrderStatus,
  asRecord,
} from "../shared/domain";

export const updateOrderStatus = onCall(async (request) => {
  await assertAdmin(request);
  const payload = asRecord(request.data) || {};
  const orderId = asString(payload.orderId).trim();
  const nextStatus = normalizeOrderStatus(payload.status);

  if (!orderId) {
    throw new HttpsError("invalid-argument", "orderId is required.");
  }

  const orderRef = db.collection(COLLECTIONS.ORDERS).doc(orderId);
  const orderDoc = await orderRef.get();
  if (!orderDoc.exists) {
    throw new HttpsError("not-found", "Order not found.");
  }

  const currentData = orderDoc.data() || {};
  const uid = asString(currentData.uid);
  const currentStatus = normalizeOrderStatus(currentData.status);
  if (!uid) {
    throw new HttpsError("failed-precondition", "Order is missing its owner.");
  }
  if (!canTransitionOrderStatus(currentStatus, nextStatus)) {
    throw new HttpsError(
      "failed-precondition",
      `Cannot change order status from ${currentStatus} to ${nextStatus}.`,
    );
  }

  const changedAt = Date.now();
  const trackingEvents = appendOrderTrackingEvent(currentData.trackingEvents, nextStatus, changedAt);
  const updatePayload = {
    status: nextStatus,
    trackingEvents,
    statusTimeline: trackingEvents,
    updatedAt: admin.firestore.FieldValue.serverTimestamp(),
    schemaVersion: SCHEMA_VERSION,
  };

  const legacyOrderRef = db.collection(COLLECTIONS.USERS)
    .doc(uid)
    .collection(USER_SUBCOLLECTIONS.ORDERS)
    .doc(orderId);
  const inboxRef = db.collection(COLLECTIONS.USERS)
    .doc(uid)
    .collection(USER_SUBCOLLECTIONS.INBOX)
    .doc();

  await orderRef.set(updatePayload, {merge: true});
  await legacyOrderRef.set(updatePayload, {merge: true});
  await inboxRef.set({
    id: inboxRef.id,
    type: "order_status_changed",
    title: "Commande mise a jour",
    body: `Le statut de votre commande ${orderId} est maintenant ${nextStatus}.`,
    route: "order_details",
    entityRef: orderId,
    orderId,
    readAt: null,
    createdAt: admin.firestore.FieldValue.serverTimestamp(),
    updatedAt: admin.firestore.FieldValue.serverTimestamp(),
    schemaVersion: SCHEMA_VERSION,
  });

  logger.info("Order status updated by admin", {
    orderId,
    uid,
    from: currentStatus,
    to: nextStatus,
  });

  return {
    order: {
      ...currentData,
      ...updatePayload,
      createdAt: asMillis(currentData.createdAt),
      updatedAt: changedAt,
    },
  };
});
