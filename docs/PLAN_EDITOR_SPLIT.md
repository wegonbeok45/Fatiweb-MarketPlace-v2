# Plan — Product Editor Split (Phase 2c)

[`AdminProductEditorActivity`](../app/src/main/java/isim/ia2y/myapplication/AdminProductEditorActivity.kt)
is **1,401 LOC** today and serves both admin and vendor flows via an
`EXTRA_SELLER_MODE` boolean. Per the rebuild plan, this gets split into:

```
AdminProductEditorActivity (container, ~250 LOC)
VendorProductEditorActivity (container, ~250 LOC)
   └── both host →  ProductFormFragment  (the shared form, ~700 LOC)
                       ├── ProductImagePicker          (~200 LOC)
                       ├── ProductCategoryPicker       (~80 LOC)
                       └── ProductAutofillController   (~250 LOC, AI/Gemini)
```

The Fragment owns the form's view state. The two Activities are thin
shells: top bar, role gating, result handling, and (for admin only) the
extra "edit any vendor's product" affordance.

## Method-by-method routing

Source line numbers refer to the current monolithic activity.

| Method | LOC | Target |
|---|---:|---|
| `verifyProductManagerAccess` | 30 | Activity (role gate differs per container) |
| `restoreState`, `restoreImages` | 22 | Fragment (`onCreateView` / `onSaveInstanceState`) |
| `setupWindowInsets` | 13 | Activity |
| `setupTopBar` | 5 | Activity (title + back differ per container) |
| `setupCategoryDropdown` | 16 | `ProductCategoryPicker` |
| `bindExistingProduct` | 37 | Fragment |
| `loadExistingProduct` | 22 | Fragment |
| `bindActions` | 26 | Fragment (Save / AI / Add Photo buttons) |
| `bindImagePreviewRefresh` | 13 | `ProductImagePicker` |
| `fillRandomProductDraft` | 38 | `ProductAutofillController` |
| `generateProductInfoFromImage` | 25 | `ProductAutofillController` |
| `applyGeneratedProductInfo` | 55 | `ProductAutofillController` |
| `compressImageForGemini` | 18 | `ProductAutofillController` |
| `calculateImageSampleSize` | 12 | `ProductAutofillController` |
| `renderImagePreview` | 22 | `ProductImagePicker` |
| `launchCameraCapture` | 25 | `ProductImagePicker` |
| `createCameraImageUri` | 6 | `ProductImagePicker` |
| `deleteCachedCameraImage` | 8 | `ProductImagePicker` |
| `renderImageStrip` | 10 | `ProductImagePicker` |
| `buildImageThumb` | 51 | `ProductImagePicker` |
| `addSelectedImages` | 17 | `ProductImagePicker` |
| `promoteImageToCover` | 7 | `ProductImagePicker` |
| `removeImageAt` | 10 | `ProductImagePicker` |
| `resetEditorImagesFromProduct` | 6 | `ProductImagePicker` |
| `clearErrors` | 15 | Fragment |
| `saveProduct` | **228** | Fragment, but the **228-LOC method needs an internal extract** first: split into `validate()` / `buildDraft()` / `submit()` (~70 LOC each) before moving. Currently does validation, image upload prep, ID generation, save, navigation in one body. |
| `setSavingState` | 25 | Fragment |
| `setSaveProgress` | 4 | Fragment |
| `setGeneratingProductInfoState` | 24 | `ProductAutofillController` |
| `handleCloseRequest` | 15 | Activity (calls `fragment.hasUnsavedChanges()`) |
| `hasUnsavedChanges` | 2 | Fragment (exposed) |
| `captureFormSignature` | 18 | Fragment |
| `Product.primaryRemoteImageUrl` / `orderedRemoteImageUrls` | 10 | `ProductImagePicker` |
| `EditorImage.source` | 5 | `ProductImagePicker` |
| `verifiedRemoteImageUrl` | 8 | `ProductImagePicker` (also extract to top-level helper in `ImageLoading.kt` to avoid duplication w/ other callers) |
| `currentDraftTitle` / `currentDraftCategory` | 19 | Fragment |
| `friendlySaveError` | 35 | Fragment |
| `friendlyProductGenerationError` | 20 | `ProductAutofillController` |
| `showDebugSaveError` | 27 | Fragment (debug-only) |
| `scrollToError` | 10 | Fragment |
| `buildFallbackDescription` | 13 | `ProductAutofillController` |
| `buildFallbackBullets` | 24 | `ProductAutofillController` |
| `createUniqueProductId` | 12 | Fragment |
| `resolveCategoryKey` / `categoryLabel` | 13 | `ProductCategoryPicker` |
| `randomTemplateFor` | 146 | `ProductAutofillController` (pure data; consider moving to a separate resource file or asset JSON) |
| `randomPriceFor` | 10 | `ProductAutofillController` |
| `formatGeneratedNumber` / `formatSuggestedPrice` | 8 | `ProductAutofillController` |
| `generateSearchKeywords` | 30 | Fragment |

## Differences between admin and vendor containers

| Concern | Admin | Vendor |
|---|---|---|
| Role gate | `requireAdminRole()` | `requireAdminOrVendeurRole()` then assert `role == VENDEUR` |
| Edit any product | yes (`canEdit` always true) | only own (`product.sellerId == uid`) |
| `sellerId` on save | preserves existing or picks from a vendor selector | always `currentUser.uid` |
| Admin moderation fields | exposed (`approvalStatus`, `featured`, `featuredPriority`) | hidden; vendor's own publish toggles `status` only |
| Title bar | "Modifier produit" / "Nouveau produit" | "Mon produit" / "Nouveau produit" |
| AI autofill | enabled | enabled (gated on Gemini quota) |
| Theme | `Theme.MyApplication.Admin` | `Theme.MyApplication.Admin` (kept; could fork to a Vendor theme later) |

## Migration order (smallest-blast-radius first)

1. **Extract `ProductImagePicker`** as a `Fragment` first — it has the
   cleanest boundary (24 image-related methods, ~200 LOC). Verify both
   admin and vendor still save correctly.
2. **Extract `ProductCategoryPicker`** — 3 methods, ~80 LOC. Trivial.
3. **Extract `ProductAutofillController`** as a plain `LifecycleObserver`
   class (not a fragment — no UI of its own; mounts buttons + dialogs).
4. **Refactor `saveProduct` in place** into `validate / buildDraft /
   submit` — pure internal split, no Fragment yet. Verify both flows.
5. **Extract `ProductFormFragment`** containing the now-slimmer activity
   minus `setupTopBar` / `setupWindowInsets` / `handleCloseRequest` /
   role gate.
6. **Create `VendorProductEditorActivity`** — 1-screen container hosting
   `ProductFormFragment`. Add to `AndroidManifest`. Update
   `VendorProductsActivity.openEditor()` to launch it instead.
7. **Slim `AdminProductEditorActivity`** to its own container.
8. **Remove `EXTRA_SELLER_MODE`** boolean — the activity choice now
   encodes the role.

## Verification per step

- Lint pass after every step.
- Manual smoke: open editor, type a title, save → check Firestore doc
  shape matches before.
- For the autofill controller extract: re-test "fill random" + "generate
  from image" flows since they hit Gemini.
- For image picker: test camera capture, gallery multi-select, drag to
  reorder, delete, set-as-cover. Don't ship without these.

## What this plan does NOT include

- Variants (size, color) — not in the current editor; if Phase 4
  introduces them, they sit inside `ProductFormFragment` as a new sub-
  fragment `ProductVariantsPicker`.
- Bulk operations — out of scope; Phase 2's row overflow menu covers
  per-product lifecycle.
- Approval-status writes from the vendor side — once Phase 5 admin
  moderation ships, `VendorProductLifecycle.publish` should set
  `approvalStatus = "pending"` instead of preserving the existing value
  (see [VendorProductLifecycle.kt](../app/src/main/java/isim/ia2y/myapplication/VendorProductLifecycle.kt) lines 26-31).

## Estimated effort

- Steps 1-4: one focused session (~3-4 hours of careful refactor with
  verification between each step).
- Steps 5-7: one focused session (~2-3 hours).
- Step 8: 15 minutes.

**Total: two focused sessions.** Not safe to bundle into a single round
without manual verification between each step — the editor is a
load-bearing piece of the vendor experience.
