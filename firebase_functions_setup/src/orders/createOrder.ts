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

function parseCartItems(value: unknown): CartItemRequest[] {
  if (!Array.isArray(value)) {
    return [];
  }

  const merged = new Map<string, number>();
  for (const entry of value) {
    const item = asRecord(entry);
    if (!item) continue;

    const productId = asString(item.productId).trim();
    const quantity = Math.max(0, Math.floor(asNumber(item.quantity)));
    if (!productId || quantity <= 0) continue;
    merged.set(productId, (merged.get(productId) || 0) + quantity);
  }

  return Array.from(merged.entries()).map(([productId, quantity]) => ({
    productId,
    quantity,
  }));
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

  return Object.entries(legacyItems as Record<string, unknown>)
    .map(([productId, quantity]) => ({
      productId,
      quantity: Math.max(0, Math.floor(asNumber(quantity))),
    }))
    .filter((item) => item.productId.length > 0 && item.quantity > 0);
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

export const createOrder = onCall(async (request) => {
  const {uid} = assertAuthenticated(request);
  const payload = asRecord(request.data) || {};
  const orderPayload = asRecord(payload.order) || {};
  const clientRequestId = asString(payload.clientRequestId).trim();
  const deliveryType = resolveDeliveryType(payload.deliveryType ?? orderPayload.deliveryType);
  const shippingAddress = parseShippingAddress(orderPayload.shippingAddress);
  const paymentMethod = asString(orderPayload.paymentMethod, "COD").trim().toUpperCase();

  if (paymentMethod !== "COD") {
    throw new HttpsError("failed-precondition", "Cash on delivery is the only supported payment method.");
  }

  const requestedItems = parseCartItems(orderPayload.items);
  const cartItems = await loadCanonicalCart(uid);
  const orderItemsRequest = cartItems.length > 0 ? cartItems : requestedItems;

  if (orderItemsRequest.length === 0) {
    throw new HttpsError("failed-precondition", "Your cart is empty.");
  }

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
  const legacyOrderRef = db.collection(COLLECTIONS.USERS)
    .doc(uid)
    .collection(USER_SUBCOLLECTIONS.ORDERS)
    .doc(orderRef.id);
  const canonicalCartRef = db.collection(COLLECTIONS.USERS)
    .doc(uid)
    .collection(USER_SUBCOLLECTIONS.CART)
    .doc("active");
  const legacyCartRef = db.collection(COLLECTIONS.CARTS_LEGACY).doc(uid);
  const requestRef = clientRequestId ?
    db.collection(COLLECTIONS.USERS)
      .doc(uid)
      .collection(USER_SUBCOLLECTIONS.ORDER_REQUESTS)
      .doc(clientRequestId) :
    null;
  const inboxRef = db.collection(COLLECTIONS.USERS)
    .doc(uid)
    .collection(USER_SUBCOLLECTIONS.INBOX)
    .doc();

  const nowMs = Date.now();
  let responseOrder: Record<string, unknown> | null = null;

  await db.runTransaction(async (transaction) => {
    if (requestRef) {
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
    }

    const itemSnapshots: Array<Record<string, unknown>> = [];
    let subtotalMinor = 0;
    let currency: string = DEFAULTS.currency;

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
      if (!isActive || status === "archived" || status === "draft") {
        throw new HttpsError("failed-precondition", `Product ${requestedItem.productId} is not available.`);
      }
      if (requestedItem.quantity > stock) {
        throw new HttpsError(
          "failed-precondition",
          `Insufficient stock for ${asString(productData.title, requestedItem.productId)}.`,
        );
      }

      const itemPriceMinor = productData.priceMinor != null ?
        Math.max(0, Math.floor(asNumber(productData.priceMinor))) :
        toMinorUnits(asNumber(productData.price));
      subtotalMinor += itemPriceMinor * requestedItem.quantity;
      currency = normalizeCurrency(productData.currency);

      const imageUrls = Array.isArray(productData.imageUrls) ?
        productData.imageUrls.filter((value): value is string => typeof value === "string") :
        [];
      const thumbnailUrl = imageUrls[0] || asString(productData.imageUrl);

      itemSnapshots.push({
        productId: requestedItem.productId,
        name: asString(productData.title, requestedItem.productId),
        priceAtPurchase: fromMinorUnits(itemPriceMinor),
        priceAtPurchaseMinor: itemPriceMinor,
        quantity: requestedItem.quantity,
        thumbnailUrl,
      });

      transaction.update(productRef, {
        stock: stock - requestedItem.quantity,
        updatedAt: admin.firestore.FieldValue.serverTimestamp(),
      });
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
      trackingEvents,
      statusTimeline: trackingEvents,
      serverVerified: true,
      schemaVersion: SCHEMA_VERSION,
      createdAt: admin.firestore.FieldValue.serverTimestamp(),
      updatedAt: admin.firestore.FieldValue.serverTimestamp(),
    };

    transaction.set(orderRef, orderRecord);
    transaction.set(legacyOrderRef, orderRecord);
    transaction.set(canonicalCartRef, {
      items: [],
      schemaVersion: SCHEMA_VERSION,
      updatedAt: admin.firestore.FieldValue.serverTimestamp(),
    }, {merge: true});
    transaction.set(legacyCartRef, {
      items: {},
      updatedAt: admin.firestore.FieldValue.serverTimestamp(),
    }, {merge: true});

    if (requestRef) {
      transaction.set(requestRef, {
        orderId: orderRef.id,
        schemaVersion: SCHEMA_VERSION,
        createdAt: admin.firestore.FieldValue.serverTimestamp(),
      }, {merge: true});
    }

    transaction.set(inboxRef, {
      id: inboxRef.id,
      type: "order_created",
      title: "Commande creee",
      body: `Votre commande ${orderRef.id} a bien ete enregistree.`,
      route: "order_details",
      entityRef: orderRef.id,
      orderId: orderRef.id,
      readAt: null,
      createdAt: admin.firestore.FieldValue.serverTimestamp(),
      updatedAt: admin.firestore.FieldValue.serverTimestamp(),
      schemaVersion: SCHEMA_VERSION,
    });

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

  return {order: responseOrder};
});
