import { $, element, icon, api, writeApi, safeVideoUrl, safeImageUrl, toast, showError, loading, emptyState, setDialogBusy, setButtonPending, authExpired } from './common.js';
import { getNoteLines, insertNoteMarker } from './notes.js';
import { ImageValidationError, prepareUploadImage, validateImageDimensions, extractVideoUrl } from './submission-input.js';
import { createTagPicker, loadTagCatalog, matchesTagFilters, renderTagChips } from './tags.js';
import { createDishImage } from './dish-image.js';
import { createMenuSorter } from './menu-sort.js';

let dishes = [];
let activeFilter = 'all';
let editDish = null;
let deleteDish = null;
let selectedFile = null;
let imageAction = 'KEEP';
let previewUrl = null;
let imageSequence = 0;
let readingImage = false;
let imageValidationError = '';
let initialized = false;
let chefMode = false;
let addToShopping = null;
let isInShopping = () => false;
let menuLoaded = false;
let menuLoadSequence = 0;
let menuRequest = null;
let tagEditor = null;
let tagFilter = null;
let activeTagFilters = [];
let detailDishId = null;
let detailSequence = 0;
let renderedDetail = null;
let sorter = null;
const cardsById = new Map();
const addingDishIds = new Set();
const content = $('#menu-content');
const dishDialog = $('#dish-dialog');
const deleteDialog = $('#delete-dialog');
const dishForm = $('#dish-form');
const dishError = $('#dish-error');

export function initializeMenu(options) {
  if (initialized) return Promise.resolve();
  chefMode = Boolean(options.isChef);
  addToShopping = options.addToShopping;
  isInShopping = options.isInShopping || (() => false);
  tagEditor = createTagPicker($('#dish-tag-picker'), { isChef: chefMode });
  tagFilter = createTagPicker($('#menu-tag-picker'), { mode: 'filter', onChange: selected => {
    activeTagFilters = selected;
    $('#tag-filter-count').textContent = selected.length ? `${selected.length} 项` : '全部';
    $('#menu-tag-filter-summary').title = selected.map(tag => tag.name).join('、');
    $('#clear-tag-filter').disabled = selected.length === 0;
    renderMenu();
    document.dispatchEvent(new CustomEvent('menu:filter-changed', { detail: {
      scope: 'menu', key: `${activeFilter}:${selected.map(tag => tag.id).sort().join(',')}`, direction: 1
    } }));
  } });
  sorter = createMenuSorter({
    isChef: chefMode,
    getDishes: () => dishes,
    setDishes: next => { dishes = next; menuLoaded = true; renderMenu(); },
    invalidate: () => {
      menuLoadSequence += 1;
      menuRequest = null;
      content.removeAttribute('data-page-loading');
    },
    resetFilters: () => {
      $('#menu-search').value = '';
      activeTagFilters = []; tagFilter.setSelection([], true); setFilter('all');
      $('#menu-tag-filter').open = false;
    },
    refresh: () => refreshMenu({ afterWrite: true })
  });
  initialized = true;
  return Promise.all([refreshMenu(), loadTagCatalog()]);
}

function updateShoppingButton(button) {
  const id = Number(button.dataset.dishId);
  const selected = isInShopping(id);
  const busy = addingDishIds.has(id);
  setButtonPending(button, busy);
  button.disabled = selected || busy || Boolean(sorter?.isActive());
  button.classList.toggle('is-selected', selected);
  button.setAttribute('aria-pressed', String(selected));
  const action = busy ? '正在加入待采购' : selected ? '已加入待采购' : '加入待采购';
  const label = `${action}：${button.dataset.dishName}`;
  button.setAttribute('aria-label', label);
  button.title = action;
  if (!button.firstChild || !busy) button.replaceChildren(icon(selected ? 'check' : 'plus'));
}

function updateShoppingButtons() {
  document.querySelectorAll('.shopping-add-button').forEach(updateShoppingButton);
}

async function addDishToShopping(dish) {
  if (sorter?.isActive() || !addToShopping || addingDishIds.has(dish.id) || isInShopping(dish.id)) return;
  addingDishIds.add(dish.id);
  updateShoppingButtons();
  try {
    await addToShopping(dish);
    toast('已加入待采购', dish.name, 3500);
  } catch (error) {
    toast('添加失败', error.message);
  } finally {
    addingDishIds.delete(dish.id);
    updateShoppingButtons();
  }
}

function imageFallback(cover) {
  cover.replaceChildren(icon('bowl', 'bowl-icon'));
}

function makeCover(dish, className = 'dish-cover', eager = false) {
  const cover = element('div', className);
  const imageUrl = safeImageUrl(dish.imageUrl);
  if (imageUrl) {
    cover.append(createDishImage(imageUrl, dish.name, { eager, onError: () => imageFallback(cover) }));
  } else imageFallback(cover);
  return cover;
}

function plainNotes(dish) {
  return getNoteLines(dish).filter(line => line.type === 'NOTE').map(line => line.text).join('\n');
}

function makeShoppingButton(dish) {
  const add = element('button', 'icon-button shopping-add-button');
  add.type = 'button';
  add.dataset.dishId = dish.id;
  add.dataset.dishName = dish.name;
  updateShoppingButton(add);
  add.addEventListener('click', () => addDishToShopping(dish));
  return add;
}

function makeCard(dish) {
  const card = element('article', 'dish-card');
  card.dataset.dishId = dish.id;
  const link = element('a', 'dish-card-link');
  link.href = `/dish/${dish.id}`;
  link.setAttribute('aria-label', `查看${dish.name}详情`);
  link.addEventListener('click', event => {
    if (sorter?.isActive()) { event.preventDefault(); return; }
    if (event.button !== 0 || event.ctrlKey || event.metaKey || event.shiftKey || event.altKey) return;
    event.preventDefault();
    document.dispatchEvent(new CustomEvent('menu:open-dish', { detail: { id: dish.id } }));
  });
  const body = element('div', 'dish-body');
  body.append(element('h3', '', dish.name));
  body.append(renderTagChips(dish.tags));
  const notes = plainNotes(dish);
  if (notes) body.append(element('p', 'dish-notes', notes));
  link.append(makeCover(dish), body);
  const actions = element('div', 'card-actions');
  actions.append(makeShoppingButton(dish));
  if (chefMode) actions.append(sorter.createHandle(dish));
  card.append(link, actions);
  return card;
}

function renderDishDetail(dish) {
  const detail = $('#dish-detail-content');
  detail.setAttribute('aria-busy', 'false');
  if (!dish) {
    renderedDetail = null;
    detail.replaceChildren(emptyState({ title: '菜品已移除', actionText: '返回菜单', action: () => {
      document.dispatchEvent(new CustomEvent('menu:open-menu'));
    } }));
    return;
  }
  const fingerprint = JSON.stringify(dish);
  if (location.pathname === `/dish/${dish.id}`) document.title = `${dish.name} · 菜单`;
  if (renderedDetail === fingerprint && detail.querySelector('.dish-detail')) {
    updateShoppingButtons();
    return;
  }
  renderedDetail = fingerprint;
  const article = element('article', 'dish-detail dish-detail-layout');
  const body = element('div', 'dish-detail-body');
  const heading = element('div', 'dish-detail-heading');
  heading.append(element('h1', '', dish.name));
  body.append(heading, renderTagChips(dish.tags));
  const notes = plainNotes(dish);
  if (notes) body.append(element('p', 'dish-notes', notes));
  const video = safeVideoUrl(dish.videoUrl);
  if (video) {
    const link = element('a', 'video-link');
    link.href = video; link.target = '_blank'; link.rel = 'noopener noreferrer';
    link.setAttribute('aria-label', `观看${dish.name}的视频（新窗口打开）`);
    link.append(icon('play'), element('span', '', '视频'));
    body.append(link);
  }
  const actions = element('div', 'dish-detail-actions');
  const edit = element('button', 'text-button');
  edit.type = 'button'; edit.append(icon('edit'), element('span', '', '编辑'));
  edit.setAttribute('aria-label', `${chefMode ? '修改' : '申请修改'}${dish.name}`);
  edit.addEventListener('click', () => openDishEditor(dish));
  const remove = element('button', 'text-button danger-text');
  remove.type = 'button'; remove.append(icon('trash'), element('span', '', '删除'));
  remove.setAttribute('aria-label', `${chefMode ? '删除' : '申请删除'}${dish.name}`);
  remove.addEventListener('click', () => openDelete(dish));
  actions.append(edit, remove, makeShoppingButton(dish));
  body.append(actions);
  article.append(makeCover(dish, 'dish-detail-cover', true), body);
  detail.replaceChildren(article);
}

export async function showDishDetail(id) {
  detailDishId = id;
  const sequence = ++detailSequence;
  const detail = $('#dish-detail-content');
  if (!menuLoaded) {
    loading(detail, '');
    await refreshMenu();
    if (sequence !== detailSequence) return;
    if (!menuLoaded) {
      detail.setAttribute('aria-busy', 'false');
      detail.replaceChildren(emptyState({ title: '加载失败', actionText: '重试', action: () => showDishDetail(id), symbol: 'refresh' }));
      return;
    }
  }
  renderDishDetail(dishes.find(dish => dish.id === id));
}

function renderMenu() {
  if (!menuLoaded) return;
  content.setAttribute('aria-busy', 'false');
  $('#menu-count').textContent = String(dishes.length);
  const query = $('#menu-search').value.trim().toLocaleLowerCase('zh-CN');
  const visible = dishes.filter(dish => (!query || `${dish.name} ${dish.notes || ''}`.toLocaleLowerCase('zh-CN').includes(query))
    && (activeFilter === 'all' || dish.tags?.some(tag => tag.name === activeFilter && tag.categoryName === '菜品类型'))
    && matchesTagFilters(dish.tags, activeTagFilters));
  const availableIds = new Set(dishes.map(dish => dish.id));
  for (const id of cardsById.keys()) if (!availableIds.has(id)) cardsById.delete(id);
  if (!dishes.length) {
    content.replaceChildren(emptyState({ title: '暂无菜品' }));
    return;
  }
  if (!visible.length) {
    content.replaceChildren(emptyState({ title: '未找到菜品', symbol: 'search', actionText: '重置', action: () => {
      $('#menu-search').value = ''; tagFilter.setSelection([], true); setFilter('all');
    } }));
    return;
  }
  const grid = content.querySelector('.menu-grid') || element('div', 'menu-grid');
  const nextCards = visible.map(dish => {
    const fingerprint = JSON.stringify(dish);
    let cached = cardsById.get(dish.id);
    if (!cached || cached.fingerprint !== fingerprint) {
      cached = { fingerprint, node: makeCard(dish) };
      cardsById.set(dish.id, cached);
    }
    return cached.node;
  });
  const selectedCards = new Set(nextCards);
  for (const node of [...grid.children]) if (!selectedCards.has(node)) node.remove();
  nextCards.forEach((node, index) => {
    if (grid.children[index] !== node) grid.insertBefore(node, grid.children[index] || null);
  });
  if (grid.parentNode !== content) content.replaceChildren(grid);
  updateShoppingButtons();
  sorter?.syncCards();
}

export function updateSortingVisibility(isMenu) { sorter?.setVisible(isMenu); }

export function refreshMenu({ afterWrite = false } = {}) {
  if (sorter?.isActive()) { renderMenu(); return; }
  if (menuRequest && !afterWrite) return menuRequest;
  const sequence = ++menuLoadSequence;
  // Restore the public menu from its own cache when returning from shopping; keep it visible while refreshing.
  if (menuLoaded) renderMenu(); else loading(content, '');
  content.toggleAttribute('data-page-loading', menuLoaded);
  menuRequest = (async () => {
    try {
      const result = await api('/api/menu');
      if (sequence !== menuLoadSequence || sorter?.isActive()) return;
      if (!Array.isArray(result)) throw new Error('菜单暂时无法读取，请重试。');
      dishes = result; menuLoaded = true; renderMenu();
      if (detailDishId !== null && !$('#dish-detail-view').hidden) renderDishDetail(dishes.find(dish => dish.id === detailDishId));
    } catch (error) {
      if (sequence !== menuLoadSequence) return;
      if (menuLoaded) toast('刷新失败', error.message);
      else content.replaceChildren(emptyState({ title: '加载失败', message: error.message, actionText: '重试', action: () => refreshMenu(), symbol: 'refresh' }));
    } finally {
      if (sequence === menuLoadSequence) {
        menuRequest = null;
        content.setAttribute('aria-busy', 'false');
        content.removeAttribute('data-page-loading');
      }
    }
  })();
  return menuRequest;
}

function setFilter(filter) {
  if (activeFilter === filter) return;
  const options = [...document.querySelectorAll('[data-filter]')].map(button => button.dataset.filter);
  const direction = Math.sign(options.indexOf(filter) - options.indexOf(activeFilter)) || 1;
  activeFilter = filter;
  document.querySelectorAll('[data-filter]').forEach(button => {
    const selected = button.dataset.filter === filter;
    button.classList.toggle('active', selected); button.setAttribute('aria-pressed', String(selected));
  });
  renderMenu();
  document.dispatchEvent(new CustomEvent('menu:filter-changed', { detail: { scope: 'menu', key: filter, direction } }));
}

function releasePreview() {
  if (previewUrl) URL.revokeObjectURL(previewUrl);
  previewUrl = null;
}

function setImagePreview(url, caption = '') {
  const image = $('#image-preview');
  image.hidden = !url;
  if (url) image.src = url; else image.removeAttribute('src');
  $('#upload-prompt').hidden = Boolean(url);
  $('#image-controls').hidden = !url;
  $('#image-caption').textContent = caption;
}

export function openDishEditor(dish = null) {
  if (!initialized || dishDialog.dataset.busy) return;
  if (sorter?.isActive()) { toast('请先保存或撤销排序'); return; }
  editDish = dish;
  imageSequence += 1; readingImage = false; imageValidationError = '';
  dishForm.reset(); releasePreview(); selectedFile = null; imageAction = 'KEEP';
  setDialogBusy(dishDialog, false);
  showError(dishError, '');
  $('#dish-dialog-title').textContent = dish ? '修改菜品' : '添加菜品';
  $('#dish-name').value = dish?.name || '';
  $('#dish-video').value = dish?.videoUrl || '';
  $('#dish-notes').value = dish?.notes || '';
  tagEditor.setSelection(dish?.tags || []);
  $('#notes-count').textContent = `${$('#dish-notes').value.length} / 2000`;
  $('#dish-submit').textContent = chefMode ? '保存' : '提交审核';
  setImagePreview(safeImageUrl(dish?.imageUrl), '当前图片');
  dishDialog.showModal();
  $('#dish-name').focus();
}

function openDelete(dish) {
  if (!initialized || deleteDialog.dataset.busy) return;
  deleteDish = dish;
  setDialogBusy(deleteDialog, false);
  const name = $('#delete-dish-name');
  name.textContent = dish.name;
  $('#delete-title').textContent = chefMode ? '删除菜品？' : '申请删除？';
  $('#delete-description').replaceChildren(document.createTextNode('「'), name,
    document.createTextNode(chefMode ? '」将从菜单和待采购中移除。' : '」将在审核通过后删除。'));
  $('#delete-submit').textContent = chefMode ? '删除' : '提交审核';
  showError($('#delete-error'), '');
  deleteDialog.showModal();
  $('[data-close="delete-dialog"]', $('#delete-form')).focus();
}

async function readImage(file) {
  if (!file) return;
  const sequence = ++imageSequence;
  readingImage = true; imageValidationError = '';
  showError(dishError, '');
  releasePreview(); selectedFile = null;
  setImagePreview(null);
  $('#image-controls').hidden = false;
  $('#image-caption').textContent = '正在读取图片…';
  let temporaryUrl = null;
  try {
    const normalizedFile = await prepareUploadImage(file);
    if (sequence !== imageSequence) return;
    temporaryUrl = URL.createObjectURL(normalizedFile);
    const image = new Image(); image.src = temporaryUrl; await image.decode();
    if (sequence !== imageSequence) return;
    validateImageDimensions(image.naturalWidth, image.naturalHeight);
    previewUrl = temporaryUrl; temporaryUrl = null;
    selectedFile = normalizedFile; imageAction = 'REPLACE';
    setImagePreview(previewUrl, `${file.name} · ${(file.size / 1024 / 1024).toFixed(2)} MB`);
  } catch (error) {
    if (sequence !== imageSequence) return;
    $('#dish-image').value = '';
    $('#image-caption').textContent = file.name;
    imageValidationError = error instanceof ImageValidationError ? error.message : '这张图片无法读取，请选择有效的 JPG 或 PNG 图片。';
    showError(dishError, imageValidationError);
  } finally {
    if (temporaryUrl) URL.revokeObjectURL(temporaryUrl);
    if (sequence === imageSequence) readingImage = false;
  }
}

$('#add-dish').addEventListener('click', () => openDishEditor());
document.addEventListener('shopping:changed', updateShoppingButtons);
$('#clear-tag-filter').addEventListener('click', () => tagFilter?.setSelection([], true));
document.querySelectorAll('[data-filter]').forEach(button => button.addEventListener('click', () => setFilter(button.dataset.filter)));
$('#menu-search').addEventListener('input', renderMenu);
$('#dish-notes').addEventListener('input', event => { $('#notes-count').textContent = `${event.target.value.length} / 2000`; });
document.querySelectorAll('[data-note-marker]').forEach(button => {
  button.addEventListener('mousedown', event => event.preventDefault());
  button.addEventListener('click', () => {
    const notes = $('#dish-notes');
    const updated = insertNoteMarker(notes.value, notes.selectionStart, notes.selectionEnd, button.dataset.noteMarker, notes.maxLength);
    if (!updated) { showError(dishError, '备注不能超过 2000 个字符。'); notes.focus(); return; }
    notes.value = updated.value;
    notes.focus();
    notes.setSelectionRange(updated.selectionStart, updated.selectionEnd);
    notes.dispatchEvent(new Event('input', { bubbles: true }));
  });
});
$('#dish-image').addEventListener('change', event => { readImage(event.target.files[0]); });
$('#remove-image').addEventListener('click', () => {
  imageSequence += 1; readingImage = false; imageValidationError = ''; releasePreview(); selectedFile = null;
  imageAction = editDish?.imageUrl ? 'REMOVE' : 'KEEP';
  $('#dish-image').value = ''; setImagePreview(null); showError(dishError, '');
});
dishDialog.addEventListener('close', () => { imageSequence += 1; readingImage = false; imageValidationError = ''; releasePreview(); });

dishForm.addEventListener('submit', async event => {
  event.preventDefault();
  if (!initialized || dishDialog.dataset.busy) return;
  showError(dishError, '');
  if (readingImage) { showError(dishError, '正在读取图片，请稍等一下再提交。'); return; }
  if (tagEditor?.isBusy()) { showError(dishError, '正在添加标签，请稍后保存。'); return; }
  if (!tagEditor?.isReady()) { showError(dishError, '请等待标签加载完成，或重试后保存。'); return; }
  const name = $('#dish-name').value.trim();
  const rawVideo = $('#dish-video').value;
  if (!name) { showError(dishError, '请填写菜名'); $('#dish-name').focus(); return; }
  if (imageValidationError) { showError(dishError, imageValidationError); $('#dish-image').focus(); return; }
  let videoUrl;
  try { videoUrl = extractVideoUrl(rawVideo); }
  catch (error) { showError(dishError, error.message); $('#dish-video').focus(); return; }
  $('#dish-video').value = videoUrl;
  if (!dishForm.reportValidity()) return;
  const form = new FormData();
  form.set('operation', editDish ? 'UPDATE' : 'CREATE');
  if (editDish) { form.set('dishId', editDish.id); form.set('expectedVersion', editDish.version); }
  form.set('name', name); form.set('videoUrl', videoUrl); form.set('notes', $('#dish-notes').value.trim()); form.set('imageAction', imageAction);
  form.set('tagIds', JSON.stringify(tagEditor.getSelection().map(tag => tag.id)));
  if (selectedFile) form.set('image', selectedFile);
  const submit = $('#dish-submit');
  setDialogBusy(dishDialog, true);
  setButtonPending(submit, true, chefMode ? '保存中…' : '正在提交…');
  try {
    const receipt = await writeApi(chefMode ? '/api/chef/dishes' : '/api/submissions', { body: form });
    dishDialog.close();
    if (chefMode) {
      toast('已保存', '', 3500);
      await refreshMenu({ afterWrite: true });
      document.dispatchEvent(new CustomEvent('menu:changed'));
    } else toast('已提交，待审核', `#${receipt.id}`, 5000);
  } catch (error) {
    if (!chefMode || !authExpired(error)) showError(dishError, error.message);
  } finally { setDialogBusy(dishDialog, false); setButtonPending(submit, false); }
});

$('#delete-form').addEventListener('submit', async event => {
  event.preventDefault();
  if (!initialized || deleteDialog.dataset.busy || !deleteDish) return;
  showError($('#delete-error'), '');
  const form = new FormData();
  form.set('operation', 'DELETE'); form.set('dishId', deleteDish.id); form.set('expectedVersion', deleteDish.version); form.set('imageAction', 'KEEP');
  const submit = $('#delete-submit');
  setDialogBusy(deleteDialog, true);
  setButtonPending(submit, true, chefMode ? '删除中…' : '正在提交…');
  try {
    const receipt = await writeApi(chefMode ? '/api/chef/dishes' : '/api/submissions', { body: form });
    deleteDialog.close();
    if (chefMode) {
      toast('已删除', '', 3500);
      await refreshMenu({ afterWrite: true });
      document.dispatchEvent(new CustomEvent('menu:changed'));
      if (detailDishId === deleteDish.id && !$('#dish-detail-view').hidden) document.dispatchEvent(new CustomEvent('menu:detail-missing'));
    } else toast('删除申请已提交', `#${receipt.id} · 待审核`, 5000);
  } catch (error) {
    if (!chefMode || !authExpired(error)) showError($('#delete-error'), error.message);
  } finally { setDialogBusy(deleteDialog, false); setButtonPending(submit, false); }
});
