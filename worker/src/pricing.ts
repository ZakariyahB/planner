/**
 * Dollars per million tokens. These are ESTIMATES used only to enforce the monthly spend cap:
 * check them against Anthropic's pricing page and edit this table if they have drifted.
 * Unknown models are charged at the dearest price here, so the cap errs on the safe side.
 */
export interface Price {
  inputPerM: number;
  outputPerM: number;
  cacheWritePerM: number;
  cacheReadPerM: number;
}

export const PRICES: Record<string, Price> = {
  "claude-sonnet-5-5": { inputPerM: 3, outputPerM: 15, cacheWritePerM: 3.75, cacheReadPerM: 0.3 },
  "claude-haiku-5-5": { inputPerM: 1, outputPerM: 5, cacheWritePerM: 1.25, cacheReadPerM: 0.1 },
};

/** Approximate exchange rate for the cap, which is set in pounds. */
export const USD_TO_GBP = 0.8;

export interface Usage {
  input_tokens: number;
  output_tokens: number;
  cache_creation_input_tokens?: number;
  cache_read_input_tokens?: number;
}

function dearest(): Price {
  return Object.values(PRICES).reduce((a, b) => (b.outputPerM > a.outputPerM ? b : a));
}

export function costUsd(model: string, usage: Usage): number {
  const p = PRICES[model] ?? dearest();
  return (
    (usage.input_tokens * p.inputPerM +
      usage.output_tokens * p.outputPerM +
      (usage.cache_creation_input_tokens ?? 0) * p.cacheWritePerM +
      (usage.cache_read_input_tokens ?? 0) * p.cacheReadPerM) /
    1_000_000
  );
}

export function costGbp(model: string, usage: Usage): number {
  return costUsd(model, usage) * USD_TO_GBP;
}
