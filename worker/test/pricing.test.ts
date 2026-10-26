import { describe, expect, it } from "vitest";
import { costGbp, costUsd, PRICES, USD_TO_GBP } from "../src/pricing";

describe("costUsd", () => {
  it("prices input and output tokens per million", () => {
    const p = PRICES["claude-sonnet-5-5"];
    const usd = costUsd("claude-sonnet-5-5", { input_tokens: 1_000_000, output_tokens: 1_000_000 });
    expect(usd).toBeCloseTo(p.inputPerM + p.outputPerM, 6);
  });

  it("prices cache writes and reads separately from plain input", () => {
    const p = PRICES["claude-haiku-5-5"];
    const usd = costUsd("claude-haiku-5-5", {
      input_tokens: 0,
      output_tokens: 0,
      cache_creation_input_tokens: 2_000_000,
      cache_read_input_tokens: 4_000_000,
    });
    expect(usd).toBeCloseTo(2 * p.cacheWritePerM + 4 * p.cacheReadPerM, 6);
  });

  it("treats missing cache fields as zero", () => {
    expect(costUsd("claude-haiku-5-5", { input_tokens: 0, output_tokens: 0 })).toBe(0);
  });

  it("charges an unknown model at the dearest known price, so the cap errs on the safe side", () => {
    const dearest = Math.max(...Object.keys(PRICES).map((m) => costUsd(m, { input_tokens: 1_000_000, output_tokens: 1_000_000 })));
    expect(costUsd("some-future-model", { input_tokens: 1_000_000, output_tokens: 1_000_000 })).toBeCloseTo(dearest, 6);
  });

  it("is cheaper on haiku than on sonnet for the same usage", () => {
    const u = { input_tokens: 10_000, output_tokens: 2_000 };
    expect(costUsd("claude-haiku-5-5", u)).toBeLessThan(costUsd("claude-sonnet-5-5", u));
  });
});

describe("costGbp", () => {
  it("converts dollars to pounds", () => {
    const u = { input_tokens: 1_000_000, output_tokens: 0 };
    expect(costGbp("claude-sonnet-5-5", u)).toBeCloseTo(costUsd("claude-sonnet-5-5", u) * USD_TO_GBP, 6);
  });
});
