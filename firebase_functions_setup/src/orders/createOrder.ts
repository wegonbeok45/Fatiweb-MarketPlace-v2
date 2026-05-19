import {logger} from "firebase-functions";
import {HttpsError, onCall} from "firebase-functions/v2/https";
import {
  COLLECTIONS,
  DEFAULTS,
  ORDER_STATUSES,
  SCHEMA_VERSION,
  USER_SUBCOLLECTIONS,
} from "../shared/constants";
import {assertAuthenticated} from "../shared/auth";
import {admin, db} from "../shared/firestore";
import {hotHeavyCallableOptions} from "../shared/callableOptions";
import {sendPushToUser} from "../notifications/push";
import {
  appendOrderTrackingEvent,
  asMillis,
  asNumber,
  asRecord,
  asString,
  fromMinorUnits,
  normalizeCurrency,
  toMinorUnits,
} from "../shared/domain";

type CartItemRequest = {
  productId: string;
  quantity: number;
};

type ShippingAddressRequest = {
  label: string;
  recipientName: string;
  phone: string;
  governorate: string;
  city: string;
  addressLine1: string;
  addressLine2: string;
  postalCode: string;
  deliveryNotes: string;
};

const MAX_ORDER_LINE_ITEMS = 20;
const MAX_ORDER_ITEM_QUANTITY = 99;
const SAFE_PRODUCT_ID = /^[A-Za-z0-9_-]{1,160}$/;

function parseCartItems(value: unknown): CartItemRequest[] {
  if (!Array.isArray(value)) {
    return [];
  }
  if (value.length > MAX_ORDER_LINE_ITEMS) {
    throw new HttpsError("invalid-argument", "Too many cart items.");
  }

  const merged = new Map<string, number>();
  for (const entry of value) {
    const item = asRecord(entry);
    if (!item) continue;

    const productId = asString(item.productId).trim();
    const quantity = Math.max(0, Math.floor(asNumber(item.quantity)));
    if (!productId || quantity <= 0) continue;
    if (!SAFE_PRODUCT_ID.test(productId)) {
      throw new HttpsError("invalid-argument", "Invalid product id in cart.");
    }
    if (quantity > MAX_ORDER_ITEM_QUANTITY) {
      throw new HttpsError("invalid-argument", "Cart item quantity is too high.");
    }
    merged.set(productId, (merged.get(productId) || 0) + quantity);
  }

  const items = Array.from(merged.entries()).map(([productId, quantity]) => ({
    productId,
    quantity,
  }));
  if (items.length > MAX_ORDER_LINE_ITEMS || items.some((item) => item.quantity > MAX_ORDER_ITEM_QUANTITY)) {
    throw new HttpsError("invalid-argument", "Cart is too large.");
  }
  return items;
}

function validateCartItems(items: CartItemRequest[]): CartItemRequest[] {
  if (items.length > MAX_ORDER_LINE_ITEMS) {
    throw new HttpsError("invalid-argument", "Too many cart items.");
  }
  for (const item of items) {
    if (!SAFE_PRODUCT_ID.test(item.productId) ||
      item.quantity <= 0 ||
      item.quantity > MAX_ORDER_ITEM_QUANTITY) {
      throw new HttpsError("invalid-argument", "Invalid cart item.");
    }
  }
  return items;
}

async function loadCanonicalCart(uid: string): Promise<CartItemRequest[]> {
  const cartRef = db.collection(COLLECTIONS.USERS)
    .doc(uid)
    .collection(USER_SUBCOLLECTIONS.CART)
    .doc("active");
  const cartDoc = await cartRef.get();
  const cartItems = parseCartItems(cartDoc.get("items"));
  if (cartItems.length > 0) {
    return cartItems;
  }

  const legacyDoc = await db.collection(COLLECTIONS.CARTS_LEGACY).doc(uid).get();
  const legacyItems = legacyDoc.get("items");
  if (!legacyItems || typeof legacyItems !== "object" || Array.isArray(legacyItems)) {
    return [];
  }

  return validateCartItems(Object.entries(legacyItems as Record<string, unknown>)
    .map(([productId, quantity]) => ({
      productId,
      quantity: Math.max(0, Math.floor(asNumber(quantity))),
    }))
    .filter((item) => item.productId.length > 0 && item.quantity > 0));
}

function parseShippingAddress(value: unknown): ShippingAddressRequest {
  const data = asRecord(value) || {};
  const shippingAddress: ShippingAddressRequest = {
    label: asString(data.label).trim(),
    recipientName: asString(data.recipientName).trim(),
    phone: asString(data.phone).trim(),
    governorate: asString(data.governorate).trim(),
    city: asString(data.city).trim(),
    addressLine1: asString(data.addressLine1).trim(),
    addressLine2: asString(data.addressLine2).trim(),
    postalCode: asString(data.postalCode).trim(),
    deliveryNotes: asString(data.deliveryNotes).trim(),
  };

  if (
    shippingAddress.recipientName.length < 3 ||
    shippingAddress.phone.length < 8 ||
    shippingAddress.governorate.length < 2 ||
    shippingAddress.city.length < 2 ||
    shippingAddress.addressLine1.length < 5
  ) {
    throw new HttpsError("invalid-argument", "A complete shipping address is required.");
  }

  return shippingAddress;
}

function resolveDeliveryType(value: unknown): "standard" | "express" {
  return asString(value).trim().toLowerCase() === "express" ? "express" : "standard";
}

function parseClientRequestId(value: unknown): string {
  const clientRequestId = asString(value).trim();
  if (!/^[A-Za-z0-9_-]{8,128}$/.test(clientRequestId)) {
    throw new HttpsError("invalid-argument", "A stable clientRequestId is required.");
  }
  return clientRequestId;
}

function orderCreatedNotificationCopy(language: string) {
  const isEnglish = language.startsWith("en");
  if (isEnglish) {
    return {
      title: "Order confirmed",
      body: "Your order has been received and is ready for tracking.",
    };
  }
  return {
    title: "Commande confirmee",
    body: "Votre commande a bien ete enregistree et peut etre suivie.",
  };
}

export const createOrder = onCall(hotHeavyCallableOptions, async (request) => {
  const {uid} = assertAuthenticated(request);
  const payload = asRecord(request.data) || {};
  const orderPayload = asRecord(payload.order) || {};
  const clientRequestId = parseClientRequestId(payload.clientRequestId);
  const deliveryType = resolveDeliveryType(payload.deliveryType ?? orderPayload.deliveryType);
  const shippingAddress = parseShippingAddress(orderPayload.shippingAddress);
  const paymentMethod = asString(orderPayload.paymentMethod, "COD").trim().toUpperCase();

  if (paymentMethod !== "COD") {
    throw new HttpsError("failed-precondition", "Cash on delivery is the only supported payment method.");
  }

  const requestedItems = parseCartItems(orderPayload.items);
  const cartItems = await loadCanonicalCart(uid);
  const orderItemsRequest = requestedItems.length > 0 ? requestedItems : cartItems;

  if (orderItemsRequest.length === 0) {
    throw new HttpsError("failed-precondition", "Your cart is empty.");
  }

  const userDoc = await db.collection(COLLECTIONS.USERS).doc(uid).get();
  const preferredLanguage = asString(
    userDoc.get("language"),
    asString(userDoc.get("preferredLanguage"), "fr"),
  ).trim().toLowerCase();
  const notificationCopy = orderCreatedNotificationCopy(preferredLanguage);

  const commerceDoc = await db.collection(COLLECTIONS.CONFIG)
    .doc(COLLECTIONS.COMMERCE)
    .get();
  const standardShippingFee = asNumber(
    commerceDoc.get("standardShippingFee"),
    DEFAULTS.standardShippingFee,
  );
  const expressShippingFee = asNumber(
    commerceDoc.get("expressShippingFee"),
    DEFAULTS.expressShippingFee,
  );

  const orderRef = db.collection(COLLECTIONS.ORDERS).doc();
  const userRef = db.collection(COLLECTIONS.USERS).doc(uid);
  const canonicalCartRef = db.collection(COLLECTIONS.USERS)
    .doc(uid)
    .collection(USER_SUBCOLLECTIONS.CART)
    .doc("active");
  const requestRef = db.collection(COLLECTIONS.USERS)
    .doc(uid)
    .collection(USER_SUBCOLLECTIONS.ORDER_REQUESTS)
    .doc(clientRequestId);
  const inboxRef = db.collection(COLLECTIONS.USERS)
    .doc(uid)
    .collection(USER_SUBCOLLECTIONS.INBOX)
    .doc(`order_created_${orderRef.id}`);

  const nowMs = Date.now();
  let responseOrder: Record<string, unknown> | null = null;
  let shouldNotify = false;

  await db.runTransaction(async (transaction) => {
    responseOrder = null;
    shouldNotify = false;

    const existingRequest = await transaction.get(requestRef);
    if (existingRequest.exists) {
      const existingOrderId = asString(existingRequest.get("orderId"));
      if (existingOrderId) {
        const existingOrder = await transaction.get(db.collection(COLLECTIONS.ORDERS).doc(existingOrderId));
        if (existingOrder.exists && existingOrder.data()) {
          responseOrder = {
            ...existingOrder.data()!,
            createdAt: asMillis(existingOrder.get("createdAt")) || nowMs,
            updatedAt: asMillis(existingOrder.get("updatedAt")) || nowMs,
          };
          return;
        }
      }
    }

    const itemSnapshots: Array<Record<string, unknown>> = [];
    const sellerIds = new Set<string>();
    let subtotalMinor = 0;
    let currency: string = DEFAULTS.currency;
    const productSnapshots: Array<{
      requestedItem: CartItemRequest;
      productRef: FirebaseFirestore.DocumentReference;
      productData: FirebaseFirestore.DocumentData;
      stock: number;
    }> = [];

    for (const requestedItem of orderItemsRequest) {
      const productRef = db.collection(COLLECTIONS.PRODUCTS).doc(requestedItem.productId);
      const productDoc = await transaction.get(productRef);
      if (!productDoc.exists) {
        throw new HttpsError("not-found", `Product ${requestedItem.productId} was not found.`);
      }

      const productData = productDoc.data() || {};
      const stock = Math.max(0, Math.floor(asNumber(productData.stock)));
      const isActive = productData.isActive !== false;
      const status = asString(productData.status, "published").trim().toLowerCase();
      if (!isActive || status !== "published") {
        throw new HttpsError("failed-precondition", `Product ${requestedItem.productId} is not available.`);
      }
      if (requestedItem.quantity > stock) {
        throw new HttpsError(
          "failed-precondition",
          `Insufficient stock for ${asString(productData.title, requestedItem.productId)}.`,
        );
      }

      productSnapshots.push({
        requestedItem,
        productRef,
        productData,
        stock,
      });
    }

    for (const {requestedItem, productRef, productData, stock} of productSnapshots) {
      const itemPriceMinor = productData.priceMinor != null ?
        Math.max(0, Math.floor(asNumber(productData.priceMinor))) :
        toMinorUnits(asNumber(productData.price));
      subtotalMinor += itemPriceMinor * requestedItem.quantity;
      currency = normalizeCurrency(productData.currency);

      const imageUrls = Array.isArray(productData.imageUrls) ?
        productData.imageUrls.filter((value): value is string => typeof value === "string") :
        [];
      const thumbnailUrl = imageUrls[0] || asString(productData.imageUrl);
      const sellerId = asString(productData.sellerId).trim();
      if (sellerId) {
        sellerIds.add(sellerId);
      }

      itemSnapshots.push({
        productId: requestedItem.productId,
        name: asString(productData.title, requestedItem.productId),
        priceAtPurchase: fromMinorUnits(itemPriceMinor),
        priceAtPurchaseMinor: itemPriceMinor,
        quantity: requestedItem.quantity,
        thumbnailUrl,
        sellerId,
        sellerName: asString(productData.sellerName),
        sellerAvatarUrl: asString(productData.sellerAvatarUrl),
      });

      transaction.update(productRef, {
        stock: stock - requestedItem.quantity,
        updatedAt: admin.firestore.FieldValue.serverTimestamp(),
      });
      if (sellerId) {
        transaction.set(db.collection(COLLECTIONS.USERS).doc(sellerId), {
          totalSold: admin.firestore.FieldValue.increment(requestedItem.quantity),
          sellerTotalSold: admin.firestore.FieldValue.increment(requestedItem.quantity),
          updatedAt: admin.firestore.FieldValue.serverTimestamp(),
        }, {merge: true});
      }
    }

    const deliveryFee = deliveryType === "express" ? expressShippingFee : standardShippingFee;
    const deliveryFeeMinor = toMinorUnits(deliveryFee);
    const totalMinor = subtotalMinor + deliveryFeeMinor;
    const trackingEvents = appendOrderTrackingEvent([], ORDER_STATUSES[0], nowMs);

    const orderRecord = {
      id: orderRef.id,
      uid,
      userId: uid,
      status: ORDER_STATUSES[0],
      paymentMethod: "COD",
      deliveryType,
      subtotal: fromMinorUnits(subtotalMinor),
      subtotalMinor,
      deliveryFee,
      shippingFee: deliveryFee,
      deliveryFeeMinor,
      total: fromMinorUnits(totalMinor),
      totalMinor,
      currency,
      shippingAddress,
      items: itemSnapshots,
      sellerIds: Array.from(sellerIds),
      clientRequestId,
      trackingEvents,
      statusTimeline: trackingEvents,
      serverVerified: true,
      schemaVersion: SCHEMA_VERSION,
      createdAt: admin.firestore.FieldValue.serverTimestamp(),
      updatedAt: admin.firestore.FieldValue.serverTimestamp(),
    };

    transaction.set(orderRef, orderRecord);
    transaction.set(canonicalCartRef, {
      items: [],
      schemaVersion: SCHEMA_VERSION,
      updatedAt: admin.firestore.FieldValue.serverTimestamp(),
    }, {merge: true});
    transaction.set(userRef, {
      orderCount: admin.firestore.FieldValue.increment(1),
      lastOrderAt: admin.firestore.FieldValue.serverTimestamp(),
      updatedAt: admin.firestore.FieldValue.serverTimestamp(),
    }, {merge: true});

    transaction.set(requestRef, {
      orderId: orderRef.id,
      clientRequestId,
      schemaVersion: SCHEMA_VERSION,
      createdAt: admin.firestore.FieldValue.serverTimestamp(),
    }, {merge: true});

    transaction.set(inboxRef, {
      id: inboxRef.id,
      type: "order_created",
      title: notificationCopy.title,
      body: notificationCopy.body,
      route: "order_details",
      entityRef: orderRef.id,
      orderId: orderRef.id,
      readAt: null,
      createdAt: admin.firestore.FieldValue.serverTimestamp(),
      updatedAt: admin.firestore.FieldValue.serverTimestamp(),
      schemaVersion: SCHEMA_VERSION,
    });
    shouldNotify = true;

    responseOrder = {
      ...orderRecord,
      createdAt: nowMs,
      updatedAt: nowMs,
    };
  });

  logger.info("Order created through trusted workflow", {
    uid,
    orderId: responseOrder?.["id"],
    deliveryType,
    itemCount: orderItemsRequest.length,
  });

  if (shouldNotify && responseOrder) {
    await sendPushToUser(uid, notificationCopy.title, notificationCopy.body, {
      route: "order_details",
      orderId: asString(responseOrder["id"]),
    });
  }

  return {order: responseOrder};
});
