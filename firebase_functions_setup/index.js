const functions = require("firebase-functions");
const admin = require("firebase-admin");
admin.initializeApp();

/**
 * Security Rule Enforcer: verifyOrderTotal
 * 
 * Intercepts every new order placed by standard clients.
 * It ignores the client-provided total, subtotal, and shipping fee.
 * Instead, it re-fetches the actual prices from the `products` collection,
 * recalculates everything, and overwrites the document to prevent cheating.
 */
exports.verifyOrderTotal = functions.firestore
  .document("users/{userId}/orders/{orderId}")
  .onCreate(async (snap, context) => {
    const orderData = snap.data();
    if (!orderData || !orderData.items) return null;

    const items = orderData.items; // map of productId -> quantity
    let trueSubtotal = 0;

    const db = admin.firestore();
    
    // Fetch all products in the cart securely from the server
    for (const [productId, quantity] of Object.entries(items)) {
      const productDoc = await db.collection("products").doc(productId).get();
      if (productDoc.exists) {
        const product = productDoc.data();
        const price = product.price || 0;
        // ensure quantity is a positive integer
        const safeQty = Math.max(0, parseInt(quantity, 10) || 0);
        trueSubtotal += price * safeQty;
      }
    }

    // Shipping fee calculation based on delivery type
    const isStandard = orderData.deliveryType !== "express";
    
    // dynamically fetch shipping constraints from commerce config
    const configDoc = await db.collection("commerce").doc("config").get();
    let standardShipping = 7.0;
    let expressShipping = 12.5;
    
    if (configDoc.exists) {
        const config = configDoc.data();
        if (config.standardShippingFee !== undefined) standardShipping = config.standardShippingFee;
        if (config.expressShippingFee !== undefined) expressShipping = config.expressShippingFee;
    }

    const shippingFee = isStandard ? standardShipping : expressShipping;
    const trueTotal = trueSubtotal + shippingFee;

    // Force update the record to reflect the server-verified amounts
    return snap.ref.update({
      subtotal: trueSubtotal,
      shippingFee: shippingFee,
      total: trueTotal,
      serverVerified: true,
      updatedAt: admin.firestore.FieldValue.serverTimestamp()
    });
  });
