import {HttpsError, type CallableRequest} from "firebase-functions/v2/https";
import {COLLECTIONS, USER_ROLES} from "./constants";
import {db} from "./firestore";

export type AuthContext = {
  uid: string;
  email: string | null;
};

export type RoleAuthContext = AuthContext & {
  role: string;
  name: string;
  avatarUrl: string;
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

export async function assertAdminOrVendeur(request: CallableRequest<unknown>): Promise<RoleAuthContext> {
  const authContext = assertAuthenticated(request);
  const token = (request.auth?.token ?? {}) as Record<string, unknown>;
  const tokenRole = token.admin === true ?
    USER_ROLES.ADMIN :
    (typeof token.role === "string" ? token.role : "");

  if (token.admin === true || tokenRole === USER_ROLES.ADMIN || tokenRole === USER_ROLES.VENDEUR) {
    return {
      ...authContext,
      role: tokenRole,
      name: tokenDisplayName(token, authContext.email),
      avatarUrl: tokenAvatarUrl(token),
    };
  }

  const userDoc = await db.collection(COLLECTIONS.USERS).doc(authContext.uid).get();
  const role = userDoc.get("role");
  if (role === USER_ROLES.ADMIN || role === USER_ROLES.VENDEUR) {
    return {
      ...authContext,
      role,
      name: userDisplayName(userDoc.data(), authContext.email),
      avatarUrl: userAvatarUrl(userDoc.data()),
    };
  }

  throw new HttpsError("permission-denied", "Seller privileges are required.");
}

function userAvatarUrl(data: Record<string, unknown> | undefined): string {
  const avatarUrl = typeof data?.avatarUrl === "string" ? data.avatarUrl.trim() : "";
  const avatar = typeof data?.avatar === "string" ? data.avatar.trim() : "";
  const photoUrl = typeof data?.photoUrl === "string" ? data.photoUrl.trim() : "";
  return avatarUrl || avatar || photoUrl;
}

function userDisplayName(data: Record<string, unknown> | undefined, email: string | null): string {
  const name = typeof data?.name === "string" ? data.name.trim() : "";
  const displayName = typeof data?.displayName === "string" ? data.displayName.trim() : "";
  return name || displayName || email || "Fatiweb Seller";
}

function tokenAvatarUrl(token: Record<string, unknown>): string {
  const picture = typeof token.picture === "string" ? token.picture.trim() : "";
  const avatarUrl = typeof token.avatarUrl === "string" ? token.avatarUrl.trim() : "";
  return avatarUrl || picture;
}

function tokenDisplayName(token: Record<string, unknown>, email: string | null): string {
  const name = typeof token.name === "string" ? token.name.trim() : "";
  return name || email || "Fatiweb Seller";
}
