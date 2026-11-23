import { $, element, api, writeApi, toast, setButtonPending } from './common.js';

const sameOrder = (left, right) => left.length === right.length && left.every((id, index) => id === right[index]);

export function createMenuSorter({ isChef, getDishes, setDishes, invalidate, resetFilters, refresh }) {
  const actions = $('#menu-sort-actions');
  const toggle = $('#toggle-menu-sort');
  const cancel = $('#cancel-menu-sort');
  const content = $('#menu-content');
  const toolbar = $('.menu-toolbar');
  const tags = $('#menu-tag-filter');
  const reducedMotion = matchMedia('(prefers-reduced-motion: reduce)');
  const announcement = element('span', 'visually-hidden');
  announcement.setAttribute('role', 'status');
  actions.append(announcement);
  let mode = 'off';
  let visible = !$('#menu').hidden;
  let original = [];
  let revision = 0;
  let drag = null;
  let scrollFrame = 0;
  let addWasDisabled = false;
  const animations = new Map();
  const ids = () => getDishes().map(dish => dish.id);
  const active = () => mode !== 'off';
  const editing = () => mode === 'editing';

  function syncCards() {
    const cards = [...content.querySelectorAll('.dish-card')];
    content.classList.toggle('is-sorting', active());
    cards.forEach((card, index) => {
      const handle = card.querySelector('.sort-handle');
      if (handle) {
        handle.hidden = !active();
        handle.disabled = !editing();
        handle.setAttribute('aria-label', `拖动排序：${handle.dataset.dishName}，第 ${index + 1} 项，共 ${cards.length} 项；可用方向键调整`);
      }
      const link = card.querySelector('.dish-card-link');
      if (link) { link.inert = active(); link.tabIndex = active() ? -1 : 0; }
      const shopping = card.querySelector('.shopping-add-button');
      if (shopping) shopping.hidden = active();
    });
  }

  function syncControls() {
    actions.hidden = !isChef || !visible;
    if (mode === 'loading' || mode === 'saving') {
      setButtonPending(toggle, true, mode === 'loading' ? '读取中…' : '保存中…');
    } else {
      setButtonPending(toggle, false);
      toggle.textContent = active() ? '保存' : '开启排序';
    }
    toggle.setAttribute('aria-pressed', String(active()));
    cancel.hidden = !active(); cancel.disabled = !editing();
    toolbar.inert = active(); tags.inert = active();
    toolbar.classList.toggle('sort-controls-disabled', active());
    tags.classList.toggle('sort-controls-disabled', active());
    syncCards();
  }

  function stopAnimations() {
    for (const animation of animations.values()) animation.cancel();
    animations.clear();
  }

  function place(next, animate = true) {
    const focused = document.activeElement;
    const before = new Map([...content.querySelectorAll('.dish-card')].map(card => [card, card.getBoundingClientRect()]));
    stopAnimations();
    setDishes(next);
    if (focused?.isConnected && focused.matches('.sort-handle')) focused.focus({ preventScroll: true });
    if (!animate || reducedMotion.matches || !visible) return;
    for (const [card, old] of before) {
      if (!card.isConnected) continue;
      const current = card.getBoundingClientRect();
      const x = old.left - current.left; const y = old.top - current.top;
      if (Math.abs(x) < 1 && Math.abs(y) < 1) continue;
      const animation = card.animate([{ transform: `translate(${x}px, ${y}px)` }, { transform: 'translate(0, 0)' }], {
        duration: 180, easing: 'cubic-bezier(.2,.75,.25,1)'
      });
      animations.set(card, animation);
      animation.finished.then(() => { if (animations.get(card) === animation) animations.delete(card); }, () => {});
    }
  }

  function move(id, index) {
    const next = [...getDishes()];
    const from = next.findIndex(dish => dish.id === id);
    if (from < 0 || index < 0 || index >= next.length || from === index) return;
    next.splice(index, 0, next.splice(from, 1)[0]);
    place(next);
    announcement.textContent = `${next[index].name}，第 ${index + 1} 项，共 ${next.length} 项`;
  }

  function finishDrag(restore = false) {
    if (!drag) return;
    const current = drag; drag = null;
    cancelAnimationFrame(scrollFrame); scrollFrame = 0;
    current.ghost.remove(); current.card.classList.remove('sort-dragging');
    document.body.classList.remove('menu-sort-dragging');
    if (current.handle.hasPointerCapture(current.pointerId)) current.handle.releasePointerCapture(current.pointerId);
    if (restore) place(current.before);
    if (visible && current.handle.isConnected) current.handle.focus({ preventScroll: true });
  }

  function targetAtPointer() {
    if (!drag || !drag.moved) return;
    const grid = content.querySelector('.menu-grid');
    if (!grid) return;
    const bounds = grid.getBoundingClientRect();
    if (drag.x < bounds.left - 40 || drag.x > bounds.right + 40) return;
    // Offset positions ignore the transient FLIP transform and prevent jitter during fast dragging.
    const cards = [...grid.children];
    let nearest = null; let distance = Infinity;
    cards.forEach((card, index) => {
      const rect = card.getBoundingClientRect();
      const transform = getComputedStyle(card).transform;
      const matrix = transform === 'none' ? null : new DOMMatrixReadOnly(transform);
      const left = rect.left - (matrix?.m41 || 0); const top = rect.top - (matrix?.m42 || 0);
      const dx = drag.x - Math.max(left, Math.min(drag.x, left + rect.width));
      const dy = drag.y - Math.max(top, Math.min(drag.y, top + rect.height));
      const score = dx * dx + dy * dy;
      if (score < distance) { distance = score; nearest = index; }
    });
    if (nearest !== null) move(drag.id, nearest);
  }

  function scrollWhileDragging() {
    if (!drag) return;
    const edge = 72;
    const speed = drag.y < edge ? -Math.ceil((edge - drag.y) / 5)
      : drag.y > innerHeight - edge ? Math.ceil((drag.y - innerHeight + edge) / 5) : 0;
    if (drag.moved && speed) { window.scrollBy(0, speed); targetAtPointer(); }
    scrollFrame = requestAnimationFrame(scrollWhileDragging);
  }

  function startDrag(event, dish, handle) {
    if (!editing() || drag || event.button !== 0 || !event.isPrimary) return;
    event.preventDefault();
    handle.focus({ preventScroll: true });
    const card = handle.closest('.dish-card');
    const rect = card.getBoundingClientRect();
    const ghost = card.cloneNode(true);
    ghost.classList.add('sort-ghost'); ghost.setAttribute('aria-hidden', 'true'); ghost.inert = true;
    Object.assign(ghost.style, { width: `${rect.width}px`, height: `${rect.height}px`, left: `${rect.left}px`, top: `${rect.top}px` });
    document.body.append(ghost);
    drag = { id: dish.id, handle, card, ghost, pointerId: event.pointerId, before: [...getDishes()], x: event.clientX, y: event.clientY,
      startX: event.clientX, startY: event.clientY, moved: false };
    card.classList.add('sort-dragging'); document.body.classList.add('menu-sort-dragging');
    handle.setPointerCapture(event.pointerId);
    scrollFrame = requestAnimationFrame(scrollWhileDragging);
  }

  function createHandle(dish) {
    const handle = element('button', 'icon-button sort-handle');
    handle.type = 'button'; handle.hidden = true; handle.dataset.dishName = dish.name;
    handle.title = '拖动排序';
    const grip = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
    grip.setAttribute('class', 'sort-grip'); grip.setAttribute('viewBox', '0 0 24 24'); grip.setAttribute('aria-hidden', 'true');
    for (const x of [9, 15]) for (const y of [6, 12, 18]) {
      const dot = document.createElementNS('http://www.w3.org/2000/svg', 'circle');
      dot.setAttribute('cx', x); dot.setAttribute('cy', y); dot.setAttribute('r', '1.5'); grip.append(dot);
    }
    handle.append(grip);
    handle.addEventListener('pointerdown', event => startDrag(event, dish, handle));
    handle.addEventListener('keydown', event => {
      if (!editing() || drag) return;
      const delta = { ArrowUp: -1, ArrowLeft: -1, ArrowDown: 1, ArrowRight: 1 }[event.key];
      if (delta === undefined && event.key !== 'Home' && event.key !== 'End') return;
      event.preventDefault();
      const order = ids();
      move(dish.id, event.key === 'Home' ? 0 : event.key === 'End' ? order.length - 1 : order.indexOf(dish.id) + delta);
    });
    return handle;
  }

  async function begin() {
    if (!isChef || active()) return;
    mode = 'loading'; invalidate(); addWasDisabled = $('#add-dish').disabled; $('#add-dish').disabled = true; syncControls();
    try {
      let snapshot = null;
      for (let attempt = 0; attempt < 3; attempt += 1) {
        const [menu, order] = await Promise.all([api('/api/menu'), api('/api/chef/dishes/order')]);
        if (!Array.isArray(menu) || !Array.isArray(order?.dishIds) || !Number.isSafeInteger(order.revision)) throw new Error('排序暂时无法读取，请重试。');
        const byId = new Map(menu.map(dish => [dish.id, dish]));
        if (order.dishIds.length === byId.size && new Set(order.dishIds).size === byId.size && order.dishIds.every(id => byId.has(id))) {
          snapshot = { dishes: order.dishIds.map(id => byId.get(id)), revision: order.revision }; break;
        }
      }
      if (!snapshot) throw new Error('菜单刚刚发生变化，请重新开启排序。');
      original = snapshot.dishes; revision = snapshot.revision;
      resetFilters(); mode = 'editing'; setDishes([...original]); syncControls();
      announcement.textContent = '排序已开启，可拖动菜品旁的手柄，或使用方向键调整。';
    } catch (error) {
      mode = 'off'; $('#add-dish').disabled = addWasDisabled; syncControls();
      toast('无法开启排序', error.message);
    }
  }

  async function save() {
    if (!editing()) return;
    finishDrag(); mode = 'saving'; syncControls();
    try {
      await writeApi('/api/chef/dishes/order', { method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({
        dishIds: ids(), expectedDishIds: original.map(dish => dish.id), expectedRevision: revision
      }) });
      mode = 'off'; original = []; $('#add-dish').disabled = addWasDisabled; syncControls();
      // Refresh after leaving edit mode so card actions and any concurrent dish edits are current.
      setDishes([...getDishes()]); toast('排序已保存', '', 3500); await refresh();
    } catch (error) {
      mode = 'editing'; syncControls();
      toast('排序未保存', error.status === 409 ? '菜单已发生变化，请撤销后重新开启排序。' : error.message);
    }
  }

  function undo() {
    if (!editing()) return;
    finishDrag(); const restored = [...original]; original = []; mode = 'off';
    $('#add-dish').disabled = addWasDisabled; syncControls(); place(restored);
    announcement.textContent = '已撤销排序'; toggle.focus({ preventScroll: true });
    refresh();
  }

  if (isChef) {
    toggle.addEventListener('click', () => active() ? save() : begin());
    cancel.addEventListener('click', undo);
    window.addEventListener('pointermove', event => {
      if (!drag || event.pointerId !== drag.pointerId) return;
      event.preventDefault();
      drag.x = event.clientX; drag.y = event.clientY;
      drag.moved ||= Math.hypot(drag.x - drag.startX, drag.y - drag.startY) > 5;
      drag.ghost.style.transform = `translate(${drag.x - drag.startX}px, ${drag.y - drag.startY}px)`;
      targetAtPointer();
    }, { passive: false });
    window.addEventListener('pointerup', event => { if (event.pointerId === drag?.pointerId) finishDrag(); });
    window.addEventListener('pointercancel', event => { if (event.pointerId === drag?.pointerId) finishDrag(true); });
    window.addEventListener('blur', () => finishDrag());
    document.addEventListener('keydown', event => { if (event.key === 'Escape' && drag) { event.preventDefault(); finishDrag(true); } });
    document.addEventListener('visibilitychange', () => { if (document.hidden) finishDrag(); });
    window.addEventListener('beforeunload', event => {
      if (mode === 'saving' || (editing() && !sameOrder(ids(), original.map(dish => dish.id)))) { event.preventDefault(); event.returnValue = ''; }
    });
    reducedMotion.addEventListener('change', () => { if (reducedMotion.matches) stopAnimations(); });
  }
  syncControls();
  return { createHandle, syncCards, isActive: active, setVisible(value) { visible = value; if (!visible) finishDrag(); syncControls(); } };
}
