import fs from "node:fs";
import path from "node:path";
import admin from "firebase-admin";
import type {WriteBatch} from "firebase-admin/firestore";
import type {CategorySeedFile, CategorySeedNode, ListingType, MarketplaceCategory} from "../src/catalog/categoryModels";

type FlatCategory = Omit<MarketplaceCategory, "createdAt" | "updatedAt">;

const args = new Set(process.argv.slice(2));
const projectArg = process.argv.find((value) => value.startsWith("--project="));
const projectId = projectArg?.split("=")[1] || "fatiweb-marketplace";
const dryRun = args.has("--dry-run");
const deactivateMissing = !args.has("--keep-missing");
const seedPath = path.resolve(process.cwd(), "data", "marketplace_categories.seed.json");

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

function placeholderUrl(name: string): string {
  return `https://placehold.co/640x420/F7F9FA/74613F?text=${slugify(name).replace(/-/g, "+")}`;
}

function keywordsFor(...values: string[]): string[] {
  return Array.from(new Set(
    values
      .flatMap((value) => value.toLowerCase().replace(/&|\+/g, " ").split(/[\s/_-]+/g))
      .map((value) => value.replace(/^'+|'+$/g, "").trim())
      .filter((value) => value.length >= 3),
  ));
}

function flattenNode(
  node: CategorySeedNode,
  index: number,
  parent: FlatCategory | null,
  ancestors: string[],
  inheritedListingTypes: ListingType[],
): FlatCategory[] {
  const slug = slugify(node.name);
  const id = parent ? `${parent.id}__${slug}` : slug;
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
  };

  const children = node.children || [];
  return [
    category,
    ...children.flatMap((child, childIndex) =>
      flattenNode(child, childIndex, category, [...ancestors, category.id], listingTypes),
    ),
  ];
}

function loadSeed(): FlatCategory[] {
  const raw = JSON.parse(fs.readFileSync(seedPath, "utf8")) as CategorySeedFile;
  if (raw.collection !== "marketplaceCategories") {
    throw new Error(`Unexpected collection ${raw.collection}.`);
  }
  const flat = raw.categories.flatMap((category, index) =>
    flattenNode(category, index * 100, null, [], category.listingTypes || ["product"]),
  );
  const ids = new Set<string>();
  flat.forEach((category) => {
    if (ids.has(category.id)) {
      throw new Error(`Duplicate category id ${category.id}.`);
    }
    ids.add(category.id);
    if (!category.name || !category.slug || category.level > 2) {
      throw new Error(`Invalid category ${category.id}.`);
    }
  });
  return flat;
}

async function commitBatches(categories: FlatCategory[]): Promise<void> {
  admin.initializeApp({projectId});
  const db = admin.firestore();
  const now = admin.firestore.FieldValue.serverTimestamp();
  const collection = db.collection("marketplaceCategories");
  const activeIds = new Set(categories.map((category) => category.id));
  const batches: WriteBatch[] = [];
  let current = db.batch();
  let operationCount = 0;

  function queue(write: (batch: WriteBatch) => void) {
    write(current);
    operationCount += 1;
    if (operationCount % 450 === 0) {
      batches.push(current);
      current = db.batch();
    }
  }

  categories.forEach((category) => {
    queue((batch) => {
      batch.set(collection.doc(category.id), {
        ...category,
        updatedAt: now,
        createdAt: now,
      }, {merge: true});
    });
  });

  if (deactivateMissing) {
    const existing = await collection.get();
    existing.docs
      .filter((doc) => !activeIds.has(doc.id))
      .forEach((doc) => {
        queue((batch) => batch.set(doc.ref, {isActive: false, updatedAt: now}, {merge: true}));
      });
  }

  queue((batch) => {
    batch.set(db.collection("categoryConfig").doc("current"), {
      collection: "marketplaceCategories",
      version: 1,
      activeCount: categories.length,
      updatedAt: now,
    }, {merge: true});
  });

  batches.push(current);
  await Promise.all(batches.map((batch) => batch.commit()));
}

async function main(): Promise<void> {
  const categories = loadSeed();
  const topCount = categories.filter((category) => category.level === 0).length;
  const subCount = categories.filter((category) => category.level === 1).length;
  const childCount = categories.filter((category) => category.level === 2).length;

  console.log(`Loaded ${categories.length} categories (${topCount} top, ${subCount} sub, ${childCount} child).`);
  if (dryRun) {
    console.log("Dry run only. No Firestore writes were made.");
    return;
  }
  await commitBatches(categories);
  console.log(`Seeded marketplaceCategories for project ${projectId}.`);
}

main().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
