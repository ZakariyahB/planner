/** The key for [now]'s month. Only token counts and a running total are stored, never message content. */
export function monthKey(now: Date): string {
  return `spend:${now.getUTCFullYear()}-${String(now.getUTCMonth() + 1).padStart(2, "0")}`;
}

export async function getSpendGbp(kv: KVNamespace, now: Date): Promise<number> {
  const stored = Number(await kv.get(monthKey(now)));
  return Number.isFinite(stored) && stored > 0 ? stored : 0;
}

/** Adds to this month's total. Not atomic, which is fine for a single user making one call at a time. */
export async function addSpendGbp(kv: KVNamespace, now: Date, amount: number): Promise<void> {
  if (!Number.isFinite(amount) || amount <= 0) return;
  const total = (await getSpendGbp(kv, now)) + amount;
  await kv.put(monthKey(now), String(total));
}
