// Keep confirmed server state separate from the user's latest, possibly unsaved choices.
export class OptimisticCheckQueue {
  constructor(ingredients) {
    this.confirmed = new Map(ingredients.map(item => [String(item.id), Boolean(item.checked)]));
    this.desired = new Map();
    this.active = null;
    this.sequence = 0;
  }

  set(id, checked) {
    id = String(id);
    if (!this.confirmed.has(id) || this.value(id) === Boolean(checked)) return false;
    this.desired.set(id, { checked: Boolean(checked), sequence: ++this.sequence });
    this.compact();
    return true;
  }

  value(id) {
    id = String(id);
    return this.desired.get(id)?.checked ?? this.confirmed.get(id) ?? false;
  }

  get pending() { return this.active !== null || this.desired.size > 0; }

  next() {
    if (this.active) return null;
    this.compact();
    const first = this.desired.entries().next();
    if (first.done) return null;
    const [id, intent] = first.value;
    this.active = { id, ...intent };
    return this.active;
  }

  resolve(operation, ingredients) {
    if (this.active !== operation) return { accepted: false, removed: 0 };
    if (this.desired.get(operation.id)?.sequence === operation.sequence) this.desired.delete(operation.id);
    this.active = null;
    return { accepted: true, removed: this.reconcile(ingredients) };
  }

  reconcile(ingredients) {
    this.confirmed = new Map(ingredients.map(item => [String(item.id), Boolean(item.checked)]));
    let removed = 0;
    for (const id of this.desired.keys()) {
      if (!this.confirmed.has(id)) { this.desired.delete(id); removed += 1; }
    }
    this.compact();
    return removed;
  }

  reject(operation) {
    if (this.active !== operation) return false;
    if (this.desired.get(operation.id)?.sequence === operation.sequence) this.desired.delete(operation.id);
    this.active = null;
    this.compact();
    return true;
  }

  cancel() { this.active = null; this.desired.clear(); }

  compact() {
    for (const [id, intent] of this.desired) {
      // An opposite request must remain queued while the earlier value is in flight.
      if (this.active?.id !== id && this.confirmed.get(id) === intent.checked) this.desired.delete(id);
    }
  }
}
