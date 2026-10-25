import { $, element, icon, api, writeApi, safeVideoUrl, safeImageUrl, dateText, toast, showError, loading, emptyState, configureDialogs, setDialogBusy } from './common.js';

let dishes = [];
let activeFilter = 'all';
let editDish = null;
let deleteDish = null;
let selectedFile = null;
let imageAction = 'KEEP';
let previewUrl = null;
let imageSequence = 0;
let readingImage = false;
const content = $('#menu-content');
const dishDialog = $('#dish-dialog');
const deleteDialog = $('#delete-dialog');
const dishForm = $('#dish-form');
const dishError = $('#dish-error');
configureDialogs();

function imageFallback(cover) {
  cover.replaceChildren(icon('bowl', 'bowl-icon'), element('span', '', '日常里的好味道'));
}

function makeCard(dish, index) {
  const card = element('article', 'dish-card');
  const cover = element('div', 'dish-cover');
  const imageUrl = safeImageUrl(dish.imageUrl);
  if (imageUrl) {
    const image = element('img');
    image.src = imageUrl; image.alt = dish.name; image.loading = 'lazy';
    image.addEventListener('error', () => imageFallback(cover), { once: true });
    cover.append(image);
  } else imageFallback(cover);
  cover.append(element('div', 'card-number', `NO. ${String(index + 1).padStart(2, '0')}`));
  const body = element('div', 'dish-body');
  body.append(element('h3', '', dish.name));
  body.append(element('p', `dish-notes${dish.notes ? '' : ' no-notes'}`, dish.notes || '简单的名字，也藏着好味道。'));
  const meta = element('div', 'dish-meta');
  const video = safeVideoUrl(dish.videoUrl);
  if (video) {
    const link = element('a', 'video-link');
    link.href = video; link.target = '_blank'; link.rel = 'noopener noreferrer';
    link.setAttribute('aria-label', `观看${dish.name}的视频（新窗口打开）`);
    link.append(icon('play'), element('span', '', '看看做法 ↗'));
    meta.append(link);
  } else meta.append(element('span'));
  meta.append(element('span', 'dish-date', dateText(dish.updatedAt)));
  body.append(meta);
  const actions = element('div', 'card-actions');
  const edit = element('button', 'text-button');
  edit.type = 'button'; edit.append(icon('edit'), element('span', '', '修改'));
  edit.setAttribute('aria-label', `申请修改${dish.name}`);
  edit.addEventListener('click', () => openDish(dish));
  const remove = element('button', 'text-button');
  remove.type = 'button'; remove.append(icon('trash'), element('span', '', '删除'));
  remove.setAttribute('aria-label', `申请删除${dish.name}`);
  remove.addEventListener('click', () => openDelete(dish));
  actions.append(edit, remove); card.append(cover, body, actions);
  return card;
}

function renderMenu() {
  content.setAttribute('aria-busy', 'false');
  $('#menu-count').textContent = String(dishes.length);
  const query = $('#menu-search').value.trim().toLocaleLowerCase('zh-CN');
  const visible = dishes.filter(dish => (!query || `${dish.name} ${dish.notes || ''}`.toLocaleLowerCase('zh-CN').includes(query)) && (activeFilter === 'all' || activeFilter === 'image' && dish.imageUrl || activeFilter === 'video' && dish.videoUrl));
  if (!dishes.length) {
    content.replaceChildren(emptyState({ title: '第一道好味道，等你来分享', message: '菜单还是一张白纸。添一道你喜欢的菜，让今天的美味从这里开始。', actionText: '＋ 添上第一道菜', action: () => openDish(), index: 'THE FIRST DISH' }));
    return;
  }
  if (!visible.length) {
    content.replaceChildren(emptyState({ title: '暂时没有找到这道美味', message: '换个关键词，或看看菜单里的其他好味道。', symbol: 'search', actionText: '查看全部菜品', action: () => { $('#menu-search').value = ''; setFilter('all'); } }));
    return;
  }
  const grid = element('div', 'menu-grid');
  visible.forEach(dish => grid.append(makeCard(dish, dishes.indexOf(dish))));
  content.replaceChildren(grid);
}

async function loadMenu() {
  loading(content, '正在准备菜单…');
  try {
    const result = await api('/api/menu');
    if (!Array.isArray(result)) throw new Error('菜单暂时无法读取，请重试。');
    dishes = result; renderMenu();
  } catch (error) {
    content.setAttribute('aria-busy', 'false');
    content.replaceChildren(emptyState({ title: '菜单还在路上', message: error.message, actionText: '重新加载', action: loadMenu, symbol: 'refresh' }));
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
  imageSequence += 1; readingImage = false;
  dishForm.reset(); releasePreview(); selectedFile = null; imageAction = 'KEEP';
  setDialogBusy(dishDialog, false);
  showError(dishError, '');
  $('#dish-dialog-title').textContent = dish ? '让这道菜，更好一点' : '添一道菜';
  $('#dish-dialog-intro').textContent = dish ? '分享你想做的调整。审核通过前，菜单会保留原来的内容。' : '一道家常味，也值得拥有姓名。提交后由管理员审核。';
  $('#dish-name').value = dish?.name || '';
  $('#dish-video').value = dish?.videoUrl || '';
  $('#dish-notes').value = dish?.notes || '';
  $('#notes-count').textContent = `${$('#dish-notes').value.length} / 2000`;
  $('#dish-submit').textContent = '提交审核 ↗';
  setImagePreview(safeImageUrl(dish?.imageUrl), '当前菜品图片 · 点击图片可更换');
  dishDialog.showModal();
  $('#dish-name').focus();
}

function openDelete(dish) {
  deleteDish = dish;
  setDialogBusy(deleteDialog, false);
  $('#delete-dish-name').textContent = dish.name;
  $('#delete-submit').textContent = '提交删除申请';
  showError($('#delete-error'), '');
  deleteDialog.showModal();
  $('[data-close="delete-dialog"]', $('#delete-form')).focus();
}

async function readImage(file) {
  const sequence = ++imageSequence;
  if (!file) return;
  showError(dishError, '');
  if (!['image/png', 'image/jpeg'].includes(file.type)) {
    showError(dishError, '请选择 JPG 或 PNG 格式的图片。'); $('#dish-image').value = ''; return;
  }
  if (file.size > 5 * 1024 * 1024) {
    showError(dishError, '图片不能超过 5 MB，请压缩后重新选择。'); $('#dish-image').value = ''; return;
  }
  const temporaryUrl = URL.createObjectURL(file);
  readingImage = true;
  try {
    const image = new Image(); image.src = temporaryUrl; await image.decode();
    if (sequence !== imageSequence) { URL.revokeObjectURL(temporaryUrl); return; }
    if (image.naturalWidth > 4096 || image.naturalHeight > 4096) throw new Error('图片的宽和高不能超过 4096 像素，请缩小后重新选择。');
    releasePreview(); previewUrl = temporaryUrl; selectedFile = file; imageAction = 'REPLACE';
    setImagePreview(previewUrl, `${file.name} · ${(file.size / 1024 / 1024).toFixed(2)} MB`);
  } catch (error) {
    URL.revokeObjectURL(temporaryUrl);
    if (sequence !== imageSequence) return;
    $('#dish-image').value = '';
    showError(dishError, error.message?.includes('4096') ? error.message : '这张图片无法读取，请选择有效的 JPG 或 PNG 图片。');
  } finally { if (sequence === imageSequence) readingImage = false; }
}

$('#hero-add').addEventListener('click', () => openDish());
document.querySelectorAll('[data-filter]').forEach(button => button.addEventListener('click', () => setFilter(button.dataset.filter)));
$('#menu-search').addEventListener('input', renderMenu);
$('#dish-notes').addEventListener('input', event => { $('#notes-count').textContent = `${event.target.value.length} / 2000`; });
$('#dish-image').addEventListener('change', event => { readImage(event.target.files[0]); });
$('#remove-image').addEventListener('click', () => {
  imageSequence += 1; readingImage = false; releasePreview(); selectedFile = null;
  imageAction = editDish?.imageUrl ? 'REMOVE' : 'KEEP';
  $('#dish-image').value = ''; setImagePreview(null); showError(dishError, '');
});
dishDialog.addEventListener('close', () => { imageSequence += 1; readingImage = false; releasePreview(); });

dishForm.addEventListener('submit', async event => {
  event.preventDefault();
  if (dishDialog.dataset.busy) return;
  showError(dishError, '');
  if (readingImage) { showError(dishError, '正在读取图片，请稍等一下再提交。'); return; }
  const name = $('#dish-name').value.trim();
  const rawVideo = $('#dish-video').value.trim();
  if (!name) { showError(dishError, '给这道菜起个名字吧。'); $('#dish-name').focus(); return; }
  if (rawVideo && !safeVideoUrl(rawVideo)) { showError(dishError, '请填写完整的 http:// 或 https:// 视频链接。'); $('#dish-video').focus(); return; }
  if (!dishForm.reportValidity()) return;
  const form = new FormData();
  form.set('operation', editDish ? 'UPDATE' : 'CREATE');
  if (editDish) { form.set('dishId', editDish.id); form.set('expectedVersion', editDish.version); }
  form.set('name', name); form.set('videoUrl', rawVideo); form.set('notes', $('#dish-notes').value.trim()); form.set('imageAction', imageAction);
  if (selectedFile) form.set('image', selectedFile);
  setDialogBusy(dishDialog, true); $('#dish-submit').textContent = '正在提交…';
  try {
    const receipt = await writeApi('/api/submissions', { body: form });
    dishDialog.close();
    toast('你的心意，已送达', `提交编号 #${receipt.id} · 正在等待审核。审核通过后会更新到菜单。`, 0);
  } catch (error) { showError(dishError, error.message); }
  finally { setDialogBusy(dishDialog, false); $('#dish-submit').textContent = '提交审核 ↗'; }
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
    toast('删除申请已提交', `提交编号 #${receipt.id} · 审核通过前，这道菜仍会保留在菜单中。`, 0);
  } catch (error) { showError($('#delete-error'), error.message); }
  finally { setDialogBusy(deleteDialog, false); $('#delete-submit').textContent = '提交删除申请'; }
});

loadMenu();
