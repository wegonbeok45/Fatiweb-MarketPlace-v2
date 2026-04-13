import {logger} from "firebase-functions";
import {HttpsError, onCall} from "firebase-functions/v2/https";
import {COLLECTIONS, DEFAULTS, SCHEMA_VERSION} from "../shared/constants";
import {assertAdmin} from "../shared/auth";
import {admin, db} from "../shared/firestore";
import {
  asBoolean,
  asMillis,
  asNumber,
  asRecord,
  asString,
  asStringArray,
  generateSearchKeywords,
  normalizeCurrency,
  normalizeProductStatus,
  toMinorUnits,
} from "../shared/domain";

function normalizeProductId(rawId: string, title: string): string {
  const sanitized = rawId.trim().toLowerCase()
    .replace(/[^a-z0-9_]+/g, "_")
    .replace(/^_+|_+$/g, "");
  if (sanitized) {
    return sanitized;
  }
  const titleId = title.trim().toLowerCase()
    .replace(/[^a-z0-9_]+/g, "_")
    .replace(/^_+|_+$/g, "");
  if (titleId) {
    return titleId;
  }
  return `product_${Date.now()}`;
}

export const adminUpsertProduct = onCall(async (request) => {
  await assertAdmin(request);
  const payload = asRecord(request.data) || {};
  const product = asRecord(payload.product) || payload;

  const title = asString(product.title).trim();
  const subtitle = asString(product.subtitle).trim();
  const description = asString(product.description).trim();
  const category = asString(product.category, "craft").trim().toLowerCase();
  const origin = asString(product.origin, "tunisia").trim().toLowerCase();
  const price = asNumber(product.price);
  const stock = Math.max(0, Math.floor(asNumber(product.stock)));

  if (title.length < 3 || subtitle.length < 3 || description.length < 12 || price <= 0) {
    throw new HttpsError("invalid-argument", "The product payload is incomplete.");
  }

  const productId = normalizeProductId(asString(product.id), title);
  const productRef = db.collection(COLLECTIONS.PRODUCTS).doc(productId);
  const existing = await productRef.get();
  const imageUrl = asString(product.imageUrl).trim();
  const imageUrls = asStringArray(product.imageUrls);
  const categoryIds = asStringArray(product.categoryIds);
  const nowMs = Date.now();

  const normalizedProduct = {
    id: productId,
    title,
    subtitle,
    description,
    bullets: asStringArray(product.bullets),
    tags: asStringArray(product.tags),
    category,
    categoryIds: categoryIds.length > 0 ? categoryIds : [category],
    origin,
    price,
    priceMinor: toMinorUnits(price),
    currency: normalizeCurrency(product.currency ?? DEFAULTS.currency),
    rating: asNumber(existing.get("rating"), asNumber(product.rating, 0)),
    ratingAvg: asNumber(existing.get("ratingAvg"), asNumber(product.rating, 0)),
    reviewsCount: Math.max(0, Math.floor(asNumber(existing.get("reviewsCount"), asNumber(product.reviewsCount)))),
    ratingCount: Math.max(0, Math.floor(asNumber(existing.get("ratingCount"), asNumber(product.reviewsCount)))),
    imageUrl,
    imageUrls: imageUrls.length > 0 ? imageUrls : (imageUrl ? [imageUrl] : []),
    stock,
    isBio: asBoolean(product.isBio),
    isActive: asBoolean(product.isActive, true),
    status: normalizeProductStatus(product.status),
    searchKeywords: generateSearchKeywords(
      title,
      subtitle,
      category,
      origin,
      asStringArray(product.tags),
    ),
    schemaVersion: SCHEMA_VERSION,
    createdAt: existing.get("createdAt") || admin.firestore.FieldValue.serverTimestamp(),
    updatedAt: admin.firestore.FieldValue.serverTimestamp(),
  };

  await productRef.set(normalizedProduct, {merge: true});

  logger.info("Product upserted through admin callable", {
    productId,
    status: normalizedProduct.status,
    stock,
  });

  return {
    product: {
      ...normalizedProduct,
      createdAt: asMillis(existing.get("createdAt")) || nowMs,
      updatedAt: nowMs,
    },
  };
});
