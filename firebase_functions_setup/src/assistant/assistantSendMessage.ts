import {createHash} from "node:crypto";
import {logger} from "firebase-functions";
import {defineSecret} from "firebase-functions/params";
import {HttpsError, onCall} from "firebase-functions/v2/https";
import {
  COLLECTIONS,
  DEFAULTS,
  USER_SUBCOLLECTIONS,
} from "../shared/constants";
import {admin, db} from "../shared/firestore";
import {asNumber, asRecord, asString} from "../shared/domain";

const geminiApiKey = defineSecret("GEMINI_API_KEY");

type ChatTurn = {
  role: "user" | "model";
  text: string;
};

function parseHistory(value: unknown): ChatTurn[] {
  if (!Array.isArray(value)) {
    return [];
  }

  return value
    .map((entry) => asRecord(entry))
    .filter((entry): entry is Record<string, unknown> => entry !== null)
    .map((entry) => {
      const role = asString(entry.role).trim().toUpperCase() === "BOT" ? "model" : "user";
      return {
        role: role as "user" | "model",
        text: asString(entry.text).trim(),
      };
    })
    .filter((entry) => entry.text.length > 0)
    .slice(-DEFAULTS.assistantMaxTurns);
}

async function enforceRateLimit(uid: string | null, ipAddress: string | undefined): Promise<void> {
  const keySource = uid || `ip:${createHash("sha256").update(ipAddress || "unknown").digest("hex")}`;
  const rateLimitRef = db.collection(COLLECTIONS.ASSISTANT_RATE_LIMITS).doc(keySource);
  const nowMs = Date.now();

  await db.runTransaction(async (transaction) => {
    const rateDoc = await transaction.get(rateLimitRef);
    const lastRequestAtMs = asNumber(rateDoc.get("lastRequestAtMs"), 0);
    if (nowMs - lastRequestAtMs < DEFAULTS.assistantCooldownMs) {
      throw new HttpsError("resource-exhausted", "Please wait a moment before sending another message.");
    }

    transaction.set(rateLimitRef, {
      key: keySource,
      uid,
      lastRequestAtMs: nowMs,
      updatedAt: admin.firestore.FieldValue.serverTimestamp(),
    }, {merge: true});
  });
}

async function fetchAssistantContext(uid: string | null): Promise<{
  products: string[];
  orders: string[];
}> {
  const productsSnapshot = await db.collection(COLLECTIONS.PRODUCTS)
    .where("isActive", "==", true)
    .orderBy("updatedAt", "desc")
    .limit(20)
    .get();
  const products = productsSnapshot.docs.map((doc) => {
    const data = doc.data();
    return `${asString(data.title)} (${asString(data.category)}) - ${asNumber(data.price)} DT - stock ${Math.floor(asNumber(data.stock))}`;
  });

  if (!uid) {
    return {products, orders: []};
  }

  let ordersSnapshot = await db.collection(COLLECTIONS.ORDERS)
    .where("uid", "==", uid)
    .orderBy("createdAt", "desc")
    .limit(5)
    .get();

  if (ordersSnapshot.empty) {
    ordersSnapshot = await db.collection(COLLECTIONS.USERS)
      .doc(uid)
      .collection(USER_SUBCOLLECTIONS.ORDERS)
      .limit(5)
      .get();
  }

  const orders = ordersSnapshot.docs.map((doc) => {
    const data = doc.data();
    return `${asString(data.id || doc.id)} - ${asString(data.status, "pending")} - ${asNumber(data.total)} DT`;
  });
  return {products, orders};
}

function buildSystemPrompt(context: {products: string[]; orders: string[]}): string {
  const productList = context.products.length > 0 ?
    context.products.join("\n") :
    "No active products are available right now.";
  const orderList = context.orders.length > 0 ?
    context.orders.join("\n") :
    "No recent orders are available for this customer.";

  return `
You are FatiBot, the marketplace assistant for FatiWeb Market.
Reply in the same language as the customer when possible.
Stay concise, helpful, and grounded in real catalog and order data.
Never invent products, prices, stock, or order states.

Active catalog:
${productList}

Recent orders:
${orderList}

Store facts:
- Standard shipping: 7 DT
- Express shipping: 12.5 DT
- Payment method: cash on delivery
- Support email: support@fatiweb.tn
  `.trim();
}

function buildGeminiContents(
  systemPrompt: string,
  history: ChatTurn[],
): Array<{role: "user" | "model"; parts: Array<{text: string}>}> {
  const contents: Array<{role: "user" | "model"; parts: Array<{text: string}>}> = [
    {
      role: "user",
      parts: [{text: `[SYSTEM CONTEXT - DO NOT EXPOSE]\n${systemPrompt}`}],
    },
    {
      role: "model",
      parts: [{text: "Understood. I will answer as FatiBot using only grounded marketplace information."}],
    },
  ];

  for (const entry of history) {
    contents.push({
      role: entry.role,
      parts: [{text: entry.text}],
    });
  }
  return contents;
}

export const assistantSendMessage = onCall(
  {secrets: [geminiApiKey], timeoutSeconds: 60},
  async (request) => {
    const uid = request.auth?.uid || null;
    const payload = asRecord(request.data) || {};
    const history = parseHistory(payload.history);
    if (history.length === 0) {
      throw new HttpsError("invalid-argument", "A non-empty message history is required.");
    }

    await enforceRateLimit(uid, request.rawRequest.ip);
    const context = await fetchAssistantContext(uid);
    const response = await fetch(
      `https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent?key=${geminiApiKey.value()}`,
      {
        method: "POST",
        headers: {"Content-Type": "application/json"},
        body: JSON.stringify({
          contents: buildGeminiContents(buildSystemPrompt(context), history),
          generationConfig: {
            temperature: 0.65,
            maxOutputTokens: 512,
            topP: 0.95,
          },
        }),
      },
    );

    const responseBody = await response.text();
    if (!response.ok) {
      logger.error("Assistant request failed", {
        status: response.status,
        body: responseBody.slice(0, 400),
      });
      throw new HttpsError("internal", "Assistant is temporarily unavailable.");
    }

    const parsed = JSON.parse(responseBody) as {
      candidates?: Array<{content?: {parts?: Array<{text?: string}>}}>;
    };
    const reply = parsed.candidates?.[0]?.content?.parts?.[0]?.text?.trim();
    if (!reply) {
      throw new HttpsError("internal", "Assistant returned an empty response.");
    }

    logger.info("Assistant reply generated", {
      uid,
      turns: history.length,
    });

    return {reply};
  },
);
