export type ListingType = "product" | "service" | "job" | "vehicle" | "real_estate" | "digital";

export interface MarketplaceCategory {
  id: string;
  name: string;
  slug: string;
  icon: string;
  imageUrl: string;
  parentCategory: string | null;
  ancestorIds: string[];
  level: 0 | 1 | 2;
  featured: boolean;
  sortOrder: number;
  searchKeywords: string[];
  listingTypes: ListingType[];
  isActive: boolean;
  createdAt?: Timestamp;
  updatedAt?: Timestamp;
}

export interface CategorySeedNode {
  name: string;
  icon?: string;
  featured?: boolean;
  listingTypes?: ListingType[];
  searchKeywords?: string[];
  children?: CategorySeedNode[];
}

export interface CategorySeedFile {
  version: number;
  collection: "marketplaceCategories";
  categories: CategorySeedNode[];
}
import type {Timestamp} from "firebase-admin/firestore";
