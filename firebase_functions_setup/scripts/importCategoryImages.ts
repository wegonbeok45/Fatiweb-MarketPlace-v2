import crypto from "node:crypto";
import fs from "node:fs/promises";
import path from "node:path";
import admin from "firebase-admin";
import sharp from "sharp";
import type {CategorySeedFile, CategorySeedNode, ListingType, MarketplaceCategory} from "../src/catalog/categoryModels";

type FlatCategory = Omit<MarketplaceCategory, "createdAt" | "updatedAt"> & {
  topLevelName: string;
};

type UnsplashPhoto = {
  id: string;
  alt_description?: string | null;
  description?: string | null;
  urls: {raw: string; regular: string};
  links: {html: string; download_location: string};
  user: {name: string; links: {html: string}};
};

type ImportResult = {
  categoryId: string;
  name: string;
  query: string;
  localPath?: string;
  storagePath?: string;
  imageUrl?: string;
  author?: string;
  skipped?: string;
  uploaded?: boolean;
  firestoreUpdate?: FirestoreImageUpdate;
};

type ImageAttribution = {
  imageSource: string;
  imageAuthor: string;
  imageAuthorUrl: string;
  imageDownloadLocation: string;
  imageAttribution: string;
};

type CachedImage = {
  image: Buffer;
  localPath: string;
  attribution: ImageAttribution;
  source: "cache" | "unsplash" | "placeholder";
};

type FirestoreImageUpdate = {
  categoryId: string;
  data: FirebaseFirestore.UpdateData<FirebaseFirestore.DocumentData>;
};

const rawArgs = process.argv.slice(2);
const args = new Set(rawArgs);
const dryRun = args.has("--dry-run") || args.has("--dry");
const downloadOnly = args.has("--download-only");
const includeChildren = args.has("--include-children");
const force = args.has("--force");
const projectId = readArg("--project") || process.env.FIREBASE_PROJECT_ID || "fatiweb-marketplace";
const storageBucket = process.env.FIREBASE_STORAGE_BUCKET || `${projectId}.appspot.com`;
const accessKey = process.env.UNSPLASH_ACCESS_KEY || "";
const seedPath = path.resolve(process.cwd(), "data", "marketplace_categories.seed.json");
const outputDir = path.resolve(process.cwd(), "..", "categories");
const unsplashAppName = "FatiWeb";
const requestDelayMs = Number(readArg("--delay-ms") || process.env.UNSPLASH_DELAY_MS || 450);

const topLevelQueries: Record<string, string> = {
  "electronics": "premium electronics gadgets on neutral background minimal product photography",
  "fashion": "minimal fashion clothing accessories soft neutral ecommerce photography",
  "home-and-furniture": "modern home furniture neutral interior soft light ecommerce",
  "beauty-and-health": "premium beauty skincare wellness products neutral background",
  "sports-and-outdoors": "fitness outdoor sports gear clean neutral product photography",
  "automotive": "modern car detail automotive accessories clean premium photography",
  "real-estate": "bright modern home interior neutral real estate photography",
  "jobs-services": "professional workspace tools service business clean neutral photography",
  "baby-and-toys": "premium baby toys neutral nursery soft light product photography",
  "books-and-media": "books media music minimal neutral product photography",
  "food-and-grocery": "fresh grocery ingredients neutral background premium food photography",
  "pets": "premium pet accessories neutral background soft light photography",
  "business-and-industrial": "industrial business tools equipment clean neutral photography",
  "digital-products": "laptop digital products workspace minimal neutral photography",
  "collectibles-and-hobbies": "collectibles hobbies handmade objects neutral product photography",
};

function readArg(name: string): string | undefined {
  const exact = rawArgs.find((arg) => arg.startsWith(`${name}=`));
  if (exact) return exact.split("=").slice(1).join("=");
  const index = rawArgs.indexOf(name);
  return index >= 0 ? rawArgs[index + 1] : undefined;
}

function slugify(value: string): string {
  return value
    .normalize("NFD")
    .replace(/[\u0300-\u036f]/g, "")
    .toLowerCase()
    .replace(/&/g, " and ")
    .replace(/\+/g, " plus ")
    .replace(/[^a-z0-9]+/g, "-")
    .replace(/^-+|-+$/g, "");
}

function keywordsFor(...values: string[]): string[] {
  return Array.from(new Set(
    values
      .flatMap((value) => value.toLowerCase().replace(/&|\+/g, " ").split(/[\s/_-]+/g))
      .map((value) => value.replace(/^'+|'+$/g, "").trim())
      .filter((value) => value.length >= 3),
  ));
}

function placeholderUrl(name: string): string {
  return `https://placehold.co/640x420/F7F9FA/74613F?text=${slugify(name).replace(/-/g, "+")}`;
}

function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

async function fileExists(filePath: string): Promise<boolean> {
  try {
    await fs.access(filePath);
    return true;
  } catch {
    return false;
  }
}

function flattenNode(
  node: CategorySeedNode,
  index: number,
  parent: FlatCategory | null,
  ancestors: string[],
  inheritedListingTypes: ListingType[],
  topLevelName?: string,
): FlatCategory[] {
  const slug = slugify(node.name);
  const id = parent ? `${parent.id}__${slug}` : slug;
  const resolvedTopLevelName = topLevelName || node.name;
  const listingTypes = node.listingTypes?.length ? node.listingTypes : inheritedListingTypes;
  const category: FlatCategory = {
    id,
    name: node.name,
    slug,
    icon: node.icon || parent?.icon || "category",
    imageUrl: placeholderUrl(node.name),
    parentCategory: parent?.id || null,
    ancestorIds: ancestors,
    level: ancestors.length as 0 | 1 | 2,
    featured: node.featured === true && ancestors.length === 0,
    sortOrder: (parent?.sortOrder || 0) + index + 1,
    searchKeywords: Array.from(new Set([...(node.searchKeywords || []), ...keywordsFor(...ancestors, node.name)])),
    listingTypes,
    isActive: true,
    topLevelName: resolvedTopLevelName,
  };

  return [
    category,
    ...(node.children || []).flatMap((child, childIndex) =>
      flattenNode(child, childIndex, category, [...ancestors, category.id], listingTypes, resolvedTopLevelName),
    ),
  ];
}

async function loadCategories(): Promise<FlatCategory[]> {
  const raw = JSON.parse(await fs.readFile(seedPath, "utf8")) as CategorySeedFile;
  return raw.categories
    .flatMap((category, index) => flattenNode(category, index * 100, null, [], category.listingTypes || ["product"]))
    .filter((category) => includeChildren || category.level <= 1);
}

function queryFor(category: FlatCategory): string {
  if (category.level === 0) {
    return topLevelQueries[category.id] || `${category.name} premium ecommerce neutral product photography`;
  }
  return `${category.name} ${category.topLevelName} premium ecommerce minimal neutral background soft light no text`;
}

async function unsplashJson<T>(url: string): Promise<T> {
  if (!accessKey) {
    throw new Error("UNSPLASH_ACCESS_KEY is required unless you run --dry-run.");
  }
  if (requestDelayMs > 0) {
    await sleep(requestDelayMs);
  }
  const response = await fetch(url, {
    headers: {
      Authorization: `Client-ID ${accessKey}`,
      "Accept-Version": "v1",
    },
  });
  if (!response.ok) {
    throw new Error(`Unsplash request failed ${response.status}: ${await response.text()}`);
  }
  return await response.json() as T;
}

async function findUnsplashPhoto(query: string): Promise<UnsplashPhoto> {
  const params = new URLSearchParams({
    query,
    orientation: "squarish",
    per_page: "12",
    content_filter: "high",
    order_by: "relevant",
  });
  const payload = await unsplashJson<{results: UnsplashPhoto[]}>(`https://api.unsplash.com/search/photos?${params}`);
  const candidates = payload.results.filter((photo) => {
    const text = `${photo.alt_description || ""} ${photo.description || ""}`.toLowerCase();
    return !["logo", "text", "watermark", "sign"].some((blocked) => text.includes(blocked));
  });
  const photo = candidates[0] || payload.results[0];
  if (!photo) {
    throw new Error(`No Unsplash image found for query: ${query}`);
  }
  return photo;
}

async function downloadUnsplashPhoto(photo: UnsplashPhoto): Promise<Buffer> {
  const tracked = await unsplashJson<{url: string}>(photo.links.download_location);
  const url = new URL(tracked.url || photo.urls.raw);
  url.searchParams.set("w", "1400");
  url.searchParams.set("h", "1400");
  url.searchParams.set("fit", "crop");
  url.searchParams.set("crop", "entropy");
  const response = await fetch(url);
  if (!response.ok) {
    throw new Error(`Image download failed ${response.status}: ${await response.text()}`);
  }
  return Buffer.from(await response.arrayBuffer());
}

async function optimizeImage(input: Buffer): Promise<Buffer> {
  return await sharp(input)
    .resize(1024, 1024, {fit: "cover", position: "centre"})
    .webp({quality: 82, effort: 4})
    .toBuffer();
}

async function saveLocal(category: FlatCategory, image: Buffer): Promise<string> {
  await fs.mkdir(outputDir, {recursive: true});
  const localPath = path.join(outputDir, `${category.id}.webp`);
  await fs.writeFile(localPath, image);
  return localPath;
}

function localImagePath(category: FlatCategory): string {
  return path.join(outputDir, `${category.id}.webp`);
}

function localMetadataPath(category: FlatCategory): string {
  return path.join(outputDir, `${category.id}.json`);
}

async function saveLocalMetadata(category: FlatCategory, attribution: ImageAttribution): Promise<void> {
  await fs.writeFile(localMetadataPath(category), `${JSON.stringify(attribution, null, 2)}\n`);
}

async function readLocalCache(category: FlatCategory): Promise<CachedImage | null> {
  const imagePath = localImagePath(category);
  if (!(await fileExists(imagePath))) return null;
  let attribution: ImageAttribution = {
    imageSource: "Local cache",
    imageAuthor: "Unknown",
    imageAuthorUrl: "",
    imageDownloadLocation: "",
    imageAttribution: "Cached category image",
  };
  const metadataPath = localMetadataPath(category);
  if (await fileExists(metadataPath)) {
    attribution = JSON.parse(await fs.readFile(metadataPath, "utf8")) as ImageAttribution;
  }
  return {
    image: await fs.readFile(imagePath),
    localPath: imagePath,
    attribution,
    source: "cache",
  };
}

async function createPlaceholderImage(category: FlatCategory): Promise<CachedImage> {
  const image = await sharp({
    create: {
      width: 1024,
      height: 1024,
      channels: 3,
      background: "#F7F5F0",
    },
  })
    .composite([{
      input: Buffer.from(`
        <svg width="1024" height="1024" xmlns="http://www.w3.org/2000/svg">
          <rect x="192" y="192" width="640" height="640" rx="96" fill="#FFFFFF"/>
          <circle cx="512" cy="512" r="132" fill="#DED8CC"/>
          <circle cx="512" cy="512" r="72" fill="#74613F" opacity="0.72"/>
        </svg>
      `),
      top: 0,
      left: 0,
    }])
    .webp({quality: 82, effort: 4})
    .toBuffer();
  const localPath = await saveLocal(category, image);
  const attribution: ImageAttribution = {
    imageSource: "FatiWeb placeholder",
    imageAuthor: "FatiWeb",
    imageAuthorUrl: "",
    imageDownloadLocation: "",
    imageAttribution: "FatiWeb category placeholder",
  };
  await saveLocalMetadata(category, attribution);
  return {image, localPath, attribution, source: "placeholder"};
}

function attributionForPhoto(photo: UnsplashPhoto): ImageAttribution {
  return {
    imageSource: "Unsplash",
    imageAuthor: photo.user.name,
    imageAuthorUrl: `${photo.user.links.html}?utm_source=${unsplashAppName}&utm_medium=referral`,
    imageDownloadLocation: photo.links.download_location,
    imageAttribution: `Photo by ${photo.user.name} on Unsplash`,
  };
}

async function getCategoryImage(category: FlatCategory, query: string): Promise<CachedImage> {
  const cached = await readLocalCache(category);
  if (cached && !force) return cached;
  try {
    const photo = await findUnsplashPhoto(query);
    const original = await downloadUnsplashPhoto(photo);
    const optimized = await optimizeImage(original);
    const localPath = await saveLocal(category, optimized);
    const attribution = attributionForPhoto(photo);
    await saveLocalMetadata(category, attribution);
    return {image: optimized, localPath, attribution, source: "unsplash"};
  } catch (error) {
    const message = error instanceof Error ? error.message : String(error);
    console.warn(`Unsplash fallback for ${category.id}: ${message}`);
    return await createPlaceholderImage(category);
  }
}

function firebaseDownloadUrl(bucketName: string, storagePath: string, token: string): string {
  return `https://firebasestorage.googleapis.com/v0/b/${bucketName}/o/${encodeURIComponent(storagePath)}?alt=media&token=${token}`;
}

async function existingStorageDownloadUrl(category: FlatCategory): Promise<string | null> {
  if (force) return null;
  const file = admin.storage().bucket(storageBucket).file(`category-images/${category.id}.webp`);
  const [exists] = await file.exists();
  if (!exists) return null;
  const [metadata] = await file.getMetadata();
  const token = metadata.metadata?.firebaseStorageDownloadTokens;
  if (typeof token !== "string" || token.trim().length === 0) return null;
  return firebaseDownloadUrl(storageBucket, file.name, token.split(",")[0]);
}

async function uploadImageAndBuildUpdate(category: FlatCategory, image: Buffer, attribution: ImageAttribution): Promise<{
  imageUrl: string;
  firestoreUpdate: FirestoreImageUpdate;
  uploaded: boolean;
}> {
  const bucket = admin.storage().bucket(storageBucket);
  const storagePath = `category-images/${category.id}.webp`;
  const existingUrl = await existingStorageDownloadUrl(category);
  if (existingUrl) {
    return {
      imageUrl: existingUrl,
      uploaded: false,
      firestoreUpdate: {
        categoryId: category.id,
        data: {
          imageUrl: existingUrl,
          ...attribution,
          updatedAt: admin.firestore.FieldValue.serverTimestamp(),
        },
      },
    };
  }
  const token = crypto.randomUUID();
  await bucket.file(storagePath).save(image, {
    resumable: false,
    contentType: "image/webp",
    metadata: {
      cacheControl: "public,max-age=31536000,immutable",
      metadata: {
        firebaseStorageDownloadTokens: token,
        imageSource: attribution.imageSource,
        imageAuthor: attribution.imageAuthor,
        imageAuthorUrl: attribution.imageAuthorUrl,
      },
    },
  });

  const imageUrl = firebaseDownloadUrl(bucket.name, storagePath, token);
  return {
    imageUrl,
    firestoreUpdate: {
      categoryId: category.id,
      data: {
        imageUrl,
        ...attribution,
        updatedAt: admin.firestore.FieldValue.serverTimestamp(),
      },
    },
    uploaded: true,
  };
}

function initFirebase(): void {
  if (admin.apps.length > 0) return;
  admin.initializeApp({
    projectId,
    storageBucket,
  });
}

async function importCategory(category: FlatCategory): Promise<ImportResult> {
  const query = queryFor(category);
  if (dryRun) {
    return {categoryId: category.id, name: category.name, query, skipped: "dry-run"};
  }
  const image = await getCategoryImage(category, query);

  if (downloadOnly) {
    return {
      categoryId: category.id,
      name: category.name,
      query,
      localPath: image.localPath,
      author: image.attribution.imageAuthor,
      skipped: "download-only",
    };
  }

  const upload = await uploadImageAndBuildUpdate(category, image.image, image.attribution);
  return {
    categoryId: category.id,
    name: category.name,
    query,
    localPath: image.localPath,
    storagePath: `category-images/${category.id}.webp`,
    imageUrl: upload.imageUrl,
    author: image.attribution.imageAuthor,
    uploaded: upload.uploaded,
    skipped: upload.uploaded ? undefined : "storage-exists",
    firestoreUpdate: upload.firestoreUpdate,
  };
}

async function commitFirestoreUpdates(updates: FirestoreImageUpdate[]): Promise<void> {
  const db = admin.firestore();
  for (let index = 0; index < updates.length; index += 450) {
    const batch = db.batch();
    const chunk = updates.slice(index, index + 450);
    for (const update of chunk) {
      batch.set(db.collection("marketplaceCategories").doc(update.categoryId), update.data, {merge: true});
    }
    await batch.commit();
    console.log(`Committed Firestore image metadata batch: ${index + 1}-${index + chunk.length}`);
  }
}

async function existingImageIds(categories: FlatCategory[]): Promise<Set<string>> {
  if (dryRun || downloadOnly || force) return new Set();
  initFirebase();
  const refs = categories.map((category) => admin.firestore().collection("marketplaceCategories").doc(category.id));
  const docs = await admin.firestore().getAll(...refs);
  return new Set(
    docs
      .filter((doc) => doc.exists && typeof doc.get("imageUrl") === "string" && doc.get("imageUrl").includes("firebasestorage.googleapis.com"))
      .map((doc) => doc.id),
  );
}

async function main(): Promise<void> {
  const categories = await loadCategories();
  const alreadyImported = await existingImageIds(categories);
  const targets = categories.filter((category) => !alreadyImported.has(category.id));
  console.log(`Category image import plan: ${targets.length}/${categories.length} targets, project=${projectId}, bucket=${storageBucket}`);
  console.log(`Mode: ${dryRun ? "dry-run" : downloadOnly ? "download-only" : "live upload + Firestore update"}`);
  console.table(targets.map((category) => ({
    id: category.id,
    name: category.name,
    level: category.level,
    query: queryFor(category),
  })));

  if (dryRun) return;
  if (!accessKey) throw new Error("UNSPLASH_ACCESS_KEY is required.");
  if (!downloadOnly) initFirebase();

  const results: ImportResult[] = [];
  for (const category of targets) {
    try {
      const result = await importCategory(category);
      results.push(result);
      console.log(`OK ${category.id}: ${result.storagePath || result.localPath}`);
    } catch (error) {
      const message = error instanceof Error ? error.message : String(error);
      results.push({categoryId: category.id, name: category.name, query: queryFor(category), skipped: message});
      console.error(`FAILED ${category.id}: ${message}`);
    }
  }

  const firestoreUpdates = results
    .map((result) => result.firestoreUpdate)
    .filter((update): update is FirestoreImageUpdate => Boolean(update));
  if (!downloadOnly && firestoreUpdates.length > 0) {
    await commitFirestoreUpdates(firestoreUpdates);
  }

  const uploaded = results.filter((result) => result.uploaded).length;
  const downloaded = results.filter((result) => result.localPath).length;
  const skipped = results.filter((result) => result.skipped).length;
  console.log(`Summary: downloaded=${downloaded}, uploaded=${uploaded}, skippedOrFailed=${skipped}`);
}

main().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
