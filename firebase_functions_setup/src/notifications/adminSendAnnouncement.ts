import {logger} from "firebase-functions";
import {HttpsError, onCall} from "firebase-functions/v2/https";
import {
  COLLECTIONS,
  SCHEMA_VERSION,
  USER_ROLES,
} from "../shared/constants";
import {assertAdmin} from "../shared/auth";
import {admin, db} from "../shared/firestore";
import {heavyCallableOptions} from "../shared/callableOptions";
import {asRecord, asString} from "../shared/domain";
import {sendPushToUsers} from "./push";

type Audience = "all" | "clients" | "admins";
const USER_PAGE_SIZE = 500;

function normalizeAudience(value: unknown): Audience {
  const audience = asString(value, "all").trim().toLowerCase();
  return audience === "admins" || audience === "clients" ? audience : "all";
}

function validateAnnouncementCopy(title: string, message: string): void {
  if (title.trim().length < 3 || message.trim().length < 5) {
    throw new HttpsError(
      "invalid-argument",
      "Announcement title and message are required.",
    );
  }
}

export const adminSendAnnouncement = onCall({...heavyCallableOptions, maxInstances: 1, timeoutSeconds: 540}, async (request) => {
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

  let recipientCount = 0;
  let scannedCount = 0;
  let pushFailureCount = 0;
  let lastUserDoc: FirebaseFirestore.QueryDocumentSnapshot | null = null;
  let reachedEnd = false;

  while (!reachedEnd) {
    let usersQuery = db.collection(COLLECTIONS.USERS)
      .orderBy(admin.firestore.FieldPath.documentId())
      .limit(USER_PAGE_SIZE);
    if (lastUserDoc) {
      usersQuery = usersQuery.startAfter(lastUserDoc);
    }

    const usersSnapshot = await usersQuery.get();
    reachedEnd = usersSnapshot.size < USER_PAGE_SIZE;
    lastUserDoc = usersSnapshot.docs[usersSnapshot.docs.length - 1] || null;

    const userIds = usersSnapshot.docs
      .filter((doc) => {
        const role = asString(doc.get("role"), USER_ROLES.CLIENT).trim().toLowerCase();
        if (audience === "admins") return role === USER_ROLES.ADMIN;
        if (audience === "clients") return role !== USER_ROLES.ADMIN;
        return true;
      })
      .map((doc) => doc.id);
    recipientCount += userIds.length;
    scannedCount += usersSnapshot.size;

    if (userIds.length > 0) {
      try {
        const pushResult = await sendPushToUsers(userIds, title, message, {
          route: "notifications",
          notificationId: notificationRef.id,
          type: "announcement",
        }, "announcements");
        pushFailureCount += pushResult.failureCount;
      } catch (error) {
        pushFailureCount += userIds.length;
        logger.warn("Announcement push fan-out page failed", {
          createdBy: adminContext.uid,
          audience,
          userCount: userIds.length,
          error,
        });
      }
    }

    logger.info("Announcement fan-out progress", {
      createdBy: adminContext.uid,
      audience,
      scannedCount,
      recipientCount,
    });
  }

  logger.info("Announcement published", {
    createdBy: adminContext.uid,
    audience,
    recipientCount,
    pushFailureCount,
  });

  return {
    notification: {
      ...notificationPayload,
      createdAtTs: nowMs,
      updatedAt: nowMs,
    },
    recipientCount,
    pushFailureCount,
  };
});
