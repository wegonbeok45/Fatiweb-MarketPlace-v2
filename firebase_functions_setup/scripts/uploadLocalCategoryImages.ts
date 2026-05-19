import crypto from "node:crypto";
import fs from "node:fs/promises";
import path from "node:path";
import sharp from "sharp";
import type {CategorySeedFile, CategorySeedNode, ListingType} from "../src/catalog/categoryModels";

type TopLevelCategory = {
  id: string;
  name: string;
  listingTypes: ListingType[];
};

const rawArgs = process.argv.slice(2);
const dryRun = rawArgs.includes("--dry-run") || rawArgs.includes("--dry");
const projectId = readArg("--project") || process.env.FIREBASE_PROJECT_ID || "fatiweb-marketplace";
const storageBucket = process.env.FIREBASE_STORAGE_BUCKET || `${projectId}.firebasestorage.app`;
const seedPath = path.resolve(process.cwd(), "data", "marketplace_categories.seed.json");
const inputDir = path.resolve(process.cwd(), "..", "categories");

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

function firebaseDownloadUrl(bucketName: string, storagePath: string, token: string): string {
  return `https://firebasestorage.googleapis.com/v0/b/${bucketName}/o/${encodeURIComponent(storagePath)}?alt=media&token=${token}`;
}

async function getFirebaseCliAccessToken(): Promise<string> {
  const appData = process.env.APPDATA;
  if (!appData) {
    throw new Error("APPDATA is missing; cannot find Firebase CLI login.");
  }

  const authPath = path.join(appData, "npm", "node_modules", "firebase-tools", "lib", "auth.js");
  const auth = require(authPath) as {
    getGlobalDefaultAccount: () => {tokens: {refresh_token?: string}} | undefined;
    getAccessToken: (refreshToken: string | undefined, authScopes: string[]) => Promise<{access_token: string}>;
  };

  const account = auth.getGlobalDefaultAccount();
  const token = await auth.getAccessToken(account?.tokens.refresh_token, []);
  if (!token.access_token) {
    throw new Error("Firebase CLI login did not return an access token.");
  }
  return token.access_token;
}

async function checkedFetch(url: string, init: RequestInit, label: string): Promise<Response> {
  const response = await fetch(url, init);
  if (!response.ok) {
    throw new Error(`${label} failed ${response.status}: ${await response.text()}`);
  }
  return response;
}

async function loadTopLevelCategories(): Promise<Map<string, TopLevelCategory>> {
  const raw = JSON.parse(await fs.readFile(seedPath, "utf8")) as CategorySeedFile;
  return new Map(raw.categories.map((node: CategorySeedNode) => {
    const id = slugify(node.name);
    return [id, {
      id,
      name: node.name,
      listingTypes: node.listingTypes || ["product"],
    }];
  }));
}

async function loadLocalImages(categories: Map<string, TopLevelCategory>): Promise<Array<{
  category: TopLevelCategory;
  sourcePath: string;
  storagePath: string;
  image: Buffer;
}>> {
  const files = await fs.readdir(inputDir, {withFileTypes: true});
  const images = [];
  for (const file of files) {
    if (!file.isFile() || !/\.(png|jpe?g|webp)$/i.test(file.name)) continue;

    const categoryId = slugify(path.parse(file.name).name);
    const category = categories.get(categoryId);
    if (!category) {
      console.warn(`Skipping ${file.name}: no top-level category named ${categoryId}.`);
      continue;
    }

    const sourcePath = path.join(inputDir, file.name);
    const image = await sharp(sourcePath)
      .resize(1024, 1024, {fit: "cover", position: "centre"})
      .webp({quality: 84, effort: 4})
      .toBuffer();

    images.push({
      category,
      sourcePath,
      storagePath: `category-images/${category.id}.webp`,
      image,
    });
  }
  return images;
}

function firestoreField(value: string): {stringValue: string} {
  return {stringValue: value};
}

async function uploadAndUpdateFirestore(items: Awaited<ReturnType<typeof loadLocalImages>>): Promise<void> {
  const accessToken = await getFirebaseCliAccessToken();

  for (const item of items) {
    const token = crypto.randomUUID();
    const metadata = {
      name: item.storagePath,
      contentType: "image/webp",
      cacheControl: "public,max-age=31536000,immutable",
      metadata: {
        firebaseStorageDownloadTokens: token,
        imageSource: "FatiWeb local category images",
        imageAuthor: "FatiWeb",
      },
    };
    const boundary = `fatiweb-${crypto.randomUUID()}`;
    const metadataPart = Buffer.from(
      `--${boundary}\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n${JSON.stringify(metadata)}\r\n` +
      `--${boundary}\r\nContent-Type: image/webp\r\n\r\n`,
      "utf8",
    );
    const closePart = Buffer.from(`\r\n--${boundary}--\r\n`, "utf8");

    await checkedFetch(
      `https://storage.googleapis.com/upload/storage/v1/b/${encodeURIComponent(storageBucket)}/o?uploadType=multipart&name=${encodeURIComponent(item.storagePath)}`,
      {
        method: "POST",
        headers: {
          Authorization: `Bearer ${accessToken}`,
          "Content-Type": `multipart/related; boundary=${boundary}`,
        },
        body: Buffer.concat([metadataPart, item.image, closePart]),
      },
      `Storage upload for ${item.category.id}`,
    );

    const imageUrl = firebaseDownloadUrl(storageBucket, item.storagePath, token);
    const params = new URLSearchParams();
    ["imageUrl", "imageSource", "imageAuthor", "imageAttribution", "updatedAt"].forEach((field) => {
      params.append("updateMask.fieldPaths", field);
    });
    await checkedFetch(
      `https://firestore.googleapis.com/v1/projects/${projectId}/databases/(default)/documents/marketplaceCategories/${item.category.id}?${params}`,
      {
        method: "PATCH",
        headers: {
          Authorization: `Bearer ${accessToken}`,
          "Content-Type": "application/json",
        },
        body: JSON.stringify({
          fields: {
            imageUrl: firestoreField(imageUrl),
            imageSource: firestoreField("FatiWeb local category images"),
            imageAuthor: firestoreField("FatiWeb"),
            imageAttribution: firestoreField("FatiWeb category image"),
            updatedAt: {timestampValue: new Date().toISOString()},
          },
        }),
      },
      `Firestore update for ${item.category.id}`,
    );

    console.log(`Uploaded ${item.category.id}: ${item.storagePath}`);
  }
}

async function main(): Promise<void> {
  const categories = await loadTopLevelCategories();
  const images = await loadLocalImages(categories);

  console.log(`Local category image upload plan: ${images.length} images, project=${projectId}, bucket=${storageBucket}`);
  console.table(images.map((item) => ({
    id: item.category.id,
    name: item.category.name,
    file: path.basename(item.sourcePath),
    storagePath: item.storagePath,
  })));

  if (dryRun) return;
  await uploadAndUpdateFirestore(images);
  console.log(`Summary: uploaded=${images.length}, firestoreUpdated=${images.length}`);
}

main().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
