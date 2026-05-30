import {logger} from "firebase-functions";
import {onDocumentUpdated} from "firebase-functions/v2/firestore";
import {FUNCTIONS_REGION} from "../shared/callableOptions";
import {COLLECTIONS, SCHEMA_VERSION} from "../shared/constants";
import {admin, db} from "../shared/firestore";
import {asString, chunk} from "../shared/domain";

const PRODUCT_BATCH_SIZE = 400;

export const onUserProfileUpdate = onDocumentUpdated(
  {
    document: `${COLLECTIONS.USERS}/{uid}`,
    region: FUNCTIONS_REGION,
    retry: false,
  },
  async (event) => {
    const uid = event.params.uid;
    const before = event.data?.before;
    const after = event.data?.after;
    if (!before || !after) return;

    const beforeAvatar = asString(before.get("avatarUrl"));
    const afterAvatar = asString(after.get("avatarUrl"));
    const beforeName = asString(before.get("name"), asString(before.get("displayName")));
    const afterName = asString(after.get("name"), asString(after.get("displayName")));
    const beforeTrust = JSON.stringify({
      verifiedAt: before.get("verifiedAt") || before.get("sellerVerifiedAt") || null,
      memberSince: before.get("memberSince") || before.get("createdAt") || null,
    });
    const afterTrust = JSON.stringify({
      verifiedAt: after.get("verifiedAt") || after.get("sellerVerifiedAt") || null,
      memberSince: after.get("memberSince") || after.get("createdAt") || null,
    });

    if (beforeAvatar === afterAvatar && beforeName === afterName && beforeTrust === afterTrust) return;

    const productsSnapshot = await db.collection(COLLECTIONS.PRODUCTS)
      .where("sellerId", "==", uid)
      .limit(200)
      .get();
    if (productsSnapshot.empty) return;

    for (const docs of chunk(productsSnapshot.docs, PRODUCT_BATCH_SIZE)) {
      const batch = db.batch();
      for (const doc of docs) {
        batch.set(doc.ref, {
          sellerAvatarUrl: afterAvatar,
          sellerName: afterName,
          sellerVerifiedAt: after.get("verifiedAt") || after.get("sellerVerifiedAt") || null,
          sellerMemberSince: after.get("memberSince") || after.get("createdAt") || null,
          sellerTotalSold: after.get("totalSold") || after.get("sellerTotalSold") || 0,
          sellerRating: after.get("sellerRating") || after.get("ratingAvg") || 0,
          sellerRatingCount: after.get("sellerRatingCount") || after.get("ratingCount") || 0,
          sellerProfileUpdatedAt: admin.firestore.FieldValue.serverTimestamp(),
          schemaVersion: SCHEMA_VERSION,
          updatedAt: admin.firestore.FieldValue.serverTimestamp(),
        }, {merge: true});
      }
      await batch.commit();
    }

    logger.info("Seller profile data propagated to products", {
      uid,
      productCount: productsSnapshot.size,
    });
  },
);
