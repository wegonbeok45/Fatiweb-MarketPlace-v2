import {logger} from "firebase-functions";
import {onDocumentWritten} from "firebase-functions/v2/firestore";
import {FUNCTIONS_REGION} from "../shared/callableOptions";
import {COLLECTIONS, USER_ROLES} from "../shared/constants";
import {auth} from "../shared/firestore";

export const syncUserRoleClaims = onDocumentWritten(
  {
    document: `${COLLECTIONS.USERS}/{userId}`,
    region: FUNCTIONS_REGION,
  },
  async (event) => {
    const userId = event.params.userId;
    const beforeRole = normalizeRole(event.data?.before?.get("role"));
    const afterRole = normalizeRole(event.data?.after?.get("role"));

    if (event.data?.before?.exists && beforeRole === afterRole && event.data?.after?.exists) {
      return;
    }

    const userRecord = await auth.getUser(userId);
    const existingClaims = userRecord.customClaims || {};

    if (!event.data?.after?.exists) {
      if (existingClaims.admin || existingClaims.role) {
        await auth.setCustomUserClaims(userId, {
          ...existingClaims,
          admin: false,
          role: USER_ROLES.CLIENT,
        });
      }
      logger.info("Cleared elevated claims for deleted user profile", {userId});
      return;
    }

    const normalizedRole = afterRole;
    const isAdmin = normalizedRole === USER_ROLES.ADMIN;
    const nextClaims = {
      ...existingClaims,
      admin: isAdmin,
      role: normalizedRole,
    };

    await auth.setCustomUserClaims(userId, nextClaims);
    logger.info("Synchronized custom claims from user profile", {
      userId,
      role: nextClaims.role,
    });
  },
);

function normalizeRole(role: unknown): string {
  const value = typeof role === "string" ? role.trim().toLowerCase() : "";
  if (value === USER_ROLES.ADMIN) return USER_ROLES.ADMIN;
  if (value === USER_ROLES.VENDEUR || value === "vendor" || value === "seller") {
    return USER_ROLES.VENDEUR;
  }
  return USER_ROLES.CLIENT;
}
