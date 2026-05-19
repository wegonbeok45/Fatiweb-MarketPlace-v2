const crypto = require("node:crypto");
const fs = require("node:fs/promises");
const path = require("node:path");
const sharp = require("../firebase_functions_setup/node_modules/sharp");

const ROOT = path.resolve(__dirname, "..");
const PRODUCTS_DIR = path.join(ROOT, "PRODUCTS");
const MISSING_CATEGORIES_DIR = path.join(ROOT, "missing categories");
const PROJECT_ID = process.env.FIREBASE_PROJECT_ID || "fatiweb-marketplace";
const STORAGE_BUCKET = process.env.FIREBASE_STORAGE_BUCKET || `${PROJECT_ID}.firebasestorage.app`;
const DRY_RUN = process.argv.includes("--dry-run") || process.argv.includes("--dry");
const CATEGORIES_ONLY = process.argv.includes("--categories-only");
const WARM_BG = "#F7F5F0";

const products = [
  product("582257942_978961374847342_5439278119803268904_n.jpg", "Blue Printed Lounge Set", "Soft two-piece women's lounge set", 89.90, "fashion", ["fashion", "women-s-clothing"], "women-s-clothing", ["loungewear", "set", "blue"]),
  product("588863752_4133060060160538_3490804508354370811_n.jpg", "Pink Butterfly Toddler Tracksuit", "Cozy toddler tracksuit with butterfly print", 54.90, "baby-and-toys", ["baby-and-toys", "baby-clothing"], "baby-clothing", ["kids", "tracksuit", "pink"]),
  product("589615843_1927212674609536_2845919115521289915_n.jpg", "Rose Floral Summer Dress", "Elegant floral dress with a light premium finish", 119.90, "fashion", ["fashion", "women-s-clothing"], "women-s-clothing", ["dress", "floral", "occasion"]),
  product("591660596_1489323646218705_2036385217448614503_n.webp", "Powder Blue Wide-Leg Co-ord", "Chic sleeveless top and wide-leg pants set", 124.90, "fashion", ["fashion", "women-s-clothing"], "women-s-clothing", ["co-ord", "pants", "blue"]),
  product("591763979_1349112570477479_1686037225996308184_n.jpg", "Beige Floral Maxi Dress", "Soft beige floral maxi dress for warm days", 118.90, "fashion", ["fashion", "women-s-clothing"], "women-s-clothing", ["dress", "floral", "beige"]),
  product("592069360_1574638871041469_3730921586560335563_n.jpg", "Turquoise Embroidered Kaftan", "Flowing traditional kaftan with embroidered front detail", 149.90, "fashion", ["fashion", "traditional-wear"], "traditional-wear", ["kaftan", "traditional", "embroidered"]),
  product("592253739_1635601744392143_1456606839927802940_n.jpg", "Pomegranate Peel Powder", "Natural pomegranate peel powder for beauty routines", 24.90, "beauty-and-health", ["beauty-and-health", "natural-and-organic"], "natural-and-organic", ["pomegranate", "natural", "powder"]),
  product("592860878_1201884978589181_3783901213560826930_n.jpg", "Musical Baby Activity Toy", "Colorful musical activity toy for babies", 39.90, "baby-and-toys", ["baby-and-toys", "toys"], "toys", ["baby", "music", "toy"]),
  product("592860878_2434125520368856_9209695327106905973_n.jpg", "Three-Piece Baby Musical Toy Set", "Pastel baby toy set with lights and music", 49.90, "baby-and-toys", ["baby-and-toys", "toys"], "toys", ["baby", "toy", "pastel"]),
  product("592869883_978282478125258_4098621183359789027_n.jpg", "Peach Butterfly Toddler Tracksuit", "Comfortable toddler tracksuit in soft peach", 54.90, "baby-and-toys", ["baby-and-toys", "baby-clothing"], "baby-clothing", ["kids", "tracksuit", "peach"]),
  product("597956921_1498779578361557_8450223630954541106_n.jpg", "Black Embroidered Kaftan", "Black traditional kaftan with refined embroidered neckline", 154.90, "fashion", ["fashion", "traditional-wear"], "traditional-wear", ["kaftan", "black", "embroidered"]),
  product("659712516_950000754187744_70541189374443233_n.jpg", "Rainbow Baby Play Gym", "Soft activity play mat with hanging toys", 119.90, "baby-and-toys", ["baby-and-toys", "toys"], "toys", ["baby", "playmat", "activity"]),
  product("664058031_954806707156331_4299036887059745469_n.jpg", "Blue Printed Pajama Set", "Relaxed blue pajama set with button shirt", 84.90, "fashion", ["fashion", "women-s-clothing"], "women-s-clothing", ["pajama", "set", "blue"]),
  product("671657345_28133689639552780_695876670781468197_n.jpg", "Red Sport Co-ord Set", "Casual red sport set with zip jacket and pants", 94.90, "fashion", ["fashion", "activewear"], "activewear", ["sport", "tracksuit", "red"]),
  product("675307953_1489166219367990_6020712574198475981_n.jpg", "Black Modest Sport Set", "Comfortable black modest activewear set", 98.90, "fashion", ["fashion", "activewear"], "activewear", ["activewear", "black", "modest"]),
  product("688413759_4135012316747441_8178096677879966348_n.jpg", "Curly Hair Care Set", "Complete curly hair routine with cream and shampoo", 69.90, "beauty-and-health", ["beauty-and-health", "haircare"], "haircare", ["curly", "haircare", "set"]),
  product("688762406_995721726303187_8642257081841006849_n.jpg", "Burgundy Sport Co-ord Set", "Casual burgundy co-ord with zip detail", 96.90, "fashion", ["fashion", "activewear"], "activewear", ["sport", "tracksuit", "burgundy"]),
  product("688826424_1542060797520227_8066438455143264396_n.jpg", "Cream Floral Blouse", "Light cream blouse with delicate floral print", 79.90, "fashion", ["fashion", "women-s-clothing"], "women-s-clothing", ["blouse", "floral", "cream"]),
  product("689060894_1719716132389209_6961924905603990420_n.jpg", "Taupe Embroidered Kaftan", "Premium taupe kaftan with embroidered neckline", 149.90, "fashion", ["fashion", "traditional-wear"], "traditional-wear", ["kaftan", "taupe", "embroidered"]),
  product("689132149_931771053181488_2428780939996569739_n.jpg", "Blue Floral Tunic Set", "Two-piece blue floral tunic and pants set", 112.90, "fashion", ["fashion", "women-s-clothing"], "women-s-clothing", ["tunic", "floral", "set"]),
  product("689136467_975626908700482_2089819497344915460_n.jpg", "Ivory Floral Tunic Set", "Elegant ivory tunic and pants with floral pattern", 112.90, "fashion", ["fashion", "women-s-clothing"], "women-s-clothing", ["tunic", "ivory", "set"]),
  product("689254868_993899326406729_8900108400033705164_n.jpg", "Beige Button Blouse", "Minimal beige button blouse with soft fabric finish", 74.90, "fashion", ["fashion", "women-s-clothing"], "women-s-clothing", ["blouse", "beige", "minimal"]),
  product("689318341_1306620098101484_5975928764516062689_n.webp", "Pink Wide-Leg Co-ord", "Feminine pink sleeveless co-ord with wide pants", 124.90, "fashion", ["fashion", "women-s-clothing"], "women-s-clothing", ["co-ord", "pink", "pants"]),
  product("689494931_1008242868438345_989116605565194084_n.png", "Ananj Aloe Skincare Duo", "Aloe skincare set with gel and brightening care", 58.90, "beauty-and-health", ["beauty-and-health", "skincare"], "skincare", ["skincare", "aloe", "care"]),
  product("689599529_1964791034399483_1883118011576266281_n.jpg", "White Orchid Home Arrangement", "Decorative orchid arrangement for a clean home accent", 139.90, "home-and-furniture", ["home-and-furniture", "home-decor"], "home-decor", ["orchid", "decor", "home"]),
  product("691556765_1368283205359046_4960927238735588805_n.jpg", "Beige Floral Occasion Dress", "Soft floral occasion dress with a flattering waist", 119.90, "fashion", ["fashion", "women-s-clothing"], "women-s-clothing", ["dress", "floral", "occasion"]),
  product("693662865_1308155464788154_7072850441198557013_n.webp", "Arabic Learning Sound Book", "Interactive Arabic learning book with sound handle", 44.90, "baby-and-toys", ["baby-and-toys", "toys"], "toys", ["learning", "arabic", "kids"]),
  product("fatiweb.PNG", "Organic Pomegranate Vinegar", "Natural pomegranate vinegar for kitchen and wellness use", 29.90, "food-and-grocery", ["food-and-grocery", "tunisian-specialties"], "tunisian-specialties", ["vinegar", "pomegranate", "organic"]),
  product("qdsqd.PNG", "Pomegranate Seed Oil", "Natural pomegranate seed oil in a dropper bottle", 34.90, "beauty-and-health", ["beauty-and-health", "natural-and-organic"], "natural-and-organic", ["oil", "pomegranate", "natural"]),
];

const categoryImages = [
  {file: "books.png", id: "books-and-media"},
  {file: "collectobiles and Hobbies.png", id: "collectibles-and-hobbies"},
];

function product(file, title, subtitle, price, category, categoryIds, categoryLeafId, tags) {
  const id = `local_${slugify(title)}`;
  return {
    id,
    file,
    title,
    subtitle,
    price,
    category,
    categoryIds,
    categoryLeafId,
    tags,
    stock: 18 + (title.length % 9),
    rating: Number((4.5 + (title.length % 5) * 0.08).toFixed(1)),
    reviewsCount: 8 + (title.length % 22),
  };
}

function slugify(value) {
  return value
    .normalize("NFD")
    .replace(/[\u0300-\u036f]/g, "")
    .toLowerCase()
    .replace(/&/g, " and ")
    .replace(/[^a-z0-9]+/g, "-")
    .replace(/^-+|-+$/g, "");
}

function firebaseDownloadUrl(bucketName, storagePath, token) {
  return `https://firebasestorage.googleapis.com/v0/b/${bucketName}/o/${encodeURIComponent(storagePath)}?alt=media&token=${token}`;
}

async function getFirebaseCliAccessToken() {
  const appData = process.env.APPDATA;
  if (!appData) throw new Error("APPDATA is missing; cannot find Firebase CLI login.");
  const authPath = path.join(appData, "npm", "node_modules", "firebase-tools", "lib", "auth.js");
  const auth = require(authPath);
  const account = auth.getGlobalDefaultAccount();
  const token = await auth.getAccessToken(account?.tokens?.refresh_token, []);
  if (!token.access_token) throw new Error("Firebase CLI login did not return an access token.");
  return token.access_token;
}

async function checkedFetch(url, init, label) {
  const response = await fetch(url, init);
  if (!response.ok) throw new Error(`${label} failed ${response.status}: ${await response.text()}`);
  return response;
}

async function prepareImage(sourcePath, size) {
  return sharp(sourcePath)
    .rotate()
    .resize(size, size, {fit: "contain", position: "centre", background: WARM_BG})
    .flatten({background: WARM_BG})
    .webp({quality: 86, effort: 4})
    .toBuffer();
}

async function uploadImage(accessToken, storagePath, image, contentType = "image/webp") {
  const token = crypto.randomUUID();
  const metadata = {
    name: storagePath,
    contentType,
    cacheControl: "public,max-age=31536000,immutable",
    metadata: {
      firebaseStorageDownloadTokens: token,
      imageSource: "FatiWeb local import",
      imageAuthor: "FatiWeb",
    },
  };
  const boundary = `fatiweb-${crypto.randomUUID()}`;
  const metadataPart = Buffer.from(
    `--${boundary}\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n${JSON.stringify(metadata)}\r\n` +
    `--${boundary}\r\nContent-Type: ${contentType}\r\n\r\n`,
    "utf8",
  );
  const closePart = Buffer.from(`\r\n--${boundary}--\r\n`, "utf8");
  await checkedFetch(
    `https://storage.googleapis.com/upload/storage/v1/b/${encodeURIComponent(STORAGE_BUCKET)}/o?uploadType=multipart&name=${encodeURIComponent(storagePath)}`,
    {
      method: "POST",
      headers: {
        Authorization: `Bearer ${accessToken}`,
        "Content-Type": `multipart/related; boundary=${boundary}`,
      },
      body: Buffer.concat([metadataPart, image, closePart]),
    },
    `Storage upload ${storagePath}`,
  );
  return firebaseDownloadUrl(STORAGE_BUCKET, storagePath, token);
}

function field(value) {
  if (value === null || value === undefined) return {nullValue: null};
  if (typeof value === "string") return {stringValue: value};
  if (typeof value === "boolean") return {booleanValue: value};
  if (Number.isInteger(value)) return {integerValue: String(value)};
  if (typeof value === "number") return {doubleValue: value};
  if (value instanceof Date) return {timestampValue: value.toISOString()};
  if (Array.isArray(value)) return {arrayValue: {values: value.map(field)}};
  return {mapValue: {fields: Object.fromEntries(Object.entries(value).map(([key, item]) => [key, field(item)]))}};
}

function productPayload(item, imageUrl, now) {
  const description = `${item.subtitle}. Carefully selected for FatiWeb shoppers with a clean look, practical quality, and ready-to-sell presentation.`;
  const searchKeywords = Array.from(new Set([
    ...slugify(`${item.title} ${item.subtitle} ${item.category} ${item.tags.join(" ")}`).split("-").filter((part) => part.length >= 2),
    item.category,
    item.categoryLeafId,
  ]));
  return {
    id: item.id,
    title: item.title,
    subtitle: item.subtitle,
    description,
    bullets: [
      "Balanced product photo prepared for clean marketplace cards",
      "Ready for everyday browsing and search discovery",
      "Selected for the FatiWeb local catalog",
    ],
    tags: item.tags,
    category: item.category,
    categoryIds: item.categoryIds,
    categoryLeafId: item.categoryLeafId,
    origin: "tunisia",
    price: item.price,
    priceMinor: Math.round(item.price * 100),
    currency: "TND",
    rating: item.rating,
    ratingAvg: item.rating,
    reviewsCount: item.reviewsCount,
    ratingCount: item.reviewsCount,
    imageUrl,
    imageUrls: [imageUrl],
    thumbnailUrl: imageUrl,
    thumbnailUrls: [imageUrl],
    stock: item.stock,
    isBio: ["beauty-and-health", "food-and-grocery"].includes(item.category),
    isActive: true,
    status: "published",
    searchKeywords,
    schemaVersion: 1,
    sellerId: "fatiweb-official",
    sellerName: "FatiWeb",
    sellerAvatarUrl: "",
    sellerTotalSold: 0,
    sellerRating: 4.8,
    sellerRatingCount: 24,
    createdAt: now,
    updatedAt: now,
  };
}

async function patchFirestore(accessToken, collection, docId, payload) {
  await checkedFetch(
    `https://firestore.googleapis.com/v1/projects/${PROJECT_ID}/databases/(default)/documents/${collection}/${docId}`,
    {
      method: "PATCH",
      headers: {
        Authorization: `Bearer ${accessToken}`,
        "Content-Type": "application/json",
      },
      body: JSON.stringify({fields: Object.fromEntries(Object.entries(payload).map(([key, value]) => [key, field(value)]))}),
    },
    `Firestore write ${collection}/${docId}`,
  );
}

async function main() {
  const productFiles = new Set((await fs.readdir(PRODUCTS_DIR)).filter((file) => /\.(png|jpe?g|webp)$/i.test(file)));
  const missingProducts = products.filter((item) => !productFiles.has(item.file));
  if (missingProducts.length) throw new Error(`Missing product images: ${missingProducts.map((item) => item.file).join(", ")}`);

  const plannedProducts = CATEGORIES_ONLY ? [] : products;
  console.log(`Plan: ${plannedProducts.length} products, ${categoryImages.length} category images, project=${PROJECT_ID}, bucket=${STORAGE_BUCKET}, dryRun=${DRY_RUN}`);
  if (plannedProducts.length) {
    console.table(plannedProducts.map((item) => ({
      id: item.id,
      file: item.file,
      title: item.title,
      category: item.category,
      price: item.price,
    })));
  }
  if (DRY_RUN) return;

  const accessToken = await getFirebaseCliAccessToken();
  const now = new Date();

  for (const item of plannedProducts) {
    const image = await prepareImage(path.join(PRODUCTS_DIR, item.file), 1200);
    const storagePath = `product_images/${item.id}/${item.id}_${Date.now()}.webp`;
    const imageUrl = await uploadImage(accessToken, storagePath, image);
    await patchFirestore(accessToken, "products", item.id, productPayload(item, imageUrl, now));
    console.log(`Uploaded product: ${item.title}`);
  }

  for (const item of categoryImages) {
    const image = await prepareImage(path.join(MISSING_CATEGORIES_DIR, item.file), 1024);
    const storagePath = `category-images/${item.id}.webp`;
    const imageUrl = await uploadImage(accessToken, storagePath, image);
    await patchFirestore(accessToken, "marketplaceCategories", item.id, {
      imageUrl,
      imageSource: "FatiWeb local category images",
      imageAuthor: "FatiWeb",
      imageAttribution: "FatiWeb category image",
      updatedAt: now,
    });
    console.log(`Uploaded category image: ${item.id}`);
  }

  console.log(`Done: products=${plannedProducts.length}, categoryImages=${categoryImages.length}`);
}

main().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
