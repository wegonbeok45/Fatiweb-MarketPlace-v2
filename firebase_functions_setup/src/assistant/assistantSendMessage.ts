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
import {hotCallableOptions} from "../shared/callableOptions";
import {chatCompletion, GroqMessage} from "../shared/groqClient";

const groqApiKey = defineSecret("GROQ_API_KEY");

// Primary model: Llama 3.3 70B (fast, high quality). Fallback: Llama 3.1 8B (instant).
const PRIMARY_MODEL = "llama-3.3-70b-versatile";
const FALLBACK_MODELS = ["llama-3.1-8b-instant"];
const ASSISTANT_CONTEXT_CACHE_TTL_MS = 5 * 60 * 1000;

type ChatTurn = {
  role: "user" | "assistant";
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
      // Map legacy "BOT" role to OpenAI-style "assistant"
      const rawRole = asString(entry.role).trim().toUpperCase();
      const role: "user" | "assistant" =
        rawRole === "BOT" || rawRole === "ASSISTANT" || rawRole === "MODEL" ?
          "assistant" :
          "user";
      return {
        role,
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
  const nowMs = Date.now();
  const cacheRef = uid ? db.collection(COLLECTIONS.ASSISTANT_CONTEXT_CACHE).doc(uid) : null;
  if (cacheRef) {
    const cacheDoc = await cacheRef.get();
    const updatedAtMs = asNumber(cacheDoc.get("updatedAtMs"), 0);
    const products = stringList(cacheDoc.get("products"));
    const orders = stringList(cacheDoc.get("orders"));
    if (products.length > 0 && nowMs - updatedAtMs < ASSISTANT_CONTEXT_CACHE_TTL_MS) {
      return {products, orders};
    }
  }

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
  const context = {products, orders};
  if (cacheRef) {
    await cacheRef.set({
      uid,
      ...context,
      updatedAtMs: nowMs,
      updatedAt: admin.firestore.FieldValue.serverTimestamp(),
    }, {merge: true});
  }
  return context;
}

function stringList(value: unknown): string[] {
  if (!Array.isArray(value)) {
    return [];
  }
  return value
    .map((item) => asString(item).trim())
    .filter((item) => item.length > 0);
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

function buildGroqMessages(systemPrompt: string, history: ChatTurn[]): GroqMessage[] {
  const messages: GroqMessage[] = [
    {role: "system", content: systemPrompt},
  ];
  for (const turn of history) {
    messages.push({role: turn.role, content: turn.text});
  }
  return messages;
}

export const assistantSendMessage = onCall(
  {...hotCallableOptions, secrets: [groqApiKey], timeoutSeconds: 60},
  async (request) => {
    const uid = request.auth?.uid || null;
    const payload = asRecord(request.data) || {};
    const history = parseHistory(payload.history);
    if (history.length === 0) {
      throw new HttpsError("invalid-argument", "A non-empty message history is required.");
    }

    await enforceRateLimit(uid, request.rawRequest.ip);
    const context = await fetchAssistantContext(uid);

    const reply = await chatCompletion({
      apiKey: groqApiKey.value(),
      model: PRIMARY_MODEL,
      fallbackModels: FALLBACK_MODELS,
      messages: buildGroqMessages(buildSystemPrompt(context), history),
      temperature: 0.65,
      maxTokens: 512,
      topP: 0.95,
      logTag: "assistant",
    });

    logger.info("Assistant reply generated", {
      uid,
      turns: history.length,
    });

    return {reply};
  },
);
