import {setGlobalOptions} from "firebase-functions/v2";
import {assistantSendMessage} from "./assistant/assistantSendMessage";
import {onAuthUserCreate} from "./auth/onAuthUserCreate";
import {syncUserRoleClaims} from "./auth/syncUserRoleClaims";
import {adminDeleteProduct} from "./catalog/adminDeleteProduct";
import {adminUpsertProduct} from "./catalog/adminUpsertProduct";
import {adminSendAnnouncement} from "./notifications/adminSendAnnouncement";
import {createOrder} from "./orders/createOrder";
import {updateOrderStatus} from "./orders/updateOrderStatus";
import {submitReview} from "./reviews/submitReview";

setGlobalOptions({
  region: "europe-west1",
  maxInstances: 10,
});

export {
  onAuthUserCreate,
  syncUserRoleClaims,
  createOrder,
  updateOrderStatus,
  adminUpsertProduct,
  adminDeleteProduct,
  adminSendAnnouncement,
  submitReview,
  assistantSendMessage,
};
