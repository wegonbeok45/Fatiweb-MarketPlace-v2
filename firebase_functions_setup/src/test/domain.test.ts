import test from "node:test";
import assert from "node:assert/strict";
import {
  appendOrderTrackingEvent,
  canTransitionOrderStatus,
  fromMinorUnits,
  generateSearchKeywords,
  toMinorUnits,
} from "../shared/domain";

test("minor-unit conversion stays stable for dinar amounts", () => {
  assert.equal(toMinorUnits(12.5), 12500);
  assert.equal(fromMinorUnits(12500), 12.5);
});

test("order transitions follow the production order lifecycle", () => {
  assert.equal(canTransitionOrderStatus("pending", "confirmed"), true);
  assert.equal(canTransitionOrderStatus("confirmed", "preparing"), true);
  assert.equal(canTransitionOrderStatus("preparing", "shipped"), true);
  assert.equal(canTransitionOrderStatus("shipped", "delivered"), true);
  assert.equal(canTransitionOrderStatus("delivered", "pending"), false);
  assert.equal(canTransitionOrderStatus("cancelled", "confirmed"), false);
});

test("tracking events replace duplicate statuses and stay sorted", () => {
  const tracking = appendOrderTrackingEvent(
    [
      {status: "pending", changedAt: 10},
      {status: "preparing", changedAt: 20},
    ],
    "preparing",
    30,
  );
  assert.deepEqual(tracking, [
    {status: "pending", changedAt: 10},
    {status: "preparing", changedAt: 30},
  ]);
});

test("keyword generation deduplicates repeated search terms", () => {
  const keywords = generateSearchKeywords("FatiWeb lamp", "Craft lamp", ["lamp", "decor"]);
  assert.deepEqual(keywords, [
    "fatiweb",
    "fat",
    "fati",
    "fatiw",
    "fatiwe",
    "lamp",
    "lam",
    "craft",
    "cra",
    "craf",
    "decor",
    "dec",
    "deco",
  ]);
});
