import {HttpsError, type CallableRequest} from "firebase-functions/v2/https";
import {COLLECTIONS, USER_ROLES} from "./constants";
import {db} from "./firestore";

export type AuthContext = {
  uid: string;
  email: string | null;
};

export function assertAuthenticated(request: CallableRequest<unknown>): AuthContext {
  const auth = request.auth;
  if (!auth?.uid) {
    throw new HttpsError("unauthenticated", "Authentication is required.");
  }
  return {
    uid: auth.uid,
    email: auth.token.email ?? null,
  };
}

export async function assertAdmin(request: CallableRequest<unknown>): Promise<AuthContext> {
  const authContext = assertAuthenticated(request);
  const token = (request.auth?.token ?? {}) as Record<string, unknown>;

  if (token.admin === true || token.role === USER_ROLES.ADMIN) {
    return authContext;
  }

  const userDoc = await db.collection(COLLECTIONS.USERS).doc(authContext.uid).get();
  if (userDoc.get("role") === USER_ROLES.ADMIN) {
    return authContext;
  }

  throw new HttpsError("permission-denied", "Admin privileges are required.");
}
