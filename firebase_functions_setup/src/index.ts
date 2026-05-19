import {setGlobalOptions} from "firebase-functions/v2";
import {assistantSendMessage} from "./assistant/assistantSendMessage";
import {onAuthUserCreate} from "./auth/onAuthUserCreate";
import {syncUserRoleClaims} from "./auth/syncUserRoleClaims";
import {adminDeleteProduct} from "./catalog/adminDeleteProduct";
import {adminUpsertProduct} from "./catalog/adminUpsertProduct";
import {generateProductInfo} from "./catalog/generateProductInfo";
import {adminSendAnnouncement} from "./notifications/adminSendAnnouncement";
import {
  blockConversationUser,
  hideConversation,
  markConversationRead,
  openOrCreateConversation,
  reportConversationMessage,
  sendConversationMessage,
  toggleConversationMessageReaction,
} from "./messaging/conversations";
import {createOrder} from "./orders/createOrder";
import {updateOrderStatus} from "./orders/updateOrderStatus";
import {submitReview} from "./reviews/submitReview";
import {sellerFetchWorkspace} from "./seller/fetchSellerWorkspace";
import {onImageThumbnailFinalized} from "./storage/onImageThumbnailFinalized";
import {adminPromoteUserToVendeur} from "./users/adminPromoteUserToVendeur";
import {adminRevokeVendeurAccess} from "./users/adminRevokeVendeurAccess";
import {onUserProfileUpdate} from "./users/onUserProfileUpdate";

setGlobalOptions({
  region: "europe-west1",
  maxInstances: 10,
});

export {
  onAuthUserCreate,
  syncUserRoleClaims,
  createOrder,
  updateOrderStatus,
  sellerFetchWorkspace,
  adminUpsertProduct,
  adminDeleteProduct,
  generateProductInfo,
  adminSendAnnouncement,
  adminPromoteUserToVendeur,
  adminRevokeVendeurAccess,
  onUserProfileUpdate,
  submitReview,
  onImageThumbnailFinalized,
  assistantSendMessage,
  openOrCreateConversation,
  sendConversationMessage,
  markConversationRead,
  toggleConversationMessageReaction,
  hideConversation,
  blockConversationUser,
  reportConversationMessage,
};
