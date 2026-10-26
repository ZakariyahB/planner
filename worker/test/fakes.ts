/** An in-memory KV namespace for tests. */
export class FakeKV {
  readonly store = new Map<string, string>();

  async get(key: string): Promise<string | null> {
    return this.store.get(key) ?? null;
  }

  async put(key: string, value: string): Promise<void> {
    this.store.set(key, value);
  }
}

export const asKv = (kv: FakeKV) => kv as unknown as KVNamespace;
