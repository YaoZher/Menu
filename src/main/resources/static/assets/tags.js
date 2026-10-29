import { element, api, writeApi, showError, authExpired } from './common.js';

let catalog = [];
let catalogReady = false;
let catalogError = '';
let catalogRequest = null;
const pickers = new Set();

export function flattenTags(groups) {
  return groups.flatMap(group => group.tags.map(tag => ({ ...tag, categoryId: group.id, categoryName: group.name })));
}

export function matchesTagFilters(tags, selected) {
  const groups = new Map();
  for (const tag of selected) {
    const key = String(tag.categoryId);
    if (!groups.has(key)) groups.set(key, new Set());
    groups.get(key).add(String(tag.id));
  }
  const ids = new Set((tags || []).map(tag => String(tag.id)));
  return [...groups.values()].every(group => [...group].some(id => ids.has(id)));
}

export function normalizeTagSelection(tags, fallback) {
  const unique = [...new Map(tags.map(tag => [String(tag.id), tag])).values()];
  const real = fallback ? unique.filter(tag => String(tag.id) !== String(fallback.id)) : unique;
  const selected = real.length ? real : fallback ? [fallback] : unique;
  if (selected.length > 12) throw new Error('最多选择 12 个标签。');
  return selected;
}

export async function loadTagCatalog() {
  if (catalogRequest) return catalogRequest;
  catalogError = '';
  catalogRequest = (async () => {
    try {
      const result = await api('/api/tags');
      if (!Array.isArray(result) || result.some(group => !Array.isArray(group.tags))) throw new Error('标签读取失败。');
      catalog = result; catalogReady = true;
    } catch (error) { catalogError = error.message; }
    finally { catalogRequest = null; pickers.forEach(picker => picker.refresh()); }
    return catalog;
  })();
  pickers.forEach(picker => picker.refresh());
  return catalogRequest;
}

export function renderTagChips(tags, onSelect) {
  const row = element('div', 'dish-tags');
  row.setAttribute('aria-label', '菜品标签');
  const visible = Array.isArray(tags) && tags.length ? tags : [{ name: '未分类' }];
  for (const tag of visible) {
    const interactive = onSelect && tag.id != null;
    const chip = element(interactive ? 'button' : 'span', 'tag-chip', tag.name);
    if (tag.categoryName) chip.title = `${tag.categoryName} · ${tag.name}`;
    if (interactive) {
      chip.type = 'button'; chip.setAttribute('aria-label', `筛选标签：${tag.name}`);
      chip.addEventListener('click', () => onSelect(tag));
    }
    row.append(chip);
  }
  return row;
}

export function createTagPicker(root, { mode = 'editor', isChef = false, onChange = () => {} } = {}) {
  let selected = [];
  let category = 'all';
  let query = '';
  let context = 0;
  let creating = false;
  let presentedCatalogError = false;
  const filter = mode === 'filter';
  const selectedRow = element('div', 'selected-tags');
  selectedRow.setAttribute('aria-label', filter ? '已选筛选标签' : '已选菜品标签');
  const tabs = element('div', 'tag-category-tabs'); tabs.setAttribute('role', 'group'); tabs.setAttribute('aria-label', '标签分类');
  const search = element('input', 'tag-search'); search.type = 'search'; search.placeholder = '查找标签'; search.maxLength = 30;
  search.setAttribute('aria-label', filter ? '查找筛选标签' : '查找菜品标签');
  const choices = element('div', 'tag-options'); choices.setAttribute('role', 'group'); choices.setAttribute('aria-label', filter ? '筛选标签' : '选择菜品标签');
  const error = element('p', 'form-error'); error.hidden = true; error.setAttribute('role', 'alert');
  const retry = element('button', 'text-button', '重试'); retry.type = 'button'; retry.hidden = true;
  retry.addEventListener('click', () => loadTagCatalog());
  root.replaceChildren(selectedRow, tabs, search, choices, error, retry);
  const fallback = () => flattenTags(catalog).find(tag => tag.name === '未分类' && tag.categoryName === '其他');
  const controlsDisabled = () => !catalogReady || creating || Boolean(root.closest('dialog')?.dataset.busy);

  function publish() { onChange([...selected]); }
  function select(tag, remove = false) {
    if (controlsDisabled()) return;
    try {
      const has = selected.some(item => String(item.id) === String(tag.id));
      let next = remove || has ? selected.filter(item => String(item.id) !== String(tag.id)) : [...selected, tag];
      if (!filter && !remove && !has && String(tag.id) === String(fallback()?.id)) next = [tag];
      selected = filter ? next : normalizeTagSelection(next, fallback());
      showError(error, ''); refresh(); publish();
    } catch (failure) { showError(error, failure.message); }
  }
  search.addEventListener('input', () => { query = search.value.trim().toLocaleLowerCase('zh-CN'); renderChoices(); });

  function renderChoices() {
    const tags = flattenTags(catalog).filter(tag => (category === 'all' || String(tag.categoryId) === category)
      && (!query || tag.name.toLocaleLowerCase('zh-CN').includes(query)));
    const nodes = tags.map(tag => {
      const button = element('button', 'tag-option', tag.name); button.type = 'button';
      const active = selected.some(item => String(item.id) === String(tag.id));
      button.classList.toggle('active', active); button.setAttribute('aria-pressed', String(active));
      button.disabled = controlsDisabled();
      button.title = tag.categoryName;
      button.addEventListener('click', () => select(tag));
      return button;
    });
    choices.replaceChildren(...nodes);
    if (!nodes.length) choices.append(element('span', 'tag-empty', catalogReady ? '没有匹配标签' : catalogError ? '' : '读取标签…'));
  }

  function refresh() {
    if (!filter && catalogReady) selected = normalizeTagSelection(selected, fallback());
    selectedRow.replaceChildren(...selected.map(tag => {
      if (!filter && selected.length === 1 && String(tag.id) === String(fallback()?.id)) {
        return element('span', 'tag-chip selected-tag', tag.name);
      }
      const button = element('button', 'tag-chip selected-tag', `${tag.name} ×`); button.type = 'button';
      button.setAttribute('aria-label', `移除标签：${tag.name}`); button.disabled = controlsDisabled();
      button.addEventListener('click', () => select(tag, true)); return button;
    }));
    const groups = [{ id: 'all', name: '全部' }, ...catalog];
    tabs.replaceChildren(...groups.map(group => {
      const button = element('button', 'tag-category', group.name); button.type = 'button';
      button.setAttribute('aria-pressed', String(String(group.id) === category));
      button.disabled = controlsDisabled();
      button.addEventListener('click', () => { category = String(group.id); refresh(); }); return button;
    }));
    search.disabled = controlsDisabled();
    renderChoices();
    if (catalogError) { showError(error, '标签加载失败'); presentedCatalogError = true; }
    else if (presentedCatalogError) { showError(error, ''); presentedCatalogError = false; }
    retry.hidden = !catalogError; retry.disabled = Boolean(catalogRequest);
    refreshManager();
  }

  let manager = null;
  let managerCategory = null;
  let newCategory = null;
  let tagName = null;
  let createButton = null;
  function refreshManager() {
    if (!manager) return;
    const current = managerCategory.value;
    managerCategory.replaceChildren(...catalog.map(group => {
      const option = element('option', '', group.name); option.value = group.id; return option;
    }));
    const create = element('option', '', '+ 新分类'); create.value = 'new'; managerCategory.append(create);
    if ([...managerCategory.options].some(option => option.value === current)) managerCategory.value = current;
    newCategory.hidden = managerCategory.value !== 'new';
    manager.querySelectorAll('input, select, button').forEach(control => { control.disabled = controlsDisabled(); });
  }
  if (!filter && isChef) {
    manager = element('details', 'tag-manager');
    manager.append(element('summary', '', '+ 标签'));
    const fields = element('div', 'tag-manager-fields');
    managerCategory = element('select'); managerCategory.setAttribute('aria-label', '新标签所属分类');
    newCategory = element('input'); newCategory.type = 'text'; newCategory.maxLength = 30; newCategory.placeholder = '新分类名称'; newCategory.setAttribute('aria-label', '新分类名称'); newCategory.hidden = true;
    tagName = element('input'); tagName.type = 'text'; tagName.maxLength = 30; tagName.placeholder = '标签名称'; tagName.setAttribute('aria-label', '新标签名称');
    createButton = element('button', 'button secondary', '添加'); createButton.type = 'button';
    managerCategory.addEventListener('change', () => { newCategory.hidden = managerCategory.value !== 'new'; });
    createButton.addEventListener('click', async () => {
      if (controlsDisabled()) return;
      const name = tagName.value.trim(); const categoryName = newCategory.value.trim();
      if (!name || (managerCategory.value === 'new' && !categoryName)) { showError(error, '请填写分类和标签名称。'); return; }
      if (name.length > 30 || categoryName.length > 30) { showError(error, '名称不能超过 30 个字符。'); return; }
      if (selected.filter(tag => tag.id !== fallback()?.id).length >= 12) { showError(error, '最多选择 12 个标签。'); return; }
      const currentContext = context;
      creating = true; refresh(); showError(error, '');
      try {
        let categoryId = managerCategory.value;
        if (categoryId === 'new') {
          const group = await writeApi('/api/chef/tag-categories', { headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ name: categoryName }) });
          categoryId = group.id;
          await loadTagCatalog();
          if (currentContext === context) { managerCategory.value = String(categoryId); newCategory.hidden = true; }
        }
        const tag = await writeApi('/api/chef/tags', { headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ categoryId: Number(categoryId), name }) });
        await loadTagCatalog();
        if (currentContext === context) {
          selected = normalizeTagSelection([...selected, tag], fallback());
          tagName.value = ''; newCategory.value = ''; manager.open = false; publish();
        }
      } catch (failure) { if (!authExpired(failure) && currentContext === context) showError(error, failure.message); }
      finally { creating = false; refresh(); }
    });
    fields.append(managerCategory, newCategory, tagName, createButton); manager.append(fields); root.append(manager);
  }
  const picker = {
    refresh,
    setSelection(tags, notify = false) {
      context += 1;
      selected = Array.isArray(tags) ? [...tags] : [];
      category = 'all'; query = ''; search.value = '';
      if (manager) { manager.open = false; tagName.value = ''; newCategory.value = ''; }
      showError(error, ''); refresh(); if (notify) publish();
    },
    getSelection: () => [...selected],
    isReady: () => catalogReady,
    isBusy: () => creating
  };
  pickers.add(picker); refresh(); return picker;
}
