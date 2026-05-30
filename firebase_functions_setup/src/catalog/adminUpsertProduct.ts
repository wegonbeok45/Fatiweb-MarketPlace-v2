import {logger} from "firebase-functions";
import {HttpsError, onCall} from "firebase-functions/v2/https";
import {
  COLLECTIONS,
  DEFAULTS,
  PRODUCT_STATUSES,
  SCHEMA_VERSION,
  USER_ROLES,
  type ProductStatus,
} from "../shared/constants";
import {assertAdminOrVendeur} from "../shared/auth";
import {admin, db} from "../shared/firestore";
import {trustedCallableOptions} from "../shared/callableOptions";
import {
  asBoolean,
  asMillis,
  asNumber,
  asRecord,
  asString,
  asStringArray,
  generateSearchKeywords,
  normalizeCurrency,
  toMinorUnits,
} from "../shared/domain";

const TRUSTED_IMAGE_HOSTS = new Set([
  "firebasestorage.googleapis.com",
  "storage.googleapis.com",
  "res.cloudinary.com",
]);

// ── Variant & attribute sanitizers ──────────────────────────────────────────

function sanitizeAttributeValue(v: unknown): string | number | boolean | null {
  if (typeof v === "string") return v.trim().slice(0, 500);
  if (typeof v === "number" && isFinite(v)) return v;
  if (typeof v === "boolean") return v;
  return null;
}

function sanitizeAttributes(raw: unknown): Record<string, unknown> {
  const rec = asRecord(raw);
  if (!rec) return {};
  const out: Record<string, unknown> = {};
  for (const [k, v] of Object.entries(rec)) {
    const key = k.trim().slice(0, 80);
    if (!key) continue;
    const val = sanitizeAttributeValue(v);
    if (val !== null) out[key] = val;
  }
  return out;
}

function sanitizeColorOptions(raw: unknown): Array<Record<string, string>> {
  if (!Array.isArray(raw)) return [];
  return raw
    .slice(0, 20)
    .map((item) => {
      const rec = asRecord(item);
      if (!rec) return null;
      const name = asString(rec.name).trim().slice(0, 80);
      if (!name) return null;
      return {name, hex: asString(rec.hex).trim().slice(0, 20)};
    })
    .filter((x): x is {name: string; hex: string} => x !== null);
}

function sanitizeSizeOptions(raw: unknown): string[] {
  if (!Array.isArray(raw)) return [];
  return raw
    .slice(0, 30)
    .map((s) => asString(s).trim().slice(0, 40))
    .filter((s) => s.length > 0);
}

type SanitizedVariant = {
  variantId: string;
  colorName: string;
  colorHex: string;
  size: string;
  stock: number;
  priceOverrideMinor?: number;
  imageUrl: string | null;
  sku: string;
  active: boolean;
};

function sanitizeVariants(raw: unknown): SanitizedVariant[] {
  if (!Array.isArray(raw)) return [];
  const results: SanitizedVariant[] = [];
  for (const item of raw.slice(0, 200)) {
    const rec = asRecord(item);
    if (!rec) continue;
    const variantId = asString(rec.variantId).trim().slice(0, 120);
    if (!variantId) continue;
    const stock = Math.max(0, Math.floor(asNumber(rec.stock)));
    const priceOverrideRaw = rec.priceOverrideMinor;
    const entry: SanitizedVariant = {
      variantId,
      colorName: asString(rec.colorName).trim().slice(0, 80),
      colorHex: asString(rec.colorHex).trim().slice(0, 20),
      size: asString(rec.size).trim().slice(0, 40),
      stock,
      imageUrl: asString(rec.imageUrl).trim().slice(0, 500) || null,
      sku: asString(rec.sku).trim().slice(0, 120),
      active: asBoolean(rec.active, true),
    };
    if (priceOverrideRaw != null) {
      entry.priceOverrideMinor = Math.max(0, Math.floor(asNumber(priceOverrideRaw)));
    }
    results.push(entry);
  }
  return results;
}

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

function normalizeRequestedProductStatus(value: unknown): ProductStatus {
  const status = asString(value, "published").trim().toLowerCase();
  if ((PRODUCT_STATUSES as readonly string[]).includes(status)) {
    return status as ProductStatus;
  }
  throw new HttpsError("invalid-argument", "Unsupported product status.");
}

function normalizeTrustedImageUrl(value: string): string {
  const trimmed = value.trim();
  let parsed: URL;
  try {
    parsed = new URL(trimmed);
  } catch {
    throw new HttpsError("invalid-argument", "Product images must use trusted HTTPS URLs.");
  }

  const host = parsed.hostname.toLowerCase();
  const trustedHost = TRUSTED_IMAGE_HOSTS.has(host) ||
    host.endsWith(".firebasestorage.app") ||
    host.endsWith(".cloudinary.com");
  if (parsed.protocol !== "https:" || !trustedHost) {
    throw new HttpsError("invalid-argument", "Product images must use trusted HTTPS URLs.");
  }
  return parsed.toString();
}

export const adminUpsertProduct = onCall(trustedCallableOptions, async (request) => {
  const actor = await assertAdminOrVendeur(request);
  const payload = asRecord(request.data) || {};
  const product = asRecord(payload.product) || payload;

  const title = asString(product.title).trim();
  const subtitle = asString(product.subtitle).trim();
  const description = asString(product.description).trim();
  const category = asString(product.category, "electronics").trim().toLowerCase();
  const origin = asString(product.origin, "tunisia").trim().toLowerCase();
  const price = asNumber(product.price);
  const stock = Math.max(0, Math.floor(asNumber(product.stock)));

  if (title.length < 3 || subtitle.length < 3 || description.length < 12 || price <= 0) {
    throw new HttpsError("invalid-argument", "The product payload is incomplete.");
  }

  const productId = normalizeProductId(asString(product.id), title);
  const productRef = db.collection(COLLECTIONS.PRODUCTS).doc(productId);
  const existing = await productRef.get();
  const existingSellerId = asString(existing.get("sellerId")).trim();

  if (actor.role !== USER_ROLES.ADMIN && existing.exists && existingSellerId !== actor.uid) {
    throw new HttpsError("permission-denied", "You can edit only your own products.");
  }

  const requestedSellerId = asString(product.sellerId).trim();
  const sellerId = existing.exists ?
    (existingSellerId || actor.uid) :
    (actor.role === USER_ROLES.ADMIN && requestedSellerId ? requestedSellerId : actor.uid);
  const sellerDoc = await db.collection(COLLECTIONS.USERS).doc(sellerId).get();
  if (!sellerDoc.exists) {
    throw new HttpsError("failed-precondition", "The seller profile does not exist.");
  }
  const sellerRole = asString(sellerDoc.get("role"), USER_ROLES.CLIENT).trim().toLowerCase();
  if (sellerRole !== USER_ROLES.ADMIN && sellerRole !== USER_ROLES.VENDEUR) {
    throw new HttpsError("failed-precondition", "Products must be assigned to an active seller.");
  }
  const sellerName = asString(existing.get("sellerName")).trim() ||
    asString(sellerDoc.get("name")).trim() ||
    asString(sellerDoc.get("displayName")).trim() ||
    actor.name;
  const sellerAvatarUrl = asString(existing.get("sellerAvatarUrl")).trim() ||
    asString(sellerDoc.get("avatarUrl")).trim() ||
    asString(sellerDoc.get("avatar")).trim() ||
    actor.avatarUrl;
  const requestedImageUrls = Array.from(new Set([
    ...asStringArray(product.imageUrls),
    asString(product.imageUrl).trim(),
  ].filter((url) => url.length > 0))).map(normalizeTrustedImageUrl);
  if (requestedImageUrls.length > 5) {
    throw new HttpsError("invalid-argument", "A product can have at most 5 images.");
  }
  const imageUrls = requestedImageUrls.slice(0, 5);
  if (imageUrls.length < 1) {
    throw new HttpsError("invalid-argument", "At least one product image is required.");
  }
  const imageUrl = imageUrls[0];
  const categoryIds = asStringArray(product.categoryIds);
  const categoryLeafId = asString(product.categoryLeafId).trim();
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
    categoryLeafId: categoryLeafId || categoryIds.at(-1) || category,
    origin,
    price,
    priceMinor: toMinorUnits(price),
    currency: normalizeCurrency(product.currency ?? DEFAULTS.currency),
    rating: asNumber(existing.get("rating"), asNumber(product.rating, 0)),
    ratingAvg: asNumber(existing.get("ratingAvg"), asNumber(product.rating, 0)),
    reviewsCount: Math.max(0, Math.floor(asNumber(existing.get("reviewsCount"), asNumber(product.reviewsCount)))),
    ratingCount: Math.max(0, Math.floor(asNumber(existing.get("ratingCount"), asNumber(product.reviewsCount)))),
    imageUrl,
    imageUrls,
    stock,
    isBio: asBoolean(product.isBio),
    isActive: asBoolean(product.isActive, true),
    status: normalizeRequestedProductStatus(product.status),
    searchKeywords: generateSearchKeywords(
      title,
      subtitle,
      description,
      category,
      origin,
      asStringArray(product.tags),
    ),
    schemaVersion: SCHEMA_VERSION,
    sellerId,
    sellerName,
    sellerAvatarUrl,
    sellerVerifiedAt: sellerDoc.get("verifiedAt") || sellerDoc.get("sellerVerifiedAt") || null,
    sellerMemberSince: sellerDoc.get("memberSince") || sellerDoc.get("createdAt") || null,
    sellerTotalSold: Math.max(0, Math.floor(asNumber(sellerDoc.get("totalSold"), asNumber(sellerDoc.get("sellerTotalSold"))))),
    sellerRating: asNumber(sellerDoc.get("sellerRating"), asNumber(sellerDoc.get("ratingAvg"))),
    sellerRatingCount: Math.max(0, Math.floor(asNumber(sellerDoc.get("sellerRatingCount"), asNumber(sellerDoc.get("ratingCount"))))),
    productType: asString(product.productType).trim().slice(0, 40),
    attributes: sanitizeAttributes(product.attributes),
    colorOptions: sanitizeColorOptions(product.colorOptions),
    sizeOptions: sanitizeSizeOptions(product.sizeOptions),
    variants: sanitizeVariants(product.variants),
    createdAt: existing.get("createdAt") || admin.firestore.FieldValue.serverTimestamp(),
    updatedAt: admin.firestore.FieldValue.serverTimestamp(),
  };

  await productRef.set(normalizedProduct, {merge: true});

  logger.info("Product upserted through admin callable", {
    productId,
    actorUid: actor.uid,
    actorRole: actor.role,
    sellerId,
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
