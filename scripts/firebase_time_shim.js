const offsetMs = Number(process.env.FAKE_TIME_OFFSET_MS || 0);

if (!Number.isFinite(offsetMs) || offsetMs === 0) {
  return;
}

const OriginalDate = Date;

function ShiftedDate(...args) {
  if (!(this instanceof ShiftedDate)) {
    return OriginalDate(...args);
  }

  if (args.length === 0) {
    return new OriginalDate(OriginalDate.now() + offsetMs);
  }

  return new OriginalDate(...args);
}

ShiftedDate.UTC = OriginalDate.UTC;
ShiftedDate.parse = OriginalDate.parse;
ShiftedDate.now = () => OriginalDate.now() + offsetMs;
ShiftedDate.prototype = OriginalDate.prototype;

global.Date = ShiftedDate;
