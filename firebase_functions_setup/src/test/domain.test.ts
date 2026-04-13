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

test("order transitions allow forward movement only", () => {
  assert.equal(canTransitionOrderStatus("pending", "preparing"), true);
  assert.equal(canTransitionOrderStatus("shipped", "pending"), false);
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
  assert.deepEqual(keywords, ["fatiweb", "lamp", "craft", "decor"]);
});
