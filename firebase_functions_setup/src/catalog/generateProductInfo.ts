import {logger} from "firebase-functions";
import {defineSecret} from "firebase-functions/params";
import {HttpsError, onCall} from "firebase-functions/v2/https";
import {assertAdminOrVendeur} from "../shared/auth";
import {asRecord, asString} from "../shared/domain";
import {trustedCallableOptions} from "../shared/callableOptions";
import {admin, db} from "../shared/firestore";
import {chatCompletion} from "../shared/groqClient";

const groqApiKey = defineSecret("GROQ_API_KEY");

// Vision-capable Groq model. Llama 4 Scout supports multimodal input via the
// OpenAI-style image_url content part.
const VISION_MODEL = "meta-llama/llama-4-scout-17b-16e-instruct";
const MAX_INLINE_IMAGE_CHARS = 2_000_000;
const RATE_LIMIT_WINDOW_MS = 30_000;

type ProductInfoDraft = {
  title: string;
  subtitle: string;
  categoryKey: string;
  origin: string;
  tags: string[];
  description: string;
  bullets: string[];
  bioFriendly: boolean;
  suggestedPrice: number;
  suggestedStock: number;
};

const categories = [
  {key: "electronics", label: "Electronics"},
  {key: "fashion", label: "Fashion"},
  {key: "home-and-furniture", label: "Home & Furniture"},
  {key: "beauty-and-health", label: "Beauty & Health"},
  {key: "sports-and-outdoors", label: "Sports & Outdoors"},
  {key: "automotive", label: "Automotive"},
  {key: "real-estate", label: "Real Estate"},
  {key: "jobs-services", label: "Jobs & Services"},
  {key: "baby-and-toys", label: "Baby & Toys"},
  {key: "books-and-media", label: "Books & Media"},
  {key: "food-and-grocery", label: "Food & Grocery"},
  {key: "pets", label: "Pets"},
  {key: "business-and-industrial", label: "Business & Industrial"},
  {key: "digital-products", label: "Digital Products"},
  {key: "collectibles-and-hobbies", label: "Collectibles & Hobbies"},
];

function buildPrompt(): string {
  const categoryList = categories
    .map((category) => `${category.key}: ${category.label}`)
    .join(", ");

  return `
You are helping a FatiWeb marketplace admin create a product listing from a product image.
Return only valid JSON. Do not wrap it in markdown.
Estimate suggestedPrice in Tunisian dinars (DT) from the visible product type, material, and category.
Return suggestedPrice as a plain positive number without a currency symbol.
Use French ecommerce copy.
Choose categoryKey from exactly one of: ${categoryList}.
Use only what is visible in the image. If a detail is uncertain, keep the language general.
If origin is not visible, use "Tunisie".
Set suggestedStock to 1 unless the image clearly shows a pack or multiple units.

JSON schema:
{
  "title": "3 to 8 words",
  "subtitle": "short sales subtitle",
  "categoryKey": "${categories.map((category) => category.key).join("|")}",
  "origin": "city or Tunisie",
  "tags": ["3 to 6 short tags"],
  "description": "70 to 120 words",
  "bullets": ["3 to 5 concise highlights"],
  "bioFriendly": true,
  "suggestedPrice": 25.0,
  "suggestedStock": 1
}
  `.trim();
}

function extractJsonObject(text: string): Record<string, unknown> {
  const trimmed = text.trim()
    .replace(/^```(?:json)?/i, "")
    .replace(/```$/i, "")
    .trim();
  const start = trimmed.indexOf("{");
  const end = trimmed.lastIndexOf("}");
  if (start < 0 || end <= start) {
    throw new HttpsError("internal", "Groq did not return a product JSON object.");
  }
  return JSON.parse(trimmed.slice(start, end + 1)) as Record<string, unknown>;
}

function stringArray(value: unknown): string[] {
  if (!Array.isArray(value)) {
    return [];
  }
  return value
    .map((item) => asString(item).trim())
    .filter((item) => item.length > 0)
    .slice(0, 6);
}

function normalizeCategory(value: unknown): string {
  const key = asString(value).trim().toLowerCase();
  return categories.some((category) => category.key === key) ? key : "electronics";
}

function normalizeDraft(raw: Record<string, unknown>): ProductInfoDraft {
  const title = asString(raw.title).trim();
  const subtitle = asString(raw.subtitle).trim();
  const description = asString(raw.description).trim();
  const bullets = stringArray(raw.bullets);

  if (title.length < 3 || subtitle.length < 3 || description.length < 12 || bullets.length === 0) {
    throw new HttpsError("internal", "Groq returned incomplete product information.");
  }

  const stockValue = Number(raw.suggestedStock);
  const priceValue = Number(raw.suggestedPrice);
  return {
    title: title.slice(0, 90),
    subtitle: subtitle.slice(0, 140),
    categoryKey: normalizeCategory(raw.categoryKey),
    origin: asString(raw.origin, "Tunisie").trim().slice(0, 60) || "Tunisie",
    tags: stringArray(raw.tags).slice(0, 6),
    description: description.slice(0, 900),
    bullets: bullets.slice(0, 5),
    bioFriendly: raw.bioFriendly === true,
    suggestedPrice: Number.isFinite(priceValue) && priceValue > 0 ? Number(priceValue.toFixed(2)) : 0,
    suggestedStock: Number.isFinite(stockValue) ? Math.max(1, Math.floor(stockValue)) : 1,
  };
}

async function enforceGenerateProductInfoRateLimit(uid: string): Promise<void> {
  const rateLimitRef = db.collection("generateProductInfoRateLimits").doc(uid);
  const nowMs = Date.now();

  await db.runTransaction(async (transaction) => {
    const rateDoc = await transaction.get(rateLimitRef);
    const lastCallAt = rateDoc.get("lastCallAt");
    const lastCallAtMs = lastCallAt instanceof admin.firestore.Timestamp ? lastCallAt.toMillis() : 0;
    if (nowMs - lastCallAtMs < RATE_LIMIT_WINDOW_MS) {
      throw new HttpsError("resource-exhausted", "Please wait before generating product info again.");
    }

    transaction.set(rateLimitRef, {
      uid,
      lastCallAt: admin.firestore.Timestamp.fromMillis(nowMs),
      updatedAt: admin.firestore.FieldValue.serverTimestamp(),
    }, {merge: true});
  });
}

export const generateProductInfo = onCall(
  {...trustedCallableOptions, secrets: [groqApiKey], timeoutSeconds: 30, maxInstances: 3},
  async (request) => {
    const actor = await assertAdminOrVendeur(request);
    await enforceGenerateProductInfoRateLimit(actor.uid);

    const payload = asRecord(request.data) || {};
    const imageBase64 = asString(payload.imageBase64).trim();
    const imageMimeType = asString(payload.imageMimeType, "image/jpeg").trim() || "image/jpeg";

    if (!imageBase64) {
      throw new HttpsError("invalid-argument", "A product image is required.");
    }
    if (imageBase64.length > MAX_INLINE_IMAGE_CHARS) {
      throw new HttpsError("invalid-argument", "The product image is too large.");
    }

    const reply = await chatCompletion({
      apiKey: groqApiKey.value(),
      model: VISION_MODEL,
      messages: [
        {
          role: "user",
          content: [
            {type: "text", text: buildPrompt()},
            {
              type: "image_url",
              image_url: {
                url: `data:${imageMimeType};base64,${imageBase64}`,
                detail: "low",
              },
            },
          ],
        },
      ],
      temperature: 0.35,
      topP: 0.9,
      maxTokens: 900,
      responseFormat: {type: "json_object"},
      logTag: "generateProductInfo",
    });

    const draft = normalizeDraft(extractJsonObject(reply));
    logger.info("Product info generated", {
      actorUid: actor.uid,
      actorRole: actor.role,
      category: draft.categoryKey,
    });

    return {draft};
  },
);
