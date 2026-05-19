export const SCHEMA_VERSION = 2;

export const COLLECTIONS = {
  USERS: "users",
  PRODUCTS: "products",
  ORDERS: "orders",
  CARTS_LEGACY: "carts",
  CONFIG: "config",
  COMMERCE: "commerce",
  IN_APP_NOTIFICATIONS: "in_app_notifications",
  ADMIN_STATS: "admin_stats",
  ASSISTANT_RATE_LIMITS: "assistant_rate_limits",
  CONVERSATIONS: "conversations",
  CONVERSATION_RATE_LIMITS: "conversation_rate_limits",
} as const;

export const USER_SUBCOLLECTIONS = {
  ADDRESSES: "addresses",
  FAVORITES: "favorites",
  CART: "cart",
  ORDERS: "orders",
  INBOX: "inbox",
  ORDER_REQUESTS: "order_requests",
  ASSISTANT_THREADS: "assistant_threads",
  NOTIFICATION_READS: "notification_reads",
  BLOCKED_USERS: "blockedUsers",
} as const;

export const USER_ROLES = {
  ADMIN: "admin",
  VENDEUR: "vendeur",
  CLIENT: "client",
} as const;

export const PRODUCT_STATUSES = ["draft", "published", "archived"] as const;
export type ProductStatus = (typeof PRODUCT_STATUSES)[number];

export const ORDER_STATUSES = [
  "pending",
  "confirmed",
  "preparing",
  "shipped",
  "delivered",
  "cancelled",
] as const;
export type OrderStatus = (typeof ORDER_STATUSES)[number];

export const DEFAULTS = {
  currency: "TND",
  standardShippingFee: 7.0,
  expressShippingFee: 12.5,
  assistantCooldownMs: 3000,
  assistantMaxTurns: 12,
} as const;
