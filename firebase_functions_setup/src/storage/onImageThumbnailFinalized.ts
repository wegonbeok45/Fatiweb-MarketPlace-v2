import {logger} from "firebase-functions";
import {onObjectFinalized} from "firebase-functions/v2/storage";
import {COLLECTIONS} from "../shared/constants";
import {db, storage} from "../shared/firestore";

const THUMBNAIL_DIR = "/thumbnails/";
const RESIZED_SUFFIX = /_(200x200|400x400)(\.[^.]+)$/i;

export const onImageThumbnailFinalized = onObjectFinalized(async (event) => {
  const object = event.data;
  const thumbnailPath = object.name || "";
  const bucketName = object.bucket || "";
  if (!bucketName || !isThumbnailPath(thumbnailPath)) return;

  const originalPath = originalPathFromThumbnail(thumbnailPath);
  if (!originalPath) return;

  const bucket = storage.bucket(bucketName);
  const [originalUrl, thumbnailUrl] = await Promise.all([
    downloadUrlForObject(bucketName, originalPath),
    downloadUrlForObject(bucketName, thumbnailPath),
  ]);
  if (!originalUrl || !thumbnailUrl) {
    logger.warn("Thumbnail URL sync skipped because a download token was missing", {
      originalPath,
      thumbnailPath,
    });
    return;
  }

  if (thumbnailPath.startsWith("product_images/")) {
    await updateProductThumbnailUrls(originalUrl, thumbnailUrl);
    return;
  }

  if (thumbnailPath.startsWith("user_avatars/")) {
    await updateUserAvatarThumbnailUrls(originalUrl, thumbnailUrl);
    return;
  }

  // Keep bucket variable referenced for type/lint friendliness if paths expand later.
  void bucket;
});

function isThumbnailPath(path: string): boolean {
  return path.includes(THUMBNAIL_DIR) && RESIZED_SUFFIX.test(path);
}

function originalPathFromThumbnail(thumbnailPath: string): string | null {
  const withoutThumbnailDir = thumbnailPath.replace(THUMBNAIL_DIR, "/");
  const originalPath = withoutThumbnailDir.replace(RESIZED_SUFFIX, "$2");
  return originalPath === thumbnailPath ? null : originalPath;
}

async function downloadUrlForObject(
  bucketName: string,
  objectPath: string,
): Promise<string | null> {
  const file = storage.bucket(bucketName).file(objectPath);
  const [metadata] = await file.getMetadata();
  const token = firstDownloadToken(metadata.metadata?.firebaseStorageDownloadTokens);
  if (!token) return null;

  return `https://firebasestorage.googleapis.com/v0/b/${encodeURIComponent(bucketName)}/o/${encodeURIComponent(objectPath)}?alt=media&token=${encodeURIComponent(token)}`;
}

function firstDownloadToken(value: unknown): string | null {
  return typeof value === "string" ?
    value.split(",").map((token) => token.trim()).find(Boolean) || null :
    null;
}

async function updateProductThumbnailUrls(
  originalUrl: string,
  thumbnailUrl: string,
): Promise<void> {
  const productByPrimary = await db.collection(COLLECTIONS.PRODUCTS)
    .where("imageUrl", "==", originalUrl)
    .limit(10)
    .get();
  const productsByGallery = await db.collection(COLLECTIONS.PRODUCTS)
    .where("imageUrls", "array-contains", originalUrl)
    .limit(10)
    .get();

  const docs = new Map<string, FirebaseFirestore.QueryDocumentSnapshot>();
  productByPrimary.docs.forEach((doc) => docs.set(doc.id, doc));
  productsByGallery.docs.forEach((doc) => docs.set(doc.id, doc));
  if (docs.size === 0) return;

  const batch = db.batch();
  docs.forEach((doc) => {
    const imageUrls = asStringArray(doc.get("imageUrls"));
    const thumbnailUrls = asStringArray(doc.get("thumbnailUrls"));
    const imageIndex = imageUrls.indexOf(originalUrl);
    const nextThumbnailUrls = thumbnailUrls.slice();

    if (imageIndex >= 0) {
      while (nextThumbnailUrls.length < imageUrls.length) nextThumbnailUrls.push("");
      nextThumbnailUrls[imageIndex] = thumbnailUrl;
    } else if (nextThumbnailUrls.length === 0) {
      nextThumbnailUrls.push(thumbnailUrl);
    }

    batch.set(doc.ref, {
      thumbnailUrl: doc.get("imageUrl") === originalUrl || imageIndex <= 0 ?
        thumbnailUrl :
        (doc.get("thumbnailUrl") || thumbnailUrl),
      thumbnailUrls: nextThumbnailUrls.filter((url) => url.length > 0),
      thumbnailUpdatedAt: new Date(),
    }, {merge: true});
  });
  await batch.commit();
}

async function updateUserAvatarThumbnailUrls(
  originalUrl: string,
  thumbnailUrl: string,
): Promise<void> {
  const byAvatarUrl = await db.collection(COLLECTIONS.USERS)
    .where("avatarUrl", "==", originalUrl)
    .limit(10)
    .get();
  const byAvatar = await db.collection(COLLECTIONS.USERS)
    .where("avatar", "==", originalUrl)
    .limit(10)
    .get();

  const docs = new Map<string, FirebaseFirestore.QueryDocumentSnapshot>();
  byAvatarUrl.docs.forEach((doc) => docs.set(doc.id, doc));
  byAvatar.docs.forEach((doc) => docs.set(doc.id, doc));
  if (docs.size === 0) return;

  const batch = db.batch();
  docs.forEach((doc) => {
    batch.set(doc.ref, {
      avatarThumbnailUrl: thumbnailUrl,
      avatarThumbUrl: thumbnailUrl,
      avatarThumbnailUpdatedAt: new Date(),
    }, {merge: true});
  });
  await batch.commit();
}

function asStringArray(value: unknown): string[] {
  return Array.isArray(value) ?
    value.filter((item): item is string => typeof item === "string") :
    [];
}
