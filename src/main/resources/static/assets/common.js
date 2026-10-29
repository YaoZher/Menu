export const $ = (selector, root = document) => root.querySelector(selector);
export function element(tag, className, text) {
  const node = document.createElement(tag);
  if (className) node.className = className;
  if (text !== undefined && text !== null) node.textContent = String(text);
  return node;
}
const actionPaths = {
  plus: 'M12 5v14M5 12h14',
  edit: 'm14 5 5 5M4 20l5-1L21 7a2 2 0 0 0-5-5L4 14ZM4 20h16',
  trash: 'M3 6h18M9 6V3h6v3M5 6l1 14h12l1-14M10 10v6M14 10v6',
  check: 'm5 12 4 4L19 6',
  close: 'm6 6 12 12M18 6 6 18',
  refresh: 'M20 7v5h-5M4 17v-5h5M5 8a8 8 0 0 1 14-2l1 6M4 12l1 6a8 8 0 0 0 14-2'
};
export function icon(name, className = 'icon') {
  const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
  svg.setAttribute('class', className);
  svg.setAttribute('aria-hidden', 'true');
  svg.setAttribute('viewBox', name === 'bowl' ? '0 0 80 80' : '0 0 24 24');
  if (actionPaths[name]) {
    const path = document.createElementNS('http://www.w3.org/2000/svg', 'path');
    path.setAttribute('d', actionPaths[name]);
    svg.append(path);
    return svg;
  }
  const use = document.createElementNS('http://www.w3.org/2000/svg', 'use');
  use.setAttribute('href', `/assets/icons.svg#${name}`);
  svg.append(use);
  return svg;
}
export class ApiError extends Error {
  constructor(message, status) { super(message); this.status = status; }
}
export async function api(url, options = {}) {
  let response;
  try { response = await fetch(url, { credentials: 'same-origin', ...options }); }
  catch { throw new ApiError('暂时无法连接，请检查网络后重试。', 0); }
  const isJson = response.headers.get('content-type')?.includes('application/json');
  let body = null;
  try { body = isJson ? await response.json() : null; } catch { /* Handled below. */ }
  if (!response.ok) {
    const fallback = response.status === 401 ? '登录已过期，请重新登录。' : response.status === 403 ? '页面验证已过期，请刷新页面后重试。' : response.status === 409 ? '这道菜已经发生变化，请刷新后重新提交。' : '操作未完成，请稍后重试。';
    throw new ApiError(body?.message || fallback, response.status);
  }
  if (response.redirected && new URL(response.url).pathname === '/chef/login') throw new ApiError('登录已过期，请重新登录。', 401);
  return body;
}
export async function writeApi(url, options = {}) {
  const csrf = await api('/api/csrf');
  if (!csrf?.token || !csrf?.headerName) throw new ApiError('页面验证未完成，请刷新后重试。', 403);
  const headers = new Headers(options.headers || {});
  headers.set(csrf.headerName, csrf.token);
  return api(url, { ...options, method: options.method || 'POST', headers });
}
export function safeVideoUrl(value) {
  if (!value) return null;
  try { const url = new URL(value); return ['https:', 'http:'].includes(url.protocol) ? url.href : null; }
  catch { return null; }
}
export function safeImageUrl(value) {
  if (!value) return null;
  try { const url = new URL(value, location.origin); return url.origin === location.origin && ['http:', 'https:'].includes(url.protocol) ? url.href : null; }
  catch { return null; }
}
export function dateText(value, includeTime = false) {
  if (!value) return '';
  const date = new Date(value);
  if (!Number.isFinite(date.getTime())) return '';
  return new Intl.DateTimeFormat('zh-CN', { year: 'numeric', month: '2-digit', day: '2-digit', ...(includeTime ? { hour: '2-digit', minute: '2-digit', hour12: false } : {}) }).format(date);
}
export function toast(title, detail = '', duration = 11000) {
  const region = $('#toast-region');
  if (!region) return;
  const box = element('div', 'toast');
  const text = element('div');
  text.append(element('strong', '', title));
  if (detail) text.append(element('p', '', detail));
  const close = element('button', 'icon-button');
  close.type = 'button';
  close.setAttribute('aria-label', '关闭通知');
  close.append(icon('close'));
  close.addEventListener('click', () => box.remove());
  box.append(icon('check'), text, close);
  region.append(box);
  if (duration) window.setTimeout(() => box.remove(), duration);
}
export function showError(node, message) { node.textContent = message || ''; node.hidden = !message; }
export function loading(container, message) {
  container.setAttribute('aria-busy', 'true');
  const node = element('div', 'loading-state');
  node.append(element('span', 'spinner'), element('p', '', message));
  container.replaceChildren(node);
}
export function emptyState({ title, message, action, actionText, symbol = 'bowl', index }) {
  const node = element('div', 'empty-state');
  if (index) node.append(element('span', 'empty-index', index));
  const illustration = element('div', 'empty-illustration');
  illustration.append(icon(symbol, 'bowl-icon'));
  node.append(illustration, element('h3', '', title), element('p', '', message));
  if (action) {
    const button = element('button', 'button secondary', actionText);
    button.type = 'button';
    button.addEventListener('click', action);
    node.append(button);
  }
  return node;
}
export function configureDialogs() {
  document.querySelectorAll('[data-close]').forEach(button => button.addEventListener('click', () => {
    const dialog = document.getElementById(button.dataset.close);
    if (!dialog.dataset.busy) dialog.close();
  }));
  document.querySelectorAll('dialog').forEach(dialog => {
    dialog.addEventListener('cancel', event => { if (dialog.dataset.busy) event.preventDefault(); });
    dialog.addEventListener('click', event => {
      if (event.target !== dialog || dialog.dataset.busy) return;
      const rect = dialog.getBoundingClientRect();
      if (event.clientX < rect.left || event.clientX > rect.right || event.clientY < rect.top || event.clientY > rect.bottom) dialog.close();
    });
  });
}
export function setDialogBusy(dialog, busy) {
  if (busy) dialog.dataset.busy = 'true'; else delete dialog.dataset.busy;
  dialog.querySelectorAll('button, input, textarea, select').forEach(control => { control.disabled = busy; });
}
export function authExpired(error) {
  if (error.status === 401 || error.status === 403) { location.assign('/chef/login?expired=1'); return true; }
  return false;
}
