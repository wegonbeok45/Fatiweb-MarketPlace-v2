# FatiWeb Market - Full Attribute Map

This file documents the current data shape used across the app:
- Firestore collections/documents
- Kotlin models and their fields
- SharedPreferences keys
- Intent extras/routes
- Key runtime state attributes

## 1) Firestore Data Map

### Collection: `products/{productId}`
Source: `FirestoreService.seedProducts()`, `FirestoreService.fetchAdminProducts()`, `ProductRepository.kt`

| Field | Type | Required | Notes |
|---|---|---:|---|
| `id` | `String` | Yes (in seed map) | Often same as document ID |
| `title` | `String` | Yes | Product name |
| `subtitle` | `String` | No | Short marketing line |
| `price` | `Number(Double)` | Yes | Displayed as `DT` |
| `rating` | `Number(Double)` | No | For product UI |
| `reviewsCount` | `Number(Int)` | No | For product UI |
| `tags` | `Array<String>` | No | Search + chips |
| `description` | `String` | No | Details screen body |
| `bullets` | `Array<String>` | No | Details screen bullet points |
| `stock` | `Number(Int)` | Optional | Read by admin fetch if present |

Notes:
- `imageRes` is local-only and not stored in Firestore.

### Collection: `users/{uid}`
Source: `FirestoreService.saveUserProfile()`, `fetchUserName()`, `fetchUserRole()`

| Field | Type | Required | Notes |
|---|---|---:|---|
| `uid` | `String` | Yes (on profile save) | User ID |
| `name` | `String` | Yes | Display name |
| `email` | `String` | Yes | Account email |
| `createdAt` | `Long` | Yes | Epoch millis |
| `role` | `String` | Optional | Admin logic expects `"admin"` when admin |

### Subcollection: `users/{uid}/orders/{orderId}`
Source: `AppOrder.toMap()`, `FirestoreService.saveOrder()`

| Field | Type | Required | Notes |
|---|---|---:|---|
| `id` | `String` | Yes | Order doc ID |
| `items` | `Map<String, Int>` | Yes | `productId -> qty` |
| `subtotal` | `Number(Double)` | Yes | Items subtotal |
| `shippingFee` | `Number(Double)` | Yes | Delivery fee |
| `total` | `Number(Double)` | Yes | Final amount |
| `deliveryType` | `String` | Yes | `"standard"` or `"express"` |
| `paymentMethod` | `String` | Yes | `"card"`, `"edinar"`, `"cash"` |
| `status` | `String` | Yes | `"pending"`, `"preparing"`, `"shipped"`, `"delivered"` |
| `createdAt` | `Long` | Yes | Epoch millis |

## 2) Kotlin Models and Attributes

### `Product`
Source: `ProductRepository.kt`

| Field | Type |
|---|---|
| `id` | `String` |
| `title` | `String` |
| `subtitle` | `String` |
| `price` | `Double` |
| `rating` | `Double` |
| `reviewsCount` | `Int` |
| `tags` | `List<String>` |
| `description` | `String` |
| `bullets` | `List<String>` |
| `imageRes` | `Int` (`@DrawableRes`) |

Computed:
- `unitPrice: Double`
- `tag: String` (first tag)
- `searchableText: String`

### `AppOrder`
Source: `AppOrder.kt`

| Field | Type |
|---|---|
| `id` | `String` |
| `items` | `Map<String, Int>` |
| `subtotal` | `Double` |
| `shippingFee` | `Double` |
| `total` | `Double` |
| `deliveryType` | `String` |
| `paymentMethod` | `String` |
| `status` | `String` |
| `createdAt` | `Long` |

Computed:
- `formattedDate: String`
- `displayId: String`
- `statusLabel: String`

### `AppNotification`
Source: `NotificationStore.kt`

| Field | Type |
|---|---|
| `id` | `String` |
| `title` | `String` |
| `message` | `String` |
| `timestamp` | `Long` |
| `isRead` | `Boolean` |

### Admin DTOs from `FirestoreService`

#### `ClientInfo`
| Field | Type |
|---|---|
| `uid` | `String` |
| `name` | `String` |
| `email` | `String` |
| `orderCount` | `Int` |
| `createdAt` | `Long` |

#### `AdminProductItem`
| Field | Type |
|---|---|
| `id` | `String` |
| `title` | `String` |
| `subtitle` | `String` |
| `price` | `Double` |
| `stock` | `Int?` |

#### `AdminStats`
| Field | Type |
|---|---|
| `totalOrders` | `Int` |
| `totalRevenue` | `Double` |
| `totalClients` | `Int` |
| `totalProducts` | `Int` |

## 3) Local Storage (SharedPreferences) Map

Per-user stores are prefixed with current uid: `${uid}_...` (fallback uid: `"guest"`).

### `${uid}_cart_store`
Source: `CartStore.kt`

| Key | Type | Meaning |
|---|---|---|
| `cart_qty` | `StringSet` | Encoded rows: `"productId:qty"` |

### `${uid}_favoris_store`
Source: `FavoritesStore.kt`

| Key | Type | Meaning |
|---|---|---|
| `liked_ids` | `StringSet` | Favorite product IDs |

### `${uid}_address_book_store`
Source: `AddressBookStore.kt`

| Key | Type | Meaning |
|---|---|---|
| `addresses_csv` | `String` | JSON array of addresses (legacy split fallback `|||`) |

### `${uid}_fatiweb_notifications`
Source: `NotificationStore.kt`

| Key | Type | Meaning |
|---|---|---|
| `notifications_json` | `String` | JSON array of notification objects |
| `notification_first_launch` | `Boolean` | First-launch bootstrap flag |

### `profile_prefs`
Source: `ProfileTabFragment.kt`, `AdminUiActions.kt`

| Key | Type | Meaning |
|---|---|---|
| `avatar_uri` | `String` | Saved avatar file path / legacy file uri |

### `AppPrefs`
Source: `CheckoutDetailsActivity.kt`

| Key | Type | Meaning |
|---|---|---|
| `has_saved_card` | `Boolean` | Whether a card is marked saved |

### `app_language_prefs`
Source: `LanguageManager.kt`

| Key | Type | Meaning |
|---|---|---|
| `selected_language` | `String` | `"fr"` or `"en"` |

### `app_flow`
Source: `UiActions.kt`

| Key | Type | Meaning |
|---|---|---|
| `onboarding_completed` | `Boolean` | Onboarding completion state |

### `app_runtime_permissions`
Source: `UiActions.kt`

| Key | Type | Meaning |
|---|---|---|
| `notification_permission_requested` | `Boolean` | Whether prompt was already shown |
| `notification_permission_granted` | `Boolean` | Last known grant state |

### `search_screen_prefs`
Source: `SearchActivity.kt`

| Key | Type | Meaning |
|---|---|---|
| `recent_searches_csv` | `String` | JSON array of recent search terms |

### `settings_prefs`
Referenced in: `LoadingScreen.kt`, `TabDataPrefetcher.kt`  
Keys are not explicitly declared in current codebase (storage exists for settings screen flow/inspection).

## 4) Intent Extras and Route Attributes

### Main navigation
| Key | Type | Source |
|---|---|---|
| `open_main_tab` | `String` (`MainActivity.Tab.name`) | `MainActivity.EXTRA_OPEN_TAB` |

### Product details
| Key | Type | Source |
|---|---|---|
| `extra_product_id` | `String` | `ProductDetailsScreen` |
| Route pattern | `details/{productId}` | `ProductDetailsScreen.ROUTE_PATTERN` |

### Onboarding
| Key | Type | Source |
|---|---|---|
| `extra_from_onboarding` | `Boolean` | `Onboard3.EXTRA_FROM_ONBOARDING` |

### Admin navigation
| Key | Type | Source |
|---|---|---|
| `extra_admin_tab_switch` | `Boolean` | `AdminUiActions.kt` |
| `extra_admin_dashboard_tab` | `String` (`AdminNavTab.name`) | `AdminUiActions.kt` |

## 5) Screen Runtime State Attributes

### `MainActivity`
- `currentTab: MainActivity.Tab`
- `pendingTabSelection: Tab?`
- `pendingTabAnimate: Boolean`
- `isTabLoading: Boolean`
- `loadingErrorTab: Tab?`
- `tabLoadRequestToken: Int`
- `tabLoadingStartedAtMs: Long`

### `CheckoutDetailsActivity`
- `EXPRESS_FEE: Double = 12.500`
- `isStandardSelected: Boolean`
- `currentStep: Int` (1/2/3)
- `selectedPaymentMethod: PaymentMethod`
- `isUsingSavedCard: Boolean`

### `SearchActivity`
- `currentQuery: String`
- `currentSort: SortOption`
- `lastResults: List<Product>`
- `filterCategory: String` (`all`, `craft`, `food`, `fashion`)
- `filterLocation: String` (`all`, `medina`, `djerba`, `kairouan`)
- `filterMinPrice: Float`
- `filterMaxPrice: Float`
- `filterBioNaturel: Boolean`

### `AdminDashboardActivity`
- `activeTab: DashboardInlineTab` (`OVERVIEW`, `COMMANDES`, `CLIENTS`)
- `commandesContent: View?`
- `clientsContent: View?`

### `AdminSession`
- `verifiedAdminUid: String?`

## 6) Client-Centric Attribute View (what defines a client in this app)

A client/user currently spans:

### Identity/Auth
- Firebase Auth user (`uid`, `displayName`, `email`)

### Firestore profile (`users/{uid}`)
- `uid`, `name`, `email`, `createdAt`, optional `role`

### Commerce behavior
- Orders: `users/{uid}/orders/*`
- Favorites: `${uid}_favoris_store.liked_ids`
- Cart: `${uid}_cart_store.cart_qty`
- Addresses: `${uid}_address_book_store.addresses_csv`

### Experience prefs
- Language: `app_language_prefs.selected_language`
- Notifications cache: `${uid}_fatiweb_notifications.*`
- Avatar: `profile_prefs.avatar_uri`

## 7) Gaps / Optional Standardization

Current schema works, but for stronger consistency consider:
- Always persist `stock` in `products`.
- Add `status` (`active`/`archived`) to products.
- Add `updatedAt` to `products` and `users`.
- Add `phone` and structured address fields for checkout.
- Add `currency` on orders/products if multi-currency ever appears.

