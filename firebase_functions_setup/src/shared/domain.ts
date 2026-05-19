import {
  DEFAULTS,
  ORDER_STATUSES,
  type OrderStatus,
  PRODUCT_STATUSES,
  type ProductStatus,
} from "./constants";

const orderTransitionMap: Record<OrderStatus, readonly OrderStatus[]> = {
  pending: ["pending", "confirmed", "cancelled"],
  confirmed: ["confirmed", "preparing", "cancelled"],
  preparing: ["preparing", "shipped", "cancelled"],
  shipped: ["shipped", "delivered", "cancelled"],
  delivered: ["delivered"],
  cancelled: ["cancelled"],
};

export function asRecord(value: unknown): Record<string, unknown> | null {
  if (!value || typeof value !== "object" || Array.isArray(value)) {
    return null;
  }
  return value as Record<string, unknown>;
}

export function asString(value: unknown, fallback = ""): string {
  return typeof value === "string" ? value : fallback;
}

export function asTrimmedString(value: unknown, fallback = ""): string {
  return asString(value, fallback).trim();
}

export function asNumber(value: unknown, fallback = 0): number {
  return typeof value === "number" && Number.isFinite(value) ? value : fallback;
}

export function asBoolean(value: unknown, fallback = false): boolean {
  return typeof value === "boolean" ? value : fallback;
}

export function asStringArray(value: unknown): string[] {
  if (!Array.isArray(value)) {
    return [];
  }
  return value
    .map((item) => asTrimmedString(item))
    .filter((item) => item.length > 0);
}

export function asMillis(value: unknown): number {
  if (typeof value === "number" && Number.isFinite(value)) {
    return value;
  }
  if (value instanceof Date) {
    return value.getTime();
  }
  if (value && typeof value === "object") {
    const candidate = value as {toMillis?: () => number; seconds?: unknown};
    if (typeof candidate.toMillis === "function") {
      return candidate.toMillis();
    }
    if (typeof candidate.seconds === "number") {
      return candidate.seconds * 1000;
    }
  }
  return 0;
}

export function toMinorUnits(amount: number): number {
  return Math.round(amount * 1000);
}

export function fromMinorUnits(amountMinor: number): number {
  return amountMinor / 1000;
}

export function normalizeCurrency(value: unknown): string {
  const normalized = asTrimmedString(value, DEFAULTS.currency).toUpperCase();
  return normalized || DEFAULTS.currency;
}

export function normalizeProductStatus(value: unknown): ProductStatus {
  const normalized = asTrimmedString(value, "published").toLowerCase();
  return (PRODUCT_STATUSES as readonly string[]).includes(normalized) ?
    normalized as ProductStatus :
    "published";
}

export function normalizeOrderStatus(value: unknown): OrderStatus {
  const normalized = asTrimmedString(value, "pending").toLowerCase();
  if (normalized === "processing") {
    return "preparing";
  }
  if (["failed", "returned", "refunded"].includes(normalized)) {
    return "cancelled";
  }
  return (ORDER_STATUSES as readonly string[]).includes(normalized) ?
    normalized as OrderStatus :
    "pending";
}

export function canTransitionOrderStatus(
  currentStatus: OrderStatus,
  nextStatus: OrderStatus,
): boolean {
  return currentStatus === nextStatus || orderTransitionMap[currentStatus].includes(nextStatus);
}

export function appendOrderTrackingEvent(
  existing: unknown,
  nextStatus: OrderStatus,
  changedAt: number,
): Array<{status: OrderStatus; changedAt: number}> {
  const normalized = Array.isArray(existing) ?
    existing
      .map((entry) => asRecord(entry))
      .filter((entry): entry is Record<string, unknown> => entry !== null)
      .map((entry) => ({
        status: normalizeOrderStatus(entry.status),
        changedAt: asMillis(entry.changedAt),
      }))
      .filter((entry) => entry.changedAt > 0) :
    [];

  const deduped = normalized.filter((entry) => entry.status !== nextStatus);
  return [...deduped, {status: nextStatus, changedAt}]
    .sort((left, right) => left.changedAt - right.changedAt);
}

export function generateSearchKeywords(...values: Array<string | string[]>): string[] {
  const tokens = values
    .flatMap((value) => Array.isArray(value) ? value : [value])
    .flatMap((value) => value.toLowerCase()
      .normalize("NFD")
      .replace(/[\u0300-\u036f]/g, "")
      .split(/[^a-z0-9\u0600-\u06ff]+/i))
    .map((value) => value.trim())
    .filter((value) => value.length >= 3);
  const expanded = tokens.flatMap((token) => {
    const prefixes: string[] = [token];
    for (let length = 3; length < token.length; length += 1) {
      prefixes.push(token.slice(0, length));
    }
    return prefixes;
  });
  return Array.from(new Set(expanded));
}

export function chunk<T>(items: T[], size: number): T[][] {
  const chunks: T[][] = [];
  for (let index = 0; index < items.length; index += size) {
    chunks.push(items.slice(index, index + size));
  }
  return chunks;
}
