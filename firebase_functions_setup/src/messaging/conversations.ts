import {createHash} from "crypto";
import {logger} from "firebase-functions";
import {HttpsError, onCall} from "firebase-functions/v2/https";
import {COLLECTIONS, USER_SUBCOLLECTIONS} from "../shared/constants";
import {assertAuthenticated} from "../shared/auth";
import {admin, db} from "../shared/firestore";
import {asNumber, asRecord, asStringArray, asTrimmedString, generateSearchKeywords} from "../shared/domain";
import {sendPushToUser} from "../notifications/push";
import {hotCallableOptions, trustedCallableOptions} from "../shared/callableOptions";

const MAX_MESSAGE_LENGTH = 2000;
const RATE_LIMIT_WINDOW_MS = 60_000;
const RATE_LIMIT_MAX_MESSAGES = 30;
const SAFE_DOC_ID = /^[A-Za-z0-9_-]{8,160}$/;

type MessageType = "text" | "image";

function assertSafeDocId(value: string, fieldName: string): void {
  if (!SAFE_DOC_ID.test(value)) {
    throw new HttpsError("invalid-argument", `${fieldName} is invalid.`);
  }
}

function conversationIdFor(buyerId: string, sellerId: string, productId: string): string {
  return createHash("sha256")
    .update(`${buyerId}|${sellerId}|${productId}`)
    .digest("hex")
    .slice(0, 40);
}

function displayName(data: FirebaseFirestore.DocumentData | undefined, fallback: string): string {
  const name = asTrimmedString(data?.name);
  const display = asTrimmedString(data?.displayName);
  const email = asTrimmedString(data?.email);
  return name || display || email.split("@")[0] || fallback;
}

function userAvatar(data: FirebaseFirestore.DocumentData | undefined): string {
  return asTrimmedString(data?.avatarUrl) || asTrimmedString(data?.avatar) || asTrimmedString(data?.photoUrl);
}

function conversationPreviewFor(type: MessageType, text: string): string {
  if (type === "image") return "Photo";
  return text;
}

async function assertParticipant(conversationId: string, uid: string) {
  const ref = db.collection(COLLECTIONS.CONVERSATIONS).doc(conversationId);
  const snapshot = await ref.get();
  if (!snapshot.exists) {
    throw new HttpsError("not-found", "Conversation not found.");
  }
  const participantIds = asStringArray(snapshot.get("participantIds"));
  if (!participantIds.includes(uid)) {
    throw new HttpsError("permission-denied", "You are not a participant in this conversation.");
  }
  return {ref, snapshot, participantIds};
}

async function assertNotBlocked(senderId: string, receiverId: string) {
  const [senderBlockedReceiver, receiverBlockedSender] = await Promise.all([
    db.collection(COLLECTIONS.USERS).doc(senderId)
      .collection(USER_SUBCOLLECTIONS.BLOCKED_USERS).doc(receiverId).get(),
    db.collection(COLLECTIONS.USERS).doc(receiverId)
      .collection(USER_SUBCOLLECTIONS.BLOCKED_USERS).doc(senderId).get(),
  ]);
  if (senderBlockedReceiver.exists || receiverBlockedSender.exists) {
    throw new HttpsError("failed-precondition", "Messaging is blocked for this conversation.");
  }
}

async function enforceRateLimit(uid: string) {
  const ref = db.collection(COLLECTIONS.CONVERSATION_RATE_LIMITS).doc(uid);
  const now = Date.now();
  await db.runTransaction(async (transaction) => {
    const snapshot = await transaction.get(ref);
    const windowStartedAt = asNumber(snapshot.get("windowStartedAt"), 0);
    const count = asNumber(snapshot.get("count"), 0);
    if (windowStartedAt > 0 && now - windowStartedAt < RATE_LIMIT_WINDOW_MS) {
      if (count >= RATE_LIMIT_MAX_MESSAGES) {
        throw new HttpsError("resource-exhausted", "Please slow down before sending more messages.");
      }
      transaction.set(ref, {count: count + 1, updatedAt: now}, {merge: true});
      return;
    }
    transaction.set(ref, {count: 1, windowStartedAt: now, updatedAt: now}, {merge: true});
  });
}

export const openOrCreateConversation = onCall(trustedCallableOptions, async (request) => {
  const auth = assertAuthenticated(request);
  const data = asRecord(request.data) ?? {};
  const productId = asTrimmedString(data.productId);
  if (!productId) {
    throw new HttpsError("invalid-argument", "productId is required.");
  }

  const productSnapshot = await db.collection(COLLECTIONS.PRODUCTS).doc(productId).get();
  if (!productSnapshot.exists) {
    throw new HttpsError("not-found", "Product not found.");
  }
  const product = productSnapshot.data() ?? {};
  const isActive = product.isActive !== false;
  const status = asTrimmedString(product.status, "published").toLowerCase();
  if (!isActive || status !== "published") {
    throw new HttpsError("failed-precondition", "Product is not available for messaging.");
  }
  const sellerId = asTrimmedString(product.sellerId);
  if (!sellerId) {
    throw new HttpsError("failed-precondition", "This product does not have a seller.");
  }
  if (sellerId === auth.uid) {
    throw new HttpsError("failed-precondition", "You cannot message yourself about your own product.");
  }
  await assertNotBlocked(auth.uid, sellerId);

  const conversationId = conversationIdFor(auth.uid, sellerId, productId);
  const conversationRef = db.collection(COLLECTIONS.CONVERSATIONS).doc(conversationId);
  const buyerRef = db.collection(COLLECTIONS.USERS).doc(auth.uid);
  const sellerRef = db.collection(COLLECTIONS.USERS).doc(sellerId);
  const [buyerSnapshot, sellerSnapshot] = await Promise.all([buyerRef.get(), sellerRef.get()]);
  const buyer = buyerSnapshot.data();
  const seller = sellerSnapshot.data();
  const title = asTrimmedString(product.title, "Product");
  const thumbnailUrl = asTrimmedString(product.imageUrl) || asStringArray(product.imageUrls)[0] || "";
  const price = asNumber(product.price, 0);
  const priceMinor = asNumber(product.priceMinor, Math.round(price * 1000));
  const sellerName = asTrimmedString(product.sellerName) || displayName(seller, "Seller");
  const buyerName = displayName(buyer, auth.email ?? "Buyer");
  const now = admin.firestore.FieldValue.serverTimestamp();

  await db.runTransaction(async (transaction) => {
    const existing = await transaction.get(conversationRef);
    const base = {
      id: conversationId,
      dedupeKey: `${auth.uid}_${sellerId}_${productId}`,
      participantIds: [auth.uid, sellerId],
      participantRoles: {[auth.uid]: "buyer", [sellerId]: "seller"},
      buyerId: auth.uid,
      sellerId,
      productId,
      productSnapshot: {title, thumbnailUrl, price, priceMinor},
      sellerSnapshot: {
        displayName: sellerName,
        avatarUrl: asTrimmedString(product.sellerAvatarUrl) || userAvatar(seller),
      },
      buyerSnapshot: {
        displayName: buyerName,
        avatarUrl: userAvatar(buyer),
      },
      searchKeywords: generateSearchKeywords(title, sellerName, buyerName),
      status: "active",
      hiddenFor: admin.firestore.FieldValue.arrayRemove(auth.uid),
      blockedBy: [],
      updatedAt: now,
    };
    if (existing.exists) {
      transaction.set(conversationRef, base, {merge: true});
    } else {
      transaction.set(conversationRef, {
        ...base,
        lastMessageText: "",
        lastMessageType: "system",
        lastMessageAt: now,
        lastMessageSenderId: "",
        unreadCounts: {[auth.uid]: 0, [sellerId]: 0},
        lastReadAt: {[auth.uid]: now, [sellerId]: null},
        createdAt: now,
      });
    }
  });

  return {conversationId};
});

export const sendConversationMessage = onCall(hotCallableOptions, async (request) => {
  const auth = assertAuthenticated(request);
  const data = asRecord(request.data) ?? {};
  const conversationId = asTrimmedString(data.conversationId);
  const clientMessageId = asTrimmedString(data.clientMessageId);
  const type = asTrimmedString(data.type, "text") as MessageType;
  const text = asTrimmedString(data.text);
  const imageUrl = asTrimmedString(data.imageUrl);
  const thumbnailUrl = asTrimmedString(data.thumbnailUrl);
  const storagePath = asTrimmedString(data.storagePath);

  if (!conversationId || !clientMessageId) {
    throw new HttpsError("invalid-argument", "conversationId and clientMessageId are required.");
  }
  assertSafeDocId(conversationId, "conversationId");
  assertSafeDocId(clientMessageId, "clientMessageId");
  if (!["text", "image"].includes(type)) {
    throw new HttpsError("invalid-argument", "Unsupported message type.");
  }
  if (type === "text" && !text) {
    throw new HttpsError("invalid-argument", "Message cannot be empty.");
  }
  if (text.length > MAX_MESSAGE_LENGTH) {
    throw new HttpsError("invalid-argument", "Message is too long.");
  }
  if (type === "image" && (!imageUrl || !storagePath.includes(`chat_media/${conversationId}/${auth.uid}/`))) {
    throw new HttpsError("invalid-argument", "Invalid image message.");
  }

  const {ref, snapshot, participantIds} = await assertParticipant(conversationId, auth.uid);
  const receiverId = participantIds.find((id) => id !== auth.uid) ?? "";
  if (!receiverId) {
    throw new HttpsError("failed-precondition", "No receiver found.");
  }
  await assertNotBlocked(auth.uid, receiverId);
  await enforceRateLimit(auth.uid);

  const messageRef = ref.collection("messages").doc(clientMessageId);
  const now = admin.firestore.FieldValue.serverTimestamp();
  const preview = conversationPreviewFor(type, text).slice(0, 160);

  await db.runTransaction(async (transaction) => {
    const existingMessage = await transaction.get(messageRef);
    if (existingMessage.exists) return;
    transaction.set(messageRef, {
      id: clientMessageId,
      conversationId,
      senderId: auth.uid,
      receiverId,
      type,
      text,
      imageUrl,
      thumbnailUrl,
      storagePath,
      productSnapshot: snapshot.get("productSnapshot") ?? null,
      createdAt: now,
      updatedAt: now,
      readBy: {[auth.uid]: now},
      reactions: {},
      deliveryStatus: "sent",
      clientMessageId,
    });
    transaction.set(ref, {
      lastMessageText: preview,
      lastMessageType: type,
      lastMessageAt: now,
      lastMessageSenderId: auth.uid,
      [`unreadCounts.${receiverId}`]: admin.firestore.FieldValue.increment(1),
      [`unreadCounts.${auth.uid}`]: 0,
      updatedAt: now,
      status: "active",
    }, {merge: true});
  });

  const sellerId = asTrimmedString(snapshot.get("sellerId"));
  const buyerSnapshot = asRecord(snapshot.get("buyerSnapshot"));
  const sellerSnapshot = asRecord(snapshot.get("sellerSnapshot"));
  const senderSnapshot = auth.uid === sellerId ? sellerSnapshot : buyerSnapshot;
  const senderName = asTrimmedString(senderSnapshot?.displayName) || "FatiWeb";
  const senderAvatarUrl = asTrimmedString(senderSnapshot?.avatarUrl);
  await sendPushToUser(receiverId, senderName, preview, {
    type: "conversation_message",
    conversationId,
    productId: asTrimmedString(snapshot.get("productId")),
    title: senderName,
    body: preview,
    avatarUrl: senderAvatarUrl,
  });

  return {messageId: clientMessageId};
});

export const markConversationRead = onCall(trustedCallableOptions, async (request) => {
  const auth = assertAuthenticated(request);
  const data = asRecord(request.data) ?? {};
  const conversationId = asTrimmedString(data.conversationId);
  if (!conversationId) {
    throw new HttpsError("invalid-argument", "conversationId is required.");
  }
  assertSafeDocId(conversationId, "conversationId");
  const {ref} = await assertParticipant(conversationId, auth.uid);
  const now = admin.firestore.FieldValue.serverTimestamp();
  await ref.set({
    [`unreadCounts.${auth.uid}`]: 0,
    [`lastReadAt.${auth.uid}`]: now,
    updatedAt: now,
  }, {merge: true});
  return {ok: true};
});

export const toggleConversationMessageReaction = onCall(hotCallableOptions, async (request) => {
  const auth = assertAuthenticated(request);
  const data = asRecord(request.data) ?? {};
  const conversationId = asTrimmedString(data.conversationId);
  const messageId = asTrimmedString(data.messageId);
  const reaction = asTrimmedString(data.reaction, "heart");
  if (!conversationId || !messageId) {
    throw new HttpsError("invalid-argument", "conversationId and messageId are required.");
  }
  assertSafeDocId(conversationId, "conversationId");
  assertSafeDocId(messageId, "messageId");
  if (reaction !== "heart") {
    throw new HttpsError("invalid-argument", "Unsupported reaction.");
  }

  const {ref} = await assertParticipant(conversationId, auth.uid);
  const messageRef = ref.collection("messages").doc(messageId);
  const now = admin.firestore.FieldValue.serverTimestamp();

  await db.runTransaction(async (transaction) => {
    const message = await transaction.get(messageRef);
    if (!message.exists) {
      throw new HttpsError("not-found", "Message not found.");
    }
    const existing = asRecord(message.get("reactions"));
    const current = asTrimmedString(existing?.[auth.uid]);
    if (current === reaction) {
      transaction.set(messageRef, {
        [`reactions.${auth.uid}`]: admin.firestore.FieldValue.delete(),
        updatedAt: now,
      }, {merge: true});
    } else {
      transaction.set(messageRef, {
        [`reactions.${auth.uid}`]: reaction,
        updatedAt: now,
      }, {merge: true});
    }
  });

  return {ok: true};
});

export const hideConversation = onCall(trustedCallableOptions, async (request) => {
  const auth = assertAuthenticated(request);
  const data = asRecord(request.data) ?? {};
  const conversationId = asTrimmedString(data.conversationId);
  assertSafeDocId(conversationId, "conversationId");
  const {ref} = await assertParticipant(conversationId, auth.uid);
  await ref.set({
    hiddenFor: admin.firestore.FieldValue.arrayUnion(auth.uid),
    updatedAt: admin.firestore.FieldValue.serverTimestamp(),
  }, {merge: true});
  return {ok: true};
});

export const blockConversationUser = onCall(trustedCallableOptions, async (request) => {
  const auth = assertAuthenticated(request);
  const data = asRecord(request.data) ?? {};
  const blockedUserId = asTrimmedString(data.blockedUserId);
  if (!blockedUserId || blockedUserId === auth.uid) {
    throw new HttpsError("invalid-argument", "blockedUserId is invalid.");
  }
  await db.collection(COLLECTIONS.USERS).doc(auth.uid)
    .collection(USER_SUBCOLLECTIONS.BLOCKED_USERS).doc(blockedUserId)
    .set({
      blockedUserId,
      createdAt: admin.firestore.FieldValue.serverTimestamp(),
      reason: asTrimmedString(data.reason),
    }, {merge: true});
  return {ok: true};
});

export const reportConversationMessage = onCall(trustedCallableOptions, async (request) => {
  const auth = assertAuthenticated(request);
  const data = asRecord(request.data) ?? {};
  const conversationId = asTrimmedString(data.conversationId);
  const messageId = asTrimmedString(data.messageId);
  const reason = asTrimmedString(data.reason, "reported");
  assertSafeDocId(conversationId, "conversationId");
  assertSafeDocId(messageId, "messageId");
  const {ref} = await assertParticipant(conversationId, auth.uid);
  const messageSnapshot = await ref.collection("messages").doc(messageId).get();
  if (!messageSnapshot.exists) {
    throw new HttpsError("not-found", "Message not found.");
  }
  const reportRef = ref.collection("reports").doc();
  await reportRef.set({
    id: reportRef.id,
    reporterId: auth.uid,
    messageId,
    reason: reason.slice(0, 500),
    status: "open",
    createdAt: admin.firestore.FieldValue.serverTimestamp(),
  });
  logger.warn("Conversation reported", {conversationId, messageId, reporterId: auth.uid});
  return {reportId: reportRef.id};
});
