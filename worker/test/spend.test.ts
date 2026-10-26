import { describe, expect, it } from "vitest";
import { addSpendGbp, getSpendGbp, monthKey } from "../src/spend";
import { asKv, FakeKV } from "./fakes";

describe("monthKey", () => {
  it("uses the UTC year and month", () => {
    expect(monthKey(new Date("2026-10-31T23:59:59Z"))).toBe("spend:2026-10");
    expect(monthKey(new Date("2026-11-01T00:00:00Z"))).toBe("spend:2026-11");
  });
});

describe("spend store", () => {
  const now = new Date("2026-10-26T10:00:00Z");

  it("starts at zero", async () => {
    expect(await getSpendGbp(asKv(new FakeKV()), now)).toBe(0);
  });

  it("accumulates within a month", async () => {
    const kv = asKv(new FakeKV());
    await addSpendGbp(kv, now, 0.25);
    await addSpendGbp(kv, now, 0.5);
    expect(await getSpendGbp(kv, now)).toBeCloseTo(0.75, 9);
  });

  it("starts a new total each month", async () => {
    const kv = asKv(new FakeKV());
    await addSpendGbp(kv, now, 3);
    expect(await getSpendGbp(kv, new Date("2026-11-02T00:00:00Z"))).toBe(0);
  });

  it("ignores a corrupt stored value rather than failing", async () => {
    const fake = new FakeKV();
    fake.store.set(monthKey(now), "not a number");
    expect(await getSpendGbp(asKv(fake), now)).toBe(0);
  });

  it("does not record negative or non-finite amounts", async () => {
    const kv = asKv(new FakeKV());
    await addSpendGbp(kv, now, -5);
    await addSpendGbp(kv, now, Number.NaN);
    expect(await getSpendGbp(kv, now)).toBe(0);
  });
});
