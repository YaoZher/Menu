import { $, element, icon, api, writeApi, safeVideoUrl, toast, loading, emptyState, authExpired } from './common.js';
import { getNoteLines, renderNoteContent } from './notes.js';

let entries = [];
let initialized = false;
let ready = false;
let loadingPromise = null;
let revision = 0;
let sessionEpoch = 0;
let onEditDish = () => {};
let onChanged = () => {};
const busy = new Set();
const adding = new Map();
const optimisticChecks = new Map();
const expanded = new Set();

function changed() {
  $('#shopping-count').textContent = String(entries.length);
  document.dispatchEvent(new CustomEvent('shopping:changed'));
  onChanged(entries);
}

export function initializeShopping(options = {}) {
  onEditDish = options.onEditDish || onEditDish;
  onChanged = options.onChanged || onChanged;
  if (initialized) return;
  initialized = true;
  $('#refresh-shopping').addEventListener('click', () => loadShopping());
}

export function isInShopping(dishId) {
  return entries.some(entry => String(entry.dish.id) === String(dishId));
}

export function clearShoppingState() {
  sessionEpoch += 1;
  revision += 1;
  entries = []; ready = false;
  busy.clear(); adding.clear(); optimisticChecks.clear(); expanded.clear();
  $('#shopping-content').replaceChildren();
  changed();
}

function updateEntry(updated) {
  const index = entries.findIndex(entry => entry.id === updated.id);
  if (index < 0) entries.push(updated); else entries[index] = updated;
}

export async function loadShopping() {
  if (loadingPromise) return loadingPromise;
  if (busy.size || adding.size) return entries;
  const version = revision;
  const content = $('#shopping-content');
  if (!ready) loading(content, '');
  $('#refresh-shopping').disabled = true;
  loadingPromise = (async () => {
    try {
      const data = await api('/api/chef/shopping');
      if (!Array.isArray(data)) throw new Error('清单读取失败，请重试。');
      if (version !== revision) return entries;
      entries = data; ready = true;
      renderShopping(); changed();
    } catch (error) {
      if (version !== revision || authExpired(error)) return entries;
      content.setAttribute('aria-busy', 'false');
      if (ready) toast('刷新失败', error.message);
      else content.replaceChildren(emptyState({ title: '加载失败', message: error.message, actionText: '重试', action: loadShopping, symbol: 'refresh' }));
    } finally {
      loadingPromise = null;
      $('#refresh-shopping').disabled = false;
    }
    return entries;
  })();
  return loadingPromise;
}

export async function addToShopping(dish) {
  const epoch = sessionEpoch;
  if (!ready) await loadShopping();
  if (epoch !== sessionEpoch) return;
  if (!ready) throw new Error('清单未读取完成，请刷新待采购后重试。');
  const existing = entries.find(entry => entry.dish.id === dish.id);
  if (existing) return existing;
  if (adding.has(dish.id)) return adding.get(dish.id);
  revision += 1;
  const request = (async () => {
    const entry = await writeApi('/api/chef/shopping', {
      headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ dishId: dish.id })
    });
    if (epoch !== sessionEpoch) return entry;
    revision += 1;
    updateEntry(entry);
    renderShopping(); changed();
    return entry;
  })();
  adding.set(dish.id, request);
  try { return await request; }
  finally { adding.delete(dish.id); }
}

function iconButton(symbol, label, action) {
  const button = element('button', 'icon-button');
  button.type = 'button'; button.title = label; button.setAttribute('aria-label', label);
  button.append(icon(symbol)); button.addEventListener('click', action);
  return button;
}

function shoppingCard(entry) {
  const card = element('article', 'shopping-card');
  card.dataset.shoppingId = entry.id;
  card.dataset.dishVersion = entry.dish.version;
  const heading = element('div', 'shopping-card-heading');
  const title = element('h2', '', entry.dish.name);
  title.id = `shopping-dish-${entry.id}`;
  card.setAttribute('aria-labelledby', title.id);
  const actions = element('div', 'shopping-card-actions');
  actions.append(
    iconButton('edit', `修改${entry.dish.name}的原料与做法`, () => onEditDish(entry.dish)),
    iconButton('close', `将${entry.dish.name}移出待采购`, () => removeEntry(entry))
  );
  heading.append(title, actions); card.append(heading);
  const checked = item => optimisticChecks.has(item.id) ? optimisticChecks.get(item.id) : item.checked;
  if (entry.ingredients.length) {
    const count = entry.ingredients.filter(checked).length;
    const progress = element('span', 'shopping-progress', `${count} / ${entry.ingredients.length}`);
    progress.setAttribute('aria-label', `已采购 ${count} 项，共 ${entry.ingredients.length} 项`);
    card.append(progress);
    const list = element('ul', 'shopping-ingredients');
    for (const ingredient of entry.ingredients) {
      const row = element('li');
      const label = element('label', 'ingredient-row');
      const checkbox = element('input');
      checkbox.type = 'checkbox'; checkbox.checked = checked(ingredient);
      checkbox.id = `ingredient-${ingredient.id}`;
      checkbox.dataset.ingredientId = ingredient.id;
      checkbox.addEventListener('change', () => {
        const current = entries.find(item => item.id === entry.id);
        const currentIngredient = current?.ingredients.find(item => item.id === ingredient.id);
        if (currentIngredient) setChecked(current, currentIngredient, checkbox.checked, checkbox.id);
      });
      label.append(checkbox, element('span', '', ingredient.text));
      row.append(label); list.append(row);
    }
    card.append(list);
  } else {
    const add = element('button', 'text-button shopping-add-ingredients', '添加原料');
    add.type = 'button'; add.addEventListener('click', () => onEditDish(entry.dish));
    card.append(add);
  }
  const preparation = getNoteLines(entry.dish).filter(line => line.type !== 'INGREDIENT');
  const video = safeVideoUrl(entry.dish.videoUrl);
  if (preparation.length || video) {
    const details = element('details', 'shopping-prep');
    details.open = expanded.has(entry.id);
    const summary = element('summary');
    summary.append(element('span', '', '做法'), icon('chevron'));
    details.append(summary);
    const notes = renderNoteContent({ noteLines: preparation });
    if (notes) details.append(notes);
    if (video) {
      const link = element('a', 'video-link', '视频');
      link.href = video; link.target = '_blank'; link.rel = 'noopener noreferrer';
      link.setAttribute('aria-label', `观看${entry.dish.name}的视频（新窗口打开）`);
      details.append(link);
    }
    details.addEventListener('toggle', () => { if (details.open) expanded.add(entry.id); else expanded.delete(entry.id); });
    card.append(details);
  }
  if (busy.has(entry.id)) card.querySelectorAll('button, input').forEach(control => { control.disabled = true; });
  return card;
}

function refreshCard(entry, focusId) {
  const card = [...$('#shopping-content').querySelectorAll('.shopping-card')]
    .find(node => node.dataset.shoppingId === String(entry.id));
  if (!card) { renderShopping(focusId); return; }
  const checkboxes = [...card.querySelectorAll('.shopping-ingredients input[type="checkbox"]')];
  const sameRecipe = card.dataset.dishVersion === String(entry.dish.version)
    && checkboxes.length === entry.ingredients.length
    && checkboxes.every((input, index) => input.dataset.ingredientId === String(entry.ingredients[index].id));
  if (sameRecipe) {
    const disabled = busy.has(entry.id);
    let checkedCount = 0;
    checkboxes.forEach((input, index) => {
      const ingredient = entry.ingredients[index];
      input.checked = optimisticChecks.has(ingredient.id) ? optimisticChecks.get(ingredient.id) : ingredient.checked;
      input.disabled = disabled;
      if (input.checked) checkedCount += 1;
    });
    card.querySelectorAll('button').forEach(button => { button.disabled = disabled; });
    card.setAttribute('aria-busy', String(disabled));
    const progress = card.querySelector('.shopping-progress');
    if (progress) {
      progress.textContent = `${checkedCount} / ${entry.ingredients.length}`;
      progress.setAttribute('aria-label', `已采购 ${checkedCount} 项，共 ${entry.ingredients.length} 项`);
    }
  } else {
    const active = document.activeElement;
    if (card.contains(active) && active.matches('input[type="checkbox"]')) focusId = active.id;
    card.replaceWith(shoppingCard(entry));
  }
  if (focusId && !$('#shopping-view').hidden) document.getElementById(focusId)?.focus({ preventScroll: true });
}

function renderShopping(focusId) {
  const content = $('#shopping-content');
  const active = document.activeElement;
  const activeIngredient = content.contains(active) && active.matches('input[type="checkbox"]') ? active.id : null;
  content.setAttribute('aria-busy', 'false');
  if (!entries.length) {
    content.replaceChildren(emptyState({ title: '暂无待采购', actionText: '选菜', action: () => {
      const menuLink = document.querySelector('nav a[href="/"]');
      if (menuLink) menuLink.click(); else location.assign('/');
    }, symbol: 'bowl' }));
  } else {
    const grid = element('div', 'shopping-grid');
    entries.forEach(entry => grid.append(shoppingCard(entry)));
    content.replaceChildren(grid);
  }
  const restoreId = activeIngredient || focusId;
  if (restoreId && !$('#shopping-view').hidden) document.getElementById(restoreId)?.focus({ preventScroll: true });
}

async function setChecked(entry, ingredient, checked, focusId) {
  if (busy.has(entry.id)) return;
  const epoch = sessionEpoch;
  revision += 1; busy.add(entry.id); optimisticChecks.set(ingredient.id, checked);
  refreshCard(entry);
  try {
    const updated = await writeApi(`/api/chef/shopping/${encodeURIComponent(entry.id)}/ingredients/${encodeURIComponent(ingredient.id)}`, {
      method: 'PATCH', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ checked })
    });
    if (epoch !== sessionEpoch) return;
    revision += 1; updateEntry(updated); changed();
  } catch (error) {
    if (epoch === sessionEpoch && !authExpired(error)) toast('未能保存', error.message);
  } finally {
    if (epoch === sessionEpoch) {
      optimisticChecks.delete(ingredient.id); busy.delete(entry.id);
      const restoreFocus = document.activeElement === document.body || document.activeElement?.id === focusId;
      const current = entries.find(item => item.id === entry.id);
      if (current) refreshCard(current, restoreFocus ? focusId : null);
    }
  }
}

async function removeEntry(entry) {
  if (busy.has(entry.id)) return;
  const epoch = sessionEpoch;
  revision += 1; busy.add(entry.id); renderShopping();
  try {
    await writeApi(`/api/chef/shopping/${encodeURIComponent(entry.id)}`, { method: 'DELETE' });
    if (epoch !== sessionEpoch) return;
    revision += 1;
    entries = entries.filter(item => item.id !== entry.id); expanded.delete(entry.id);
    changed(); toast('已移出待采购', entry.dish.name, 2500);
  } catch (error) {
    if (epoch === sessionEpoch && !authExpired(error)) toast('移除失败', error.message);
  } finally { if (epoch === sessionEpoch) { busy.delete(entry.id); renderShopping(); } }
}
