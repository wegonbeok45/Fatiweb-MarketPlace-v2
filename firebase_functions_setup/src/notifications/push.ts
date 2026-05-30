import {logger} from "firebase-functions";
import {COLLECTIONS} from "../shared/constants";
import {admin, db} from "../shared/firestore";
import {asBoolean, asRecord, asString, chunk} from "../shared/domain";

export type PushPreferenceKey = "orderUpdates" | "promotions" | "announcements";

const ANDROID_NOTIFICATION_CHANNEL_ID = "order_updates";
const MAX_FCM_TOKENS_PER_MULTICAST = 500;
const USER_DOC_BATCH_SIZE = 10;

export type PushSendResult = {
  successCount: number;
  failureCount: number;
  skippedCount: number;
  tokenCount: number;
};

export async function sendPushToUser(
  uid: string,
  title: string,
  body: string,
  data: Record<string, string> = {},
  preferenceKey: PushPreferenceKey = "orderUpdates",
) {
  await sendPushToUsers([uid], title, body, data, preferenceKey);
}

export async function sendPushToUsers(
  uids: string[],
  title: string,
  body: string,
  data: Record<string, string> = {},
  preferenceKey: PushPreferenceKey = "orderUpdates",
): Promise<PushSendResult> {
  const uniqueUids = [...new Set(uids.map((uid) => uid.trim()).filter((uid) => uid.length > 0))];
  if (uniqueUids.length === 0) {
    return {successCount: 0, failureCount: 0, skippedCount: 0, tokenCount: 0};
  }

  let skippedCount = 0;
  const enabledUsers: Array<{uid: string; ref: FirebaseFirestore.DocumentReference}> = [];
  for (const uidChunk of chunk(uniqueUids, USER_DOC_BATCH_SIZE)) {
    const userRefs = uidChunk.map((uid) => db.collection(COLLECTIONS.USERS).doc(uid));
    const userSnapshots = await db.getAll(...userRefs);
    userSnapshots.forEach((userSnapshot, index) => {
      const uid = uidChunk[index];
      const preferences = asRecord(userSnapshot.get("notificationPreferences"));
      if (preferences) {
        const pushEnabled = asBoolean(preferences.pushEnabled, true);
        const categoryEnabled = asBoolean(preferences[preferenceKey], true);
        if (!pushEnabled || !categoryEnabled) {
          skippedCount += 1;
          logger.info("Push notification skipped by user preference", {uid, preferenceKey});
          return;
        }
      }
      enabledUsers.push({uid, ref: userSnapshot.ref});
    });
  }

  const tokenSnapshots = await Promise.all(
    enabledUsers.map((user) => user.ref.collection("fcmTokens").get()),
  );
  const tokenEntries = tokenSnapshots.flatMap((tokensSnapshot, index) => {
    const uid = enabledUsers[index].uid;
    const entries = tokensSnapshot.docs
      .map((doc) => ({
        uid,
        token: asString(doc.get("token")).trim(),
        tokenDoc: doc,
      }))
      .filter(({token}) => token.length > 0);
    if (entries.length === 0) {
      logger.info("Push notification skipped because user has no FCM tokens", {uid});
    }
    return entries;
  });

  if (tokenEntries.length === 0) {
    return {successCount: 0, failureCount: 0, skippedCount, tokenCount: 0};
  }

  let successCount = 0;
  let failureCount = 0;
  const invalidTokenDocs: Array<(typeof tokenEntries)[number]["tokenDoc"]> = [];
  const isConversationMessage = data.type === "conversation_message";
  const messageData = {
    ...data,
    title,
    body,
  };

  for (const tokenChunk of chunk(tokenEntries, MAX_FCM_TOKENS_PER_MULTICAST)) {
    const multicastMessage: admin.messaging.MulticastMessage = {
      tokens: tokenChunk.map(({token}) => token),
      data: messageData,
      android: {
        priority: "high",
      },
    };
    if (!isConversationMessage) {
      multicastMessage.notification = {title, body};
      multicastMessage.android = {
        priority: "high",
        notification: {
          channelId: ANDROID_NOTIFICATION_CHANNEL_ID,
        },
      };
    }

    const response = await admin.messaging().sendEachForMulticast(multicastMessage);

    successCount += response.successCount;
    failureCount += response.failureCount;
    invalidTokenDocs.push(
      ...response.responses
        .map((result, index) => ({result, tokenDoc: tokenChunk[index].tokenDoc}))
        .filter(({result}) => {
          const code = result.error?.code || "";
          return code.includes("registration-token-not-registered") ||
            code.includes("invalid-registration-token");
        })
        .map(({tokenDoc}) => tokenDoc),
    );
  }

  await Promise.all(
    invalidTokenDocs.map((tokenDoc) => tokenDoc.ref.delete()),
  );

  if (failureCount > 0) {
    logger.warn("Some FCM notifications failed", {
      uidCount: uniqueUids.length,
      successCount,
      failureCount,
    });
  }

  return {successCount, failureCount, skippedCount, tokenCount: tokenEntries.length};
}
