import { $, api, writeApi, configureDialogs, toast, authExpired } from './common.js';
import { initializeMenu, refreshMenu, openDishEditor, showDishDetail, updateSortingVisibility } from './menu.js';
import { initializeReviews, loadReviews } from './chef.js';
import { initializeShopping, loadShopping, addToShopping, isInShopping, clearShoppingState } from './shopping.js';
import { createNavigationMotion } from './navigation-motion.js';
import { createBackgroundMotion } from './background-motion.js';
import { createInterfaceMotion, createFilterMotion } from './interface-motion.js';

const views = {
  menu: { path: '/', node: $('#menu'), title: '菜单' },
  detail: { node: $('#dish-detail-view'), title: '菜品详情 · 菜单' },
  shopping: { path: '/shopping', node: $('#shopping-view'), title: '待采购 · 菜单' },
  review: { path: '/chef', node: $('#review-view'), title: '审核 · 菜单' }
};
const scrollPositions = new Map();
const navigationMotion = createNavigationMotion($('#chef-nav'));
const backgroundMotion = createBackgroundMotion();
backgroundMotion.play({ kind: 'initial', view: viewFromPath(), key: viewFromPath() === 'review' ? 'PENDING' : 'all' });
const interfaceMotion = createInterfaceMotion();
const filterMotions = {
  menu: createFilterMotion($('.menu-toolbar .filter-tabs')),
  review: createFilterMotion($('.review-tabs'))
};
const filterKeys = { menu: 'all', review: 'PENDING' };
const viewOrder = ['menu', 'detail', 'shopping', 'review'];
let activeView = 'menu';
let sessionReady = false;
let authenticated = false;
let initializing = false;
let initialized = false;
let navigationRevision = 0;

function viewFromPath() {
  if (/^\/dish\/\d+\/?$/u.test(location.pathname)) return 'detail';
  if (/^\/shopping\/?$/u.test(location.pathname)) return 'shopping';
  if (/^\/chef(?:\/|\.html)?$/u.test(location.pathname)) return 'review';
  return 'menu';
}

function showView(name, { historyMode = 'push', refresh = true, focus = true, dishId } = {}) {
  if (!sessionReady) return;
  if (name === 'review' && !authenticated) { location.assign('/chef/login'); return; }
  const next = views[name];
  navigationRevision += 1;
  const selectedDishId = dishId ?? Number(location.pathname.match(/^\/dish\/(\d+)/u)?.[1]);
  const path = name === 'detail' ? `/dish/${selectedDishId}` : next.path;
  const changed = activeView !== name;
  const direction = Math.sign(viewOrder.indexOf(name) - viewOrder.indexOf(activeView)) || 1;
  scrollPositions.set(activeView, window.scrollY);
  activeView = name;
  if (historyMode === 'replace') history.replaceState({ menuView: name }, '', path);
  else if (historyMode === 'push' && location.pathname !== path) history.pushState({ menuView: name }, '', path);
  for (const [viewName, view] of Object.entries(views)) {
    view.node.hidden = viewName !== name;
  }
  $('#chef-nav').querySelectorAll('a').forEach(link => {
    if (link.dataset.view === (name === 'detail' ? 'menu' : name)) link.setAttribute('aria-current', 'page');
    else link.removeAttribute('aria-current');
  });
  $('#add-dish').hidden = name !== 'menu';
  updateSortingVisibility(name === 'menu');
  navigationMotion.sync({ animate: changed && focus });
  $('#skip-content').href = `#${next.node.id}`;
  document.title = next.title;
  document.body.dataset.view = name;
  if (name === 'detail') void showDishDetail(selectedDishId);
  if (changed) window.scrollTo({ top: scrollPositions.get(name) || 0, behavior: 'instant' });
  if (focus) next.node.focus({ preventScroll: true });
  filterMotions[name]?.sync({ animate: false });
  if (changed || !focus) {
    if (focus) backgroundMotion.play({ kind: 'page', view: name, key: filterKeys[name] || 'all', direction });
    interfaceMotion.page(next.node, { direction, initial: !focus });
  }
  if (refresh) {
    if (name === 'menu') { void refreshMenu(); void loadShopping(); }
    else if (name === 'detail') void loadShopping();
    else if (name === 'shopping') void loadShopping();
    else if (name === 'review') void loadReviews();
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
  $('#chef-nav').hidden = false;
  $('#chef-nav [data-view="review"]').hidden = !authenticated;
  $('#chef-login').hidden = authenticated;
  $('#logout').hidden = !authenticated;
  $('#logout').title = session.username ? `退出 ${session.username}` : '退出';
  document.body.classList.toggle('chef-session', authenticated);
  $('#add-dish').disabled = false;
  if (!initialized) {
    initialized = true;
    configureDialogs();
    initializeReviews();
    initializeShopping({ isChef: authenticated, onEditDish: openDishEditor });
    const initialView = viewFromPath();
    const initialRevision = navigationRevision;
    await Promise.all([
      initializeMenu({ isChef: authenticated, addToShopping, isInShopping }),
      loadShopping(),
      authenticated && initialView === 'review' ? loadReviews() : Promise.resolve()
    ]);
    if (navigationRevision === initialRevision) showView(initialView, { historyMode: 'replace', refresh: false, focus: false });
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
  if (activeView === 'shopping') void loadShopping();
});
document.addEventListener('menu:open-dish', event => showView('detail', { dishId: event.detail.id }));
document.addEventListener('menu:detail-missing', () => showView('menu'));
document.addEventListener('menu:open-menu', () => showView('menu'));
document.addEventListener('menu:filter-changed', event => {
  const { scope, key, direction = 1 } = event.detail;
  filterKeys[scope] = key;
  filterMotions[scope]?.sync();
  if (activeView !== scope) return;
  backgroundMotion.play({ kind: 'filter', view: scope, key, direction });
  interfaceMotion.filter(scope === 'review' ? $('#reviews-content') : $('#menu-content'), { direction });
});
$('#detail-back').addEventListener('click', () => showView('menu'));
// Keep the shared order visible to a chef who already has the shopping page open.
setInterval(() => {
  if (sessionReady && !document.hidden && activeView !== 'review') void loadShopping();
}, 10000);
document.addEventListener('visibilitychange', () => {
  if (sessionReady && !document.hidden) void loadShopping();
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
