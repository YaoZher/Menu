import { $, element, icon, api, writeApi, safeVideoUrl, safeImageUrl, dateText, toast, showError, loading, emptyState, configureDialogs, setDialogBusy, authExpired } from './common.js';

let submissions = [];
let activeStatus = 'PENDING';
let deleteApproval = null;
let refreshing = false;
const drafts = new Map();
const busyIds = new Set();
const content = $('#reviews-content');
const deleteDialog = $('#approve-delete-dialog');
const operations = { CREATE: '新增菜品', UPDATE: '修改菜品', DELETE: '删除菜品' };
const statuses = { PENDING: '等待审核', APPROVED: '已通过', REJECTED: '已驳回' };
configureDialogs();

function snapshot(data, side, operation) {
  const after = side === 'after';
  const panel = element('section', `snapshot ${after ? 'after' : 'before'}${after && operation === 'DELETE' ? ' delete-snapshot' : ''}`);
  panel.append(element('div', 'snapshot-label', after ? '申请生效后' : '提交时的菜单'));
  if (!data) {
    const placeholder = element('div', 'snapshot-placeholder');
    placeholder.append(icon(after ? 'trash' : 'plus'), element('span', '', after ? '从菜单中移除这道菜' : '这是一次新的菜品分享'));
    panel.append(placeholder);
    return panel;
  }
  panel.append(element('h4', '', data.name));
  const imageUrl = safeImageUrl(data.imageUrl);
  if (imageUrl) {
    const image = element('img', 'snapshot-image');
    image.src = imageUrl; image.alt = data.name; image.loading = 'lazy';
    image.addEventListener('error', () => { image.replaceWith(element('p', 'snapshot-notes', '图片暂时无法显示')); }, { once: true });
    panel.append(image);
  }
  panel.append(element('p', 'snapshot-notes', data.notes || '未填写备注'));
  const video = safeVideoUrl(data.videoUrl);
  if (video) {
    const link = element('a', 'video-link');
    link.href = video; link.target = '_blank'; link.rel = 'noopener noreferrer';
    link.append(icon('play'), element('span', '', '查看提交的视频 ↗'));
    link.setAttribute('aria-label', `查看${data.name}的视频（新窗口打开）`);
    panel.append(link);
  }
  return panel;
}

function reviewCard(submission) {
  const card = element('article', 'review-card');
  card.dataset.submissionId = submission.id;
  const heading = element('div', 'review-card-header');
  const titleBlock = element('div');
  const titleRow = element('div', 'review-title-row');
  titleRow.append(element('span', `pill pill-${String(submission.operation).toLowerCase()}`, operations[submission.operation] || submission.operation), element('h3', '', submission.after?.name || submission.before?.name || '菜品申请'));
  titleBlock.append(titleRow, element('p', 'review-id', `提交编号 #${submission.id}`));
  const dateBlock = element('div', 'review-date');
  dateBlock.append(element('div', '', dateText(submission.createdAt, true)), element('span', `pill pill-${String(submission.status).toLowerCase()}`, statuses[submission.status] || submission.status));
  heading.append(titleBlock, dateBlock);
  const comparison = element('div', 'comparison');
  comparison.append(snapshot(submission.before, 'before', submission.operation), snapshot(submission.after, 'after', submission.operation));
  card.append(heading, comparison);
  if (submission.status === 'PENDING') {
    const actions = element('div', 'review-actions');
    const field = element('div', 'field');
    const noteId = `review-note-${submission.id}`;
    const label = element('label', '', '审核备注（选填，最多 1000 字）'); label.htmlFor = noteId;
    const note = element('textarea'); note.id = noteId; note.rows = 1; note.maxLength = 1000; note.placeholder = '写下通过或驳回的原因…'; note.value = drafts.get(submission.id) || '';
    note.addEventListener('input', () => drafts.set(submission.id, note.value));
    field.append(label, note);
    const buttons = element('div', 'button-row');
    const reject = element('button', 'button secondary');
    reject.type = 'button'; reject.append(icon('close'), element('span', '', '驳回'));
    reject.addEventListener('click', () => decide(submission, 'reject', card));
    const approve = element('button', 'button primary');
    approve.type = 'button'; approve.append(icon('check'), element('span', '', '通过审核'));
    approve.addEventListener('click', () => {
      if (submission.operation === 'DELETE') {
        deleteApproval = { submission, card };
        $('#approve-delete-name').textContent = submission.before?.name || '这道菜';
        showError($('#approve-delete-error'), '');
        deleteDialog.showModal();
        $('[data-close="approve-delete-dialog"]').focus();
      } else decide(submission, 'approve', card);
    });
    buttons.append(reject, approve); actions.append(field, buttons);
    const error = element('p', 'form-error review-error'); error.hidden = true; error.setAttribute('role', 'alert');
    card.append(actions, error);
  } else {
    const result = element('div', 'review-result');
    result.append(element('span', '', `审核人：${submission.reviewedBy || '管理员'}`), element('span', '', `审核时间：${dateText(submission.reviewedAt, true) || '—'}`));
    if (submission.reviewNote) result.append(element('p', 'review-note', `审核备注：${submission.reviewNote}`));
    card.append(result);
  }
  return card;
}

function renderReviews() {
  content.setAttribute('aria-busy', 'false');
  $('#stat-pending').textContent = String(submissions.filter(item => item.status === 'PENDING').length);
  $('#stat-approved').textContent = String(submissions.filter(item => item.status === 'APPROVED').length);
  $('#stat-rejected').textContent = String(submissions.filter(item => item.status === 'REJECTED').length);
  const visible = submissions.filter(item => activeStatus === 'ALL' || item.status === activeStatus);
  $('#review-count').textContent = `${visible.length} 条记录`;
  if (!visible.length) {
    const pending = activeStatus === 'PENDING';
    content.replaceChildren(emptyState({ title: pending ? '一切妥当，歇一小会儿' : '这里还没有记录', message: pending ? '目前没有等待审核的申请。下一份分享来到时，就能在这里看到。' : '你所选择的状态下，暂时还没有提交记录。', symbol: pending ? 'check' : 'bowl', index: pending ? 'ALL CAUGHT UP' : 'REVIEW HISTORY' }));
    return;
  }
  const list = element('div', 'review-list');
  visible.forEach(submission => list.append(reviewCard(submission)));
  content.replaceChildren(list);
}

async function loadReviews() {
  if (refreshing) return;
  refreshing = true;
  $('#refresh-reviews').disabled = true;
  loading(content, '正在读取提交记录…');
  try {
    const data = await api('/api/admin/submissions?status=ALL');
    if (!Array.isArray(data)) throw new Error('提交记录暂时无法读取，请重试。');
    submissions = data; renderReviews();
  } catch (error) {
    if (authExpired(error)) return;
    content.setAttribute('aria-busy', 'false');
    content.replaceChildren(emptyState({ title: '记录暂时没有送达', message: error.message, actionText: '重新加载', action: loadReviews, symbol: 'refresh' }));
  } finally { refreshing = false; $('#refresh-reviews').disabled = false; }
}

async function decide(submission, decision, card, dialog = null) {
  if (busyIds.has(submission.id)) return;
  busyIds.add(submission.id);
  const errorNode = dialog ? $('#approve-delete-error') : $('.review-error', card);
  showError(errorNode, '');
  card.querySelectorAll('button, textarea').forEach(control => { control.disabled = true; });
  if (dialog) { setDialogBusy(dialog, true); $('#approve-delete-submit').textContent = '正在处理…'; }
  try {
    const updated = await writeApi(`/api/admin/submissions/${encodeURIComponent(submission.id)}/${decision}`, { headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ note: (drafts.get(submission.id) || '').trim() }) });
    if (dialog) dialog.close();
    drafts.delete(submission.id);
    const index = submissions.findIndex(item => item.id === submission.id);
    if (index !== -1 && updated?.id) submissions[index] = updated;
    toast(decision === 'approve' ? '申请已通过' : '申请已驳回', decision === 'approve' ? '公开菜单已同步更新。' : '审核记录已保存，公开菜单保持原样。');
    if (updated?.id) renderReviews(); else await loadReviews();
  } catch (error) {
    if (!authExpired(error)) showError(errorNode, error.message);
  } finally {
    busyIds.delete(submission.id);
    card.querySelectorAll('button, textarea').forEach(control => { control.disabled = false; });
    if (dialog) { setDialogBusy(dialog, false); $('#approve-delete-submit').textContent = '确认通过并删除'; }
  }
}

document.querySelectorAll('[data-status]').forEach(button => button.addEventListener('click', () => {
  activeStatus = button.dataset.status;
  document.querySelectorAll('[data-status]').forEach(tab => { const active = tab === button; tab.classList.toggle('active', active); tab.setAttribute('aria-pressed', String(active)); });
  renderReviews();
}));
$('#refresh-reviews').addEventListener('click', loadReviews);
$('#approve-delete-form').addEventListener('submit', event => {
  event.preventDefault();
  if (deleteApproval) decide(deleteApproval.submission, 'approve', deleteApproval.card, deleteDialog);
});
$('#logout').addEventListener('click', async () => {
  const button = $('#logout');
  if (button.disabled) return;
  button.disabled = true; button.textContent = '正在退出…';
  try { await writeApi('/api/auth/logout'); location.assign('/login'); }
  catch (error) {
    if (!authExpired(error)) toast('退出未完成', error.message);
    button.disabled = false; button.textContent = '退出登录';
  }
});

async function initialize() {
  try {
    const user = await api('/api/admin/me');
    $('#admin-identity').textContent = user?.username || '管理员';
    await loadReviews();
  } catch (error) {
    if (authExpired(error)) return;
    content.setAttribute('aria-busy', 'false');
    content.replaceChildren(emptyState({ title: '工作台暂时无法连接', message: error.message, actionText: '重新连接', action: initialize, symbol: 'refresh' }));
  }
}
initialize();
