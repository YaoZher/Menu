import { $, element, icon, api, writeApi, safeVideoUrl, safeImageUrl, toast, showError, loading, emptyState, configureDialogs, setDialogBusy } from './common.js';
import { getNoteLines, renderNoteContent, insertNoteMarker } from './notes.js';
import { ImageValidationError, prepareUploadImage, validateImageDimensions, extractVideoUrl } from './submission-input.js';

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
const content = $('#menu-content');
const dishDialog = $('#dish-dialog');
const deleteDialog = $('#delete-dialog');
const dishForm = $('#dish-form');
const dishError = $('#dish-error');
configureDialogs();

function imageFallback(cover) {
  cover.replaceChildren(icon('bowl', 'bowl-icon'));
}

function makeCard(dish) {
  const card = element('article', 'dish-card');
  const cover = element('div', 'dish-cover');
  const imageUrl = safeImageUrl(dish.imageUrl);
  if (imageUrl) {
    const image = element('img');
    image.src = imageUrl; image.alt = dish.name; image.loading = 'lazy';
    image.addEventListener('error', () => imageFallback(cover), { once: true });
    cover.append(image);
  } else imageFallback(cover);
  const body = element('div', 'dish-body');
  body.append(element('h3', '', dish.name));
  const noteLines = getNoteLines(dish);
  const hasPreparation = noteLines.some(line => line.type !== 'NOTE');
  if (!hasPreparation && dish.notes) body.append(element('p', 'dish-notes', dish.notes));
  const meta = element('div', 'dish-meta');
  const video = safeVideoUrl(dish.videoUrl);
  if (video) {
    const link = element('a', 'video-link');
    link.href = video; link.target = '_blank'; link.rel = 'noopener noreferrer';
    link.setAttribute('aria-label', `观看${dish.name}的视频（新窗口打开）`);
    link.append(icon('play'), element('span', '', '视频'));
    meta.append(link);
    body.append(meta);
  }
  const actions = element('div', 'card-actions');
  const edit = element('button', 'icon-button');
  edit.type = 'button'; edit.append(icon('edit')); edit.title = '修改';
  edit.setAttribute('aria-label', `申请修改${dish.name}`);
  edit.addEventListener('click', () => openDish(dish));
  const remove = element('button', 'icon-button danger-text');
  remove.type = 'button'; remove.append(icon('trash')); remove.title = '删除';
  remove.setAttribute('aria-label', `申请删除${dish.name}`);
  remove.addEventListener('click', () => openDelete(dish));
  actions.append(edit, remove); card.append(cover, body, actions);
  if (hasPreparation) {
    const preparation = element('details', 'dish-prep');
    const summary = element('summary');
    summary.append(element('span', '', '备菜'), icon('chevron', 'icon prep-chevron'));
    preparation.append(summary, renderNoteContent(dish));
    card.append(preparation);
  }
  return card;
}

function renderMenu() {
  content.setAttribute('aria-busy', 'false');
  $('#menu-count').textContent = String(dishes.length);
  const query = $('#menu-search').value.trim().toLocaleLowerCase('zh-CN');
  const visible = dishes.filter(dish => (!query || `${dish.name} ${dish.notes || ''}`.toLocaleLowerCase('zh-CN').includes(query)) && (activeFilter === 'all' || activeFilter === 'image' && dish.imageUrl || activeFilter === 'video' && dish.videoUrl));
  if (!dishes.length) {
    content.replaceChildren(emptyState({ title: '暂无菜品' }));
    return;
  }
  if (!visible.length) {
    content.replaceChildren(emptyState({ title: '未找到菜品', symbol: 'search', actionText: '重置', action: () => { $('#menu-search').value = ''; setFilter('all'); } }));
    return;
  }
  const grid = element('div', 'menu-grid');
  visible.forEach(dish => grid.append(makeCard(dish)));
  content.replaceChildren(grid);
}

async function loadMenu() {
  loading(content, '');
  try {
    const result = await api('/api/menu');
    if (!Array.isArray(result)) throw new Error('菜单暂时无法读取，请重试。');
    dishes = result; renderMenu();
  } catch (error) {
    content.setAttribute('aria-busy', 'false');
    content.replaceChildren(emptyState({ title: '加载失败', message: error.message, actionText: '重试', action: loadMenu, symbol: 'refresh' }));
  }
}

function setFilter(filter) {
  activeFilter = filter;
  document.querySelectorAll('[data-filter]').forEach(button => {
    const selected = button.dataset.filter === filter;
    button.classList.toggle('active', selected); button.setAttribute('aria-pressed', String(selected));
  });
  renderMenu();
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

function openDish(dish = null) {
  editDish = dish;
  imageSequence += 1; readingImage = false; imageValidationError = '';
  dishForm.reset(); releasePreview(); selectedFile = null; imageAction = 'KEEP';
  setDialogBusy(dishDialog, false);
  showError(dishError, '');
  $('#dish-dialog-title').textContent = dish ? '修改菜品' : '添加菜品';
  $('#dish-name').value = dish?.name || '';
  $('#dish-video').value = dish?.videoUrl || '';
  $('#dish-notes').value = dish?.notes || '';
  $('#notes-count').textContent = `${$('#dish-notes').value.length} / 2000`;
  $('#dish-submit').textContent = '提交审核';
  setImagePreview(safeImageUrl(dish?.imageUrl), '当前图片');
  dishDialog.showModal();
  $('#dish-name').focus();
}

function openDelete(dish) {
  deleteDish = dish;
  setDialogBusy(deleteDialog, false);
  $('#delete-dish-name').textContent = dish.name;
  $('#delete-submit').textContent = '提交审核';
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
  $('#image-caption').textContent = file.name;
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
    imageValidationError = error instanceof ImageValidationError ? error.message : '这张图片无法读取，请选择有效的 JPG 或 PNG 图片。';
    showError(dishError, imageValidationError);
  } finally {
    if (temporaryUrl) URL.revokeObjectURL(temporaryUrl);
    if (sequence === imageSequence) readingImage = false;
  }
}

$('#add-dish').addEventListener('click', () => openDish());
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
  if (dishDialog.dataset.busy) return;
  showError(dishError, '');
  if (readingImage) { showError(dishError, '正在读取图片，请稍等一下再提交。'); return; }
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
  if (selectedFile) form.set('image', selectedFile);
  setDialogBusy(dishDialog, true); $('#dish-submit').textContent = '正在提交…';
  try {
    const receipt = await writeApi('/api/submissions', { body: form });
    dishDialog.close();
    toast('已提交，待审核', `#${receipt.id}`, 5000);
  } catch (error) { showError(dishError, error.message); }
  finally { setDialogBusy(dishDialog, false); $('#dish-submit').textContent = '提交审核'; }
});

$('#delete-form').addEventListener('submit', async event => {
  event.preventDefault();
  if (deleteDialog.dataset.busy || !deleteDish) return;
  showError($('#delete-error'), '');
  const form = new FormData();
  form.set('operation', 'DELETE'); form.set('dishId', deleteDish.id); form.set('expectedVersion', deleteDish.version); form.set('imageAction', 'KEEP');
  setDialogBusy(deleteDialog, true); $('#delete-submit').textContent = '正在提交…';
  try {
    const receipt = await writeApi('/api/submissions', { body: form });
    deleteDialog.close();
    toast('删除申请已提交', `#${receipt.id} · 待审核`, 5000);
  } catch (error) { showError($('#delete-error'), error.message); }
  finally { setDialogBusy(deleteDialog, false); $('#delete-submit').textContent = '提交审核'; }
});

loadMenu();
