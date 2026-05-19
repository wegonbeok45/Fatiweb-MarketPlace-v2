import {logger} from "firebase-functions";
import {HttpsError, onCall} from "firebase-functions/v2/https";
import {COLLECTIONS, SCHEMA_VERSION, USER_ROLES, USER_SUBCOLLECTIONS} from "../shared/constants";
import {assertAdminOrVendeur} from "../shared/auth";
import {admin, db} from "../shared/firestore";
import {trustedCallableOptions} from "../shared/callableOptions";
import {sendPushToUser} from "../notifications/push";
import {
  appendOrderTrackingEvent,
  asMillis,
  asString,
  canTransitionOrderStatus,
  normalizeOrderStatus,
  asRecord,
} from "../shared/domain";

function orderStatusLabel(status: string, language: string): string {
  const isEnglish = language.startsWith("en");
  const labels: Record<string, {en: string; fr: string}> = {
    pending: {en: "pending", fr: "en attente"},
    confirmed: {en: "confirmed", fr: "confirmee"},
    preparing: {en: "preparing", fr: "en preparation"},
    shipped: {en: "shipped", fr: "expediee"},
    delivered: {en: "delivered", fr: "livree"},
    cancelled: {en: "cancelled", fr: "annulee"},
  };
  const fallback = labels.pending;
  const entry = labels[status] || fallback;
  return isEnglish ? entry.en : entry.fr;
}

function orderStatusNotificationCopy(status: string, language: string) {
  const isEnglish = language.startsWith("en");
  const label = orderStatusLabel(status, language);
  if (isEnglish) {
    return {
      title: "Order updated",
      body: `Your order is now ${label}.`,
    };
  }
  return {
    title: "Commande mise a jour",
    body: `Votre commande est maintenant ${label}.`,
  };
}

export const updateOrderStatus = onCall(trustedCallableOptions, async (request) => {
  const actor = await assertAdminOrVendeur(request);
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
  const sellerIds = Array.isArray(currentData.sellerIds) ?
    currentData.sellerIds.filter((value): value is string => typeof value === "string" && value.length > 0) :
    [];
  if (actor.role === USER_ROLES.VENDEUR && !sellerIds.includes(actor.uid)) {
    throw new HttpsError("permission-denied", "You can update only orders containing your products.");
  }
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

  const userDoc = await db.collection(COLLECTIONS.USERS).doc(uid).get();
  const preferredLanguage = asString(
    userDoc.get("language"),
    asString(userDoc.get("preferredLanguage"), "fr"),
  ).trim().toLowerCase();
  const notificationCopy = orderStatusNotificationCopy(nextStatus, preferredLanguage);
  const inboxRef = db.collection(COLLECTIONS.USERS)
    .doc(uid)
    .collection(USER_SUBCOLLECTIONS.INBOX)
    .doc();

  await orderRef.set(updatePayload, {merge: true});
  await inboxRef.set({
    id: inboxRef.id,
    type: "order_status_changed",
    title: notificationCopy.title,
    body: notificationCopy.body,
    route: "order_details",
    entityRef: orderId,
    orderId,
    readAt: null,
    createdAt: admin.firestore.FieldValue.serverTimestamp(),
    updatedAt: admin.firestore.FieldValue.serverTimestamp(),
    schemaVersion: SCHEMA_VERSION,
  });
  await sendPushToUser(uid, notificationCopy.title, notificationCopy.body, {
    route: "order_details",
    orderId,
    status: nextStatus,
  });

  logger.info("Order status updated", {
    orderId,
    uid,
    actorUid: actor.uid,
    actorRole: actor.role,
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
