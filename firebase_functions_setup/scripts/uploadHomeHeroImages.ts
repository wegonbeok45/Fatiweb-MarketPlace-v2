import crypto from "node:crypto";
import fs from "node:fs/promises";
import path from "node:path";
import sharp from "sharp";

type HeroImage = {
  fileName: string;
  storageName: string;
  category: string;
};

const rawArgs = process.argv.slice(2);
const dryRun = rawArgs.includes("--dry-run") || rawArgs.includes("--dry");
const projectId = readArg("--project") || process.env.FIREBASE_PROJECT_ID || "fatiweb-marketplace";
const storageBucket = process.env.FIREBASE_STORAGE_BUCKET || `${projectId}.firebasestorage.app`;
const inputDir = path.resolve(process.cwd(), "..", "photos");

const heroImages: HeroImage[] = [
  {fileName: "clothes.png", storageName: "home-hero-clothes.webp", category: "fashion"},
  {fileName: "clothes1.png", storageName: "home-hero-clothes1.webp", category: "fashion"},
  {fileName: "toys.png", storageName: "home-hero-toys.webp", category: "baby-and-toys"},
  {fileName: "food.png", storageName: "home-hero-food.webp", category: "food-and-grocery"},
];

function readArg(name: string): string | undefined {
  const exact = rawArgs.find((arg) => arg.startsWith(`${name}=`));
  if (exact) return exact.split("=").slice(1).join("=");
  const index = rawArgs.indexOf(name);
  return index >= 0 ? rawArgs[index + 1] : undefined;
}

function firebaseDownloadUrl(bucketName: string, storagePath: string, token?: string): string {
  const base = `https://firebasestorage.googleapis.com/v0/b/${bucketName}/o/${encodeURIComponent(storagePath)}?alt=media`;
  return token ? `${base}&token=${token}` : base;
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

async function buildImage(sourcePath: string): Promise<Buffer> {
  return sharp(sourcePath)
    .resize(1200, 1200, {fit: "inside", withoutEnlargement: true})
    .webp({quality: 90, effort: 4})
    .toBuffer();
}

async function uploadHeroImages(): Promise<void> {
  const accessToken = await getFirebaseCliAccessToken();
  const results = [];

  for (const item of heroImages) {
    const sourcePath = path.join(inputDir, item.fileName);
    await fs.access(sourcePath);

    const image = await buildImage(sourcePath);
    const token = crypto.randomUUID();
    const storagePath = `category-images/${item.storageName}`;
    const metadata = {
      name: storagePath,
      contentType: "image/webp",
      cacheControl: "public,max-age=31536000,immutable",
      metadata: {
        firebaseStorageDownloadTokens: token,
        imageSource: "FatiWeb home hero photos",
        imageAuthor: "FatiWeb",
        category: item.category,
      },
    };
    const boundary = `fatiweb-${crypto.randomUUID()}`;
    const metadataPart = Buffer.from(
      `--${boundary}\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n${JSON.stringify(metadata)}\r\n` +
        `--${boundary}\r\nContent-Type: image/webp\r\n\r\n`,
      "utf8",
    );
    const closePart = Buffer.from(`\r\n--${boundary}--\r\n`, "utf8");

    if (!dryRun) {
      await checkedFetch(
        `https://storage.googleapis.com/upload/storage/v1/b/${encodeURIComponent(storageBucket)}/o?uploadType=multipart&name=${encodeURIComponent(storagePath)}`,
        {
          method: "POST",
          headers: {
            Authorization: `Bearer ${accessToken}`,
            "Content-Type": `multipart/related; boundary=${boundary}`,
          },
          body: Buffer.concat([metadataPart, image, closePart]),
        },
        `Storage upload for ${item.fileName}`,
      );
    }

    results.push({
      fileName: item.fileName,
      category: item.category,
      storagePath,
      publicUrl: firebaseDownloadUrl(storageBucket, storagePath),
      tokenUrl: firebaseDownloadUrl(storageBucket, storagePath, token),
      bytes: image.length,
    });
  }

  console.log(JSON.stringify({dryRun, projectId, storageBucket, results}, null, 2));
}

uploadHeroImages().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
