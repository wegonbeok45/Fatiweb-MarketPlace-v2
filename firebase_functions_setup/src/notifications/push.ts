import {logger} from "firebase-functions";
import {COLLECTIONS} from "../shared/constants";
import {admin, db} from "../shared/firestore";
import {asBoolean, asRecord, asString, chunk} from "../shared/domain";

export type PushPreferenceKey = "orderUpdates" | "promotions" | "announcements";

const ANDROID_NOTIFICATION_CHANNEL_ID = "order_updates";
const MAX_FCM_TOKENS_PER_MULTICAST = 500;

export async function sendPushToUser(
  uid: string,
  title: string,
  body: string,
  data: Record<string, string> = {},
  preferenceKey: PushPreferenceKey = "orderUpdates",
) {
  const userRef = db.collection(COLLECTIONS.USERS).doc(uid);
  const userSnapshot = await userRef.get();
  const preferences = asRecord(userSnapshot.get("notificationPreferences"));
  if (preferences) {
    const pushEnabled = asBoolean(preferences.pushEnabled, true);
    const categoryEnabled = asBoolean(preferences[preferenceKey], true);
    if (!pushEnabled || !categoryEnabled) {
      logger.info("Push notification skipped by user preference", {uid, preferenceKey});
      return;
    }
  }

  const tokensSnapshot = await db.collection(COLLECTIONS.USERS)
    .doc(uid)
    .collection("fcmTokens")
    .get();
  const tokenEntries = tokensSnapshot.docs
    .map((doc) => ({
      token: asString(doc.get("token")).trim(),
      tokenDoc: doc,
    }))
    .filter(({token}) => token.length > 0);

  if (tokenEntries.length === 0) {
    logger.info("Push notification skipped because user has no FCM tokens", {uid});
    return;
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
      uid,
      successCount,
      failureCount,
    });
  }
}
