import {logger} from "firebase-functions";
import {onDocumentWritten} from "firebase-functions/v2/firestore";
import {COLLECTIONS, USER_ROLES} from "../shared/constants";
import {auth} from "../shared/firestore";

export const syncUserRoleClaims = onDocumentWritten(
  `${COLLECTIONS.USERS}/{userId}`,
  async (event) => {
    const userId = event.params.userId;
    const beforeRole = event.data?.before?.get("role");
    const afterRole = event.data?.after?.get("role");

    if (beforeRole === afterRole && event.data?.after?.exists) {
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

    const isAdmin = afterRole === USER_ROLES.ADMIN;
    const nextClaims = {
      ...existingClaims,
      admin: isAdmin,
      role: isAdmin ? USER_ROLES.ADMIN : USER_ROLES.CLIENT,
    };

    await auth.setCustomUserClaims(userId, nextClaims);
    logger.info("Synchronized custom claims from user profile", {
      userId,
      role: nextClaims.role,
    });
  },
);
