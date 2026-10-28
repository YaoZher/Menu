import { $, api, writeApi, configureDialogs, toast, authExpired } from './common.js';
import { initializeMenu, refreshMenu, openDishEditor } from './menu.js';
import { initializeReviews, loadReviews } from './chef.js';
import { initializeShopping, loadShopping, addToShopping, isInShopping, clearShoppingState } from './shopping.js';

const views = {
  menu: { path: '/', node: $('#menu'), title: '菜单' },
  shopping: { path: '/shopping', node: $('#shopping-view'), title: '待采购 · 菜单' },
  review: { path: '/chef', node: $('#review-view'), title: '审核 · 菜单' }
};
const scrollPositions = new Map();
let activeView = 'menu';
let sessionReady = false;
let authenticated = false;
let initializing = false;
let initialized = false;

function viewFromPath() {
  if (/^\/shopping\/?$/u.test(location.pathname)) return 'shopping';
  if (/^\/chef(?:\/|\.html)?$/u.test(location.pathname)) return 'review';
  return 'menu';
}

function showView(name, { historyMode = 'push', refresh = true, focus = true } = {}) {
  if (!sessionReady) return;
  if (name !== 'menu' && !authenticated) { location.assign('/chef/login'); return; }
  const next = views[name];
  const changed = activeView !== name;
  scrollPositions.set(activeView, window.scrollY);
  activeView = name;
  if (historyMode === 'replace') history.replaceState({ menuView: name }, '', next.path);
  else if (historyMode === 'push' && location.pathname !== next.path) history.pushState({ menuView: name }, '', next.path);
  for (const [viewName, view] of Object.entries(views)) {
    view.node.hidden = viewName !== name;
    view.node.classList.toggle('view-enter', viewName === name);
  }
  $('#chef-nav').querySelectorAll('a').forEach(link => {
    if (link.dataset.view === name) link.setAttribute('aria-current', 'page');
    else link.removeAttribute('aria-current');
  });
  $('#add-dish').hidden = name !== 'menu';
  $('#skip-content').href = `#${next.node.id}`;
  document.title = next.title;
  document.body.dataset.view = name;
  if (changed) window.scrollTo({ top: scrollPositions.get(name) || 0, behavior: 'instant' });
  if (focus) next.node.focus({ preventScroll: true });
  if (refresh) {
    if (name === 'menu') void refreshMenu();
    else if (name === 'shopping') void loadShopping();
    else void loadReviews();
  }
}

async function initializeApp() {
  if (initializing) return;
  initializing = true;
  $('#retry-session').disabled = true;
  let session;
  try {
    session = await api('/api/session');
    if (typeof session?.authenticated !== 'boolean') throw new Error('登录状态读取失败。');
  } catch (error) {
    $('#session-error').textContent = error.message;
    $('#session-status').hidden = false;
    Object.values(views).forEach(view => { view.node.hidden = true; });
    $('#add-dish').disabled = true;
    $('#retry-session').disabled = false;
    initializing = false;
    return;
  }
  authenticated = session.authenticated;
  sessionReady = true;
  $('#session-status').hidden = true;
  $('#chef-nav').hidden = !authenticated;
  $('#chef-login').hidden = authenticated;
  $('#logout').hidden = !authenticated;
  $('#logout').title = session.username ? `退出 ${session.username}` : '退出';
  document.body.classList.toggle('chef-session', authenticated);
  $('#add-dish').disabled = false;
  if (!initialized) {
    initialized = true;
    configureDialogs();
    initializeReviews();
    initializeShopping({ onEditDish: openDishEditor });
    const initialView = viewFromPath();
    showView(initialView, { historyMode: 'replace', refresh: false, focus: false });
    await Promise.all([
      initializeMenu({ isChef: authenticated, addToShopping, isInShopping }),
      authenticated ? loadShopping() : Promise.resolve(),
      authenticated && initialView === 'review' ? loadReviews() : Promise.resolve()
    ]);
  }
  initializing = false;
  $('#retry-session').disabled = false;
}

document.querySelectorAll('a[data-view]').forEach(link => link.addEventListener('click', event => {
  if (event.button !== 0 || event.ctrlKey || event.metaKey || event.shiftKey || event.altKey) return;
  event.preventDefault();
  showView(link.dataset.view);
}));
window.addEventListener('popstate', () => showView(viewFromPath(), { historyMode: 'none' }));
$('#retry-session').addEventListener('click', initializeApp);
document.addEventListener('menu:changed', () => {
  if (authenticated && activeView === 'shopping') void loadShopping();
});
$('#logout').addEventListener('click', async () => {
  const button = $('#logout');
  if (button.disabled) return;
  button.disabled = true;
  try {
    await writeApi('/api/chef/logout');
    clearShoppingState();
    $('#reviews-content').replaceChildren();
    location.assign('/');
  } catch (error) {
    if (!authExpired(error)) toast('退出失败', error.message);
    button.disabled = false;
  }
});

void initializeApp();
