import {HttpsError, onCall} from "firebase-functions/v2/https";
import {COLLECTIONS, USER_ROLES} from "../shared/constants";
import {asNumber} from "../shared/domain";
import {admin, db} from "../shared/firestore";
import {assertAdminOrVendeur} from "../shared/auth";
import {trustedCallableOptions} from "../shared/callableOptions";

const MAX_SELLER_ORDERS = 300;

export const sellerFetchWorkspace = onCall(trustedCallableOptions, async (request) => {
  const actor = await assertAdminOrVendeur(request);
  const requestedSellerId = typeof request.data?.sellerId === "string" ?
    request.data.sellerId.trim() :
    "";
  const sellerId = actor.role === USER_ROLES.ADMIN && requestedSellerId ?
    requestedSellerId :
    actor.uid;

  if (!sellerId) {
    throw new HttpsError("invalid-argument", "Seller id is required.");
  }

  const [ordersSnapshot, productsSnapshot] = await Promise.all([
    db.collection(COLLECTIONS.ORDERS)
      .where("sellerIds", "array-contains", sellerId)
      .get(),
    db.collection(COLLECTIONS.PRODUCTS)
      .where("sellerId", "==", sellerId)
      .get(),
  ]);

  const orders = ordersSnapshot.docs
    .map((doc) => serializeOrder(doc))
    .sort((left, right) => asNumber(right.createdAt) - asNumber(left.createdAt))
    .slice(0, MAX_SELLER_ORDERS);

  const products = productsSnapshot.docs.map((doc) => doc.data());
  const totalProducts = products.length;
  const activeProducts = products.filter((product) => product.isActive !== false).length;
  const lowStockProducts = products.filter((product) => {
    const stock = Math.floor(asNumber(product.stock));
    return product.isActive !== false && stock > 0 && stock < 6;
  }).length;

  return {
    sellerId,
    orders,
    productsSummary: {
      totalProducts,
      activeProducts,
      lowStockProducts,
    },
  };
});

function serializeOrder(doc: FirebaseFirestore.QueryDocumentSnapshot): Record<string, unknown> {
  return {
    ...serializeValue(doc.data()) as Record<string, unknown>,
    id: doc.id,
  };
}

function serializeValue(value: unknown): unknown {
  if (value instanceof admin.firestore.Timestamp) {
    return value.toMillis();
  }
  if (Array.isArray(value)) {
    return value.map(serializeValue);
  }
  if (value && typeof value === "object") {
    return Object.fromEntries(
      Object.entries(value as Record<string, unknown>)
        .map(([key, nested]) => [key, serializeValue(nested)]),
    );
  }
  return value;
}
