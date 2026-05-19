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
import {asRecord, asString, chunk} from "../shared/domain";
import {sendPushToUser} from "./push";

type Audience = "all" | "clients" | "admins";
const PUSH_FAN_OUT_BATCH_SIZE = 50;
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

export const adminSendAnnouncement = onCall(heavyCallableOptions, async (request) => {
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

  const userIds: string[] = [];
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

    userIds.push(...usersSnapshot.docs
      .filter((doc) => {
      const role = asString(doc.get("role"), USER_ROLES.CLIENT).trim().toLowerCase();
      if (audience === "admins") return role === USER_ROLES.ADMIN;
      if (audience === "clients") return role !== USER_ROLES.ADMIN;
      return true;
      })
      .map((doc) => doc.id));
  }
  const pushResults: Array<PromiseSettledResult<void>> = [];
  for (const userIdBatch of chunk(userIds, PUSH_FAN_OUT_BATCH_SIZE)) {
    const batchResults = await Promise.allSettled(
      userIdBatch.map((uid) =>
        sendPushToUser(uid, title, message, {
          route: "notifications",
          notificationId: notificationRef.id,
          type: "announcement",
        }, "announcements"),
      ),
    );
    pushResults.push(...batchResults);
  }
  const pushFailureCount = pushResults.filter((result) => result.status === "rejected").length;
  if (pushFailureCount > 0) {
    logger.warn("Announcement push fan-out had failed recipients", {
      createdBy: adminContext.uid,
      audience,
      pushFailureCount,
    });
  }

  logger.info("Announcement published", {
    createdBy: adminContext.uid,
    audience,
    recipientCount: userIds.length,
    pushFailureCount,
  });

  return {
    notification: {
      ...notificationPayload,
      createdAtTs: nowMs,
      updatedAt: nowMs,
    },
    recipientCount: userIds.length,
    pushFailureCount,
  };
});
