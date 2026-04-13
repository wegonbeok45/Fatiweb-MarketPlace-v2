import {logger} from "firebase-functions";
import {HttpsError, onCall} from "firebase-functions/v2/https";
import type {DocumentData, Query} from "firebase-admin/firestore";
import {
  COLLECTIONS,
  SCHEMA_VERSION,
  USER_ROLES,
  USER_SUBCOLLECTIONS,
} from "../shared/constants";
import {assertAdmin} from "../shared/auth";
import {admin, db} from "../shared/firestore";
import {asRecord, asString, chunk} from "../shared/domain";

type Audience = "all" | "clients" | "admins";

function normalizeAudience(value: unknown): Audience {
  const audience = asString(value, "all").trim().toLowerCase();
  return audience === "admins" || audience === "clients" ? audience : "all";
}

function looksLikeKeyboardMashing(text: string): boolean {
  const normalized = text.trim().toLowerCase();
  const letters = normalized.replace(/[^a-z]/g, "").length;
  if (letters < 4) return false;
  if (/[bcdfghjklmnpqrstvwxz]{5,}/.test(normalized)) return true;
  const vowels = normalized.replace(/[^aeiouy]/g, "").length;
  return vowels === 0;
}

function validateAnnouncementCopy(title: string, message: string): void {
  const normalizedTitle = title.trim().toLowerCase();
  const normalizedMessage = message.trim().toLowerCase();
  const blockedSamples = ["juuui", "rrrtzfh", "bienvznuz", "hello"];

  if (
    normalizedTitle.length < 3 ||
    normalizedMessage.length < 8 ||
    blockedSamples.some((sample) =>
      normalizedTitle.includes(sample) || normalizedMessage.includes(sample),
    ) ||
    looksLikeKeyboardMashing(normalizedTitle) ||
    looksLikeKeyboardMashing(normalizedMessage)
  ) {
    throw new HttpsError(
      "invalid-argument",
      "Announcement content looks malformed. Please use a clear title and message.",
    );
  }
}

export const adminSendAnnouncement = onCall(async (request) => {
  const adminContext = await assertAdmin(request);
  const payload = asRecord(request.data) || {};
  const title = asString(payload.title).trim();
  const message = asString(payload.message).trim();
  const audience = normalizeAudience(payload.audience);

  if (title.length < 3 || message.length < 5) {
    throw new HttpsError("invalid-argument", "Announcement title and message are required.");
  }
  validateAnnouncementCopy(title, message);

  const nowMs = Date.now();
  const notificationRef = db.collection(COLLECTIONS.IN_APP_NOTIFICATIONS).doc();
  const notificationPayload = {
    id: notificationRef.id,
    type: "announcement",
    title,
    message,
    audience,
    createdBy: adminContext.uid,
    createdAt: nowMs,
    createdAtTs: admin.firestore.FieldValue.serverTimestamp(),
    updatedAt: admin.firestore.FieldValue.serverTimestamp(),
    schemaVersion: SCHEMA_VERSION,
  };
  await notificationRef.set(notificationPayload);

  let usersQuery: Query<DocumentData> = db.collection(COLLECTIONS.USERS);
  if (audience === "clients") {
    usersQuery = usersQuery.where("role", "==", USER_ROLES.CLIENT);
  } else if (audience === "admins") {
    usersQuery = usersQuery.where("role", "==", USER_ROLES.ADMIN);
  }

  const usersSnapshot = await usersQuery.get();
  const userIds = usersSnapshot.docs.map((doc) => doc.id);
  const inboxEntries = userIds.map((uid) => ({
    ref: db.collection(COLLECTIONS.USERS)
      .doc(uid)
      .collection(USER_SUBCOLLECTIONS.INBOX)
      .doc(notificationRef.id),
  }));

  for (const batchEntries of chunk(inboxEntries, 400)) {
    const batch = db.batch();
    for (const entry of batchEntries) {
      batch.set(entry.ref, {
        id: notificationRef.id,
        type: "announcement",
        title,
        body: message,
        route: "notifications",
        entityRef: notificationRef.id,
        audience,
        readAt: null,
        createdAt: admin.firestore.FieldValue.serverTimestamp(),
        updatedAt: admin.firestore.FieldValue.serverTimestamp(),
        schemaVersion: SCHEMA_VERSION,
      }, {merge: true});
    }
    await batch.commit();
  }

  logger.info("Announcement published", {
    createdBy: adminContext.uid,
    audience,
    recipientCount: userIds.length,
  });

  return {
    notification: {
      ...notificationPayload,
      createdAtTs: nowMs,
      updatedAt: nowMs,
    },
    recipientCount: userIds.length,
  };
});
