# Migration — Vendor & Product Approval Status

**Phase 0 deliverable, no runtime migration yet.** This document records the
target schema, backfill rules, and rollback strategy for the rebuild's role +
status taxonomy. The code-side enums and field-name constants land in
[`UserRoles.kt`](../app/src/main/java/isim/ia2y/myapplication/UserRoles.kt).
The Firestore writes that depend on these fields ship in Phase 1 (Vendor
Home) and Phase 5 (Admin Vendors + Moderation).

---

## 1. Existing schema (today)

- **Users** (`users/{uid}`): legacy field `role` with values `admin`,
  `vendeur`, `client`. No vendor lifecycle state.
- **Products** (`products/{productId}`): no approval state — every product
  is implicitly approved on write.

## 2. New fields

### Users — vendor lifecycle

| Field | Type | Notes |
|---|---|---|
| `vendorStatus` | string | One of `pending` / `approved` / `suspended` / `rejected`. Only meaningful when `role == "vendeur"`. |
| `vendorApplicationSubmittedAt` | timestamp | Set on submit. |
| `vendorApprovedAt` | timestamp | Set on admin approval. |
| `vendorSuspendedReason` | string? | Optional reason string. |
| `shopName`, `shopBio`, `shopBannerUrl`, `shopLogoUrl` | string | Shop branding. |
| `shopOperatingHours` | map | Per-day open/close. |
| `shippingFeeDt` | number | Default shipping in DT. |
| `deliveryZones` | array<string> | Zone names this vendor delivers to. |

### Products — moderation lifecycle

| Field | Type | Notes |
|---|---|---|
| `approvalStatus` | string | `draft` / `pending` / `approved` / `rejected` / `archived`. |
| `approvalReviewedAt` | timestamp | Last admin review. |
| `approvalReviewedBy` | string | Admin uid. |
| `approvalRejectionReason` | string? | Optional. |
| `featured` | boolean | Featured on home. |
| `featuredPriority` | number | Sort within featured. |
| `reportedCount` | number | Increment when client reports. |
| `archivedAt` | timestamp? | Set when vendor archives. |

## 3. Backfill rules (non-destructive)

No batch write is required to start reading these fields. The Kotlin
enum companions resolve **missing values as approved**:

- `VendorStatus.fromWire(null)` → `VendorStatus.APPROVED`
- `ProductApprovalStatus.fromWire(null)` → `ProductApprovalStatus.APPROVED`

That means every existing vendor and product is treated as already approved
the moment the new code ships — no behaviour change for clients, no
downtime, no risk of orders dropping.

A one-shot Cloud Function (or admin-only debug button reusing the seed
helper) will *optionally* materialize the defaults so admin queries can
filter on the field. That backfill is not required to ship Phase 1.

## 4. Rollback

If the rebuild needs to be reverted before Firestore writes ship:

1. Revert the Kotlin enums (`UserRoles.kt`) — the field names are no
   longer read.
2. Existing user / product docs are untouched (no destructive writes were
   ever made in Phase 0).

If a rollback is needed *after* Phase 5 starts writing the fields:

1. Stop writes (deploy revert).
2. Existing fields stay on docs but are ignored — they are additive only.
3. No data loss; clients keep working because the legacy code path doesn't
   read the new fields.

## 5. Rules / security note

When Phase 5 lands, Firestore rules must:

- Allow vendors to write only their own `shop*` fields and `approvalStatus`
  transitions `draft <-> pending` on their own products.
- Restrict `vendorStatus`, `approvalStatus` → `approved`/`rejected`,
  `featured`, `featuredPriority`, `reportedCount` to admin custom claim only.
- Reject any client write to `users/{uid}` that mutates `role`,
  `vendorStatus`, or admin-only fields.

These rules are out of scope for Phase 0; the contract is recorded here so
the security-rules PR in Phase 5 has a checklist.
