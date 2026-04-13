# FatiWeb Marketplace - Backend Architecture Redesign & Specs

## A. Design Challenges & Final Architectural Decisions

Before finalizing the schema, we must resolve five critical architectural design choices specifically for the FatiWeb Marketplace (an Android COD marketplace). 

### 1. Root `/carts` vs. Nested `/users/{uid}/cart`
*   **The Challenge:** A centralized root cart collection vs. keeping the cart bound within the user's document path.
*   **Final Decision:** **Nested `/users/{uid}/cart` (Subcollection with a single `active` document).**
*   **Why for this app:** Securing user data is paramount. By nesting the cart under the user, we can apply a simple blanket security rule `match /users/{uid}/{document=**} { allow read, write: if request.auth.uid == uid; }`. It keeps all user-specific transactional data clustered, easily wiping it upon account deletion. A single document `active` inside this subcollection will contain the item array, keeping the main `user` document lightweight and avoiding unnecessary reads of the cart when fetching just the user's profile.

### 2. Root `/reviews` vs. Nested `/products/{productId}/reviews`
*   **The Challenge:** Storing reviews in a massive global collection vs. clustered within the product path.
*   **Final Decision:** **Nested `/products/{productId}/reviews/{reviewId}`.**
*   **Why for this app:** The primary access pattern is reading a product and immediately fetching its reviews. A subcollection natively clusters this data. If we need to fetch a user's total written reviews, we can use a Firestore Collection Group Query (`db.collectionGroup("reviews").whereEqualTo("userId", uid)`). This achieves the best of both worlds: fast product-page loads and easy user-profile aggregation.

### 3. Image Storage: `gs://` paths vs. `https://` Download URLs
*   **The Challenge:** Representing image references in Firestore documents.
*   **Final Decision:** **`https://` Download URLs.**
*   **Why for this app:** While `gs://` is clean, it requires using the Firebase Storage SDK on Android to resolve the URL before displaying it. This adds an extra asynchronous network hop, noticeably delaying image loading in scrolling lists (like the marketplace catalog). Storing the direct `https://` download URL allows seamless, instant integration with standard Android image loaders (like Coil or Glide) and takes full advantage of caching.

### 4. Timestamp format: Firestore `Timestamp` vs. Epoch Milliseconds (`Long`)
*   **The Challenge:** Relying on client device time (epoch millis) vs server truth.
*   **Final Decision:** **Firestore `Timestamp` (via `FieldValue.serverTimestamp()`).**
*   **Why for this app:** Relying on `System.currentTimeMillis()` is dangerous in an e-commerce context because users can alter their device clocks, potentially messing up order sorting, chat history, or limited-time promotions. Using Firebase's native `Timestamp` ensures a trusted, server-enforced chronological order.

### 5. Order/Cart Items: Arrays vs. Subcollections
*   **The Challenge:** Storing line items inside the master document vs. splitting them into separate documents.
*   **Final Decision:** **Arrays.**
*   **Why for this app:** An artisan marketplace typically sees small to medium basket sizes (1 to 20 items max). If we used a subcollection for order items, retrieving a 10-item order would cost 11 reads. By using an array of objects inside the `Order` or `Cart` document, it only costs 1 read. Firestore documents can hold up to 1MB (enough for thousands of items), meaning arrays provide a massive cost saving and extreme read speed.

---

## B. Target Schema Specs (Refined)

### 1. `/products/{productId}` (Catalog)
```json
{
  "id": "prod_123",
  "name": "Artisan Clay Pot",
  "description": "Handcrafted in Nabeul.",
  "price": 45.00,
  "categoryIds": ["cat_pottery", "cat_home"],
  "stock": 15,
  "status": "published",
  "imageUrls": [
    "https://firebasestorage.googleapis.com/.../main.jpg",
    "https://firebasestorage.googleapis.com/.../side.jpg"
  ],
  "metrics": {
    "ratingAverage": 4.8,
    "reviewCount": 12,
    "salesCount": 140
  },
  "searchKeywords": ["clay", "pot", "nabeul", "artisan"], // For fast local array-contains querying
  "createdAt": <Firestore Timestamp>,
  "updatedAt": <Firestore Timestamp>
}
```

### 2. `/products/{productId}/reviews/{reviewId}` (Subcollection)
```json
{
  "reviewId": "rev_XYZ",
  "userId": "user_abc123",
  "userName": "Foulen Ben Foulen",
  "rating": 5,
  "comment": "Excellent quality!",
  "createdAt": <Firestore Timestamp>
}
```

### 3. `/users/{uid}` (Identity)
```json
{
  "uid": "user_abc123",
  "name": "Foulen Ben Foulen",
  "email": "foulen@email.com",
  "role": "client", 
  "avatarUrl": "https://firebasestorage.googleapis.com/...",
  "createdAt": <Firestore Timestamp>,
  "updatedAt": <Firestore Timestamp>
}
```

### 4. `/users/{uid}/cart/active` (Cart - Subcollection)
```json
{
  "items": [
    {
      "productId": "prod_123",
      "nameSnapshot": "Artisan Clay Pot",     // UX fallback if product deleted
      "thumbnailUrl": "https://...",          // Prevents joining against products for cart display
      "priceSnapshot": 45.00,                 // Current price
      "quantity": 2,
      "addedAt": <Firestore Timestamp>
    }
  ],
  "updatedAt": <Firestore Timestamp>
}
```

### 5. `/orders/{orderId}` (Root Level)
```json
{
  "orderId": "ORD-2026-XYZ",
  "uid": "user_abc123",
  "status": "pending",
  "paymentMethod": "COD",
  "subtotal": 90.00,
  "deliveryFee": 7.00,
  "total": 97.00,
  "shippingAddress": {
    "fullName": "Foulen Ben Foulen",
    "phone": "+216 55 555 555",
    "city": "Tunis",
    "street": "123 Main St"
  },
  "items": [
    {
      "productId": "prod_123",
      "name": "Artisan Clay Pot", 
      "priceAtPurchase": 45.00,       // IMMUTABLE
      "quantity": 2,
      "thumbnailUrl": "https://..."
    }
  ],
  "trackingEvents": [
    { "status": "pending", "timestamp": <Firestore Timestamp> }
  ],
  "createdAt": <Firestore Timestamp>
}
```

---

## C. Implementation Phases Risk & Safety Assessment

### ✅ Safe to Implement Immediately
*   **Phase 1: Catalog Redesign (`/products` & `/categories`).** Creating categories, adjusting product models to use Download URLs, and fixing timestamps are totally isolated from checkout logic.
*   **Phase 2: Persistent Cart Implementation (`/users/{uid}/cart/active`).** We can replace local cart objects with the new Firestore structure securely and safely without breaking legacy orders.
*   **Phase 3: Image Storage Standardization.** Shifting from `gs://` (if used previously) or poor path structures to clean download URLs is a simple mapping update in the Android client UI layer.

### ⚠️ Needs Careful Migration (Medium Risk)
*   **Phase 4: Order & Checkout Migration.** Moving from `/users/{uid}/orders/` to a root `/orders/` collection requires writing an admin data migration script to ensure historical transactions aren't stranded or lost. 
*   **Phase 5: Timestamp Migration.** Converting legacy `Long` millisecond fields to Firestore `Timestamp` objects requires app-side mapping `(val timestamp: Any?)` during parsing to avoid outright crashes on outdated clients while the database transforms.

---

## D. Architecture Conclusion

#### Final Approved Schema Choices
1. **Carts:** Nested as a single document at `/users/{uid}/cart/active`.
2. **Reviews:** Nested as subcollections at `/products/{productId}/reviews/{reviewId}`.
3. **Images:** Always absolute `https://` Google Storage Download URLs.
4. **Timestamps:** Native Firestore `Timestamp` (using `FieldValue.serverTimestamp()`).
5. **Collection Strategy:** Arrays for items (`Order.items`, `Cart.items`) to drastically minimize Firestore read costs. The core `/orders` collection lives at the root for comprehensive admin queries.

#### Safe First Implementation Phase
**The Persistent Cart & Catalog Update.** 
We can immediately convert the Android codebase's `Cart` management to use the `/users/{uid}/cart/active` Firestore document, updating Coil/UI components to confidently expect `https://` urls, and mapping the new `Product` and `Category` data classes. This phase is low consequence as it doesn't touch fulfilled orders.

#### Highest-Risk Migration Phase
**The Order Relocation (Moving to Root).** 
Migrating away from the deeply nested `/users/{uid}/orders` structure to the root `/orders` collection is the most critical and highest-risk operation. If the data migration fails midway, users will lose visibility of past orders. This requires implementing a robust "Backfill Script" using `FirebaseAdmin` (Node.js/Python) or a highly controlled Android Admin panel function to rewrite the records seamlessly.
