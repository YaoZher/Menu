import { $, element, icon, api, writeApi, safeVideoUrl, safeImageUrl, dateText, toast, showError, loading, emptyState, setDialogBusy, authExpired } from './common.js';
import { renderNoteContent } from './notes.js';

let submissions = [];
let activeStatus = 'PENDING';
let deleteApproval = null;
let refreshing = false;
let initialized = false;
const drafts = new Map();
const busyIds = new Set();
const openReviews = new Set();
const content = $('#reviews-content');
const deleteDialog = $('#approve-delete-dialog');
const operations = { CREATE: '新增', UPDATE: '修改', DELETE: '删除' };
const statuses = { PENDING: '待审核', APPROVED: '已通过', REJECTED: '已驳回' };

function snapshot(data, side, operation) {
  const after = side === 'after';
  const panel = element('section', `snapshot ${after ? 'after' : 'before'}${after && operation === 'DELETE' ? ' delete-snapshot' : ''}`);
  panel.append(element('div', 'snapshot-label', after ? '修改后' : '修改前'));
  if (!data) {
    const placeholder = element('div', 'snapshot-placeholder');
    placeholder.append(icon(after ? 'trash' : 'plus'), element('span', '', after ? '删除' : '无'));
    panel.append(placeholder);
    return panel;
  }
  panel.append(element('h4', '', data.name));
  const imageUrl = safeImageUrl(data.imageUrl);
  if (imageUrl) {
    const image = element('img', 'snapshot-image');
    image.src = imageUrl; image.alt = data.name; image.loading = 'lazy';
    image.addEventListener('error', () => { image.replaceWith(element('p', 'snapshot-notes', '图片加载失败')); }, { once: true });
    panel.append(image);
  }
  const notes = renderNoteContent(data);
  if (notes) panel.append(notes);
  const video = safeVideoUrl(data.videoUrl);
  if (video) {
    const link = element('a', 'video-link');
    link.href = video; link.target = '_blank'; link.rel = 'noopener noreferrer';
    link.append(icon('play'), element('span', '', '视频 ↗'));
    link.setAttribute('aria-label', `查看${data.name}的视频（新窗口打开）`);
    panel.append(link);
  }
  return panel;
}

function reviewCard(submission) {
  const card = element('details', 'review-card review-entry');
  card.dataset.submissionId = submission.id;
  card.open = openReviews.has(submission.id);
  card.addEventListener('toggle', () => {
    if (card.open) openReviews.add(submission.id); else openReviews.delete(submission.id);
  });
  const summary = element('summary', 'review-summary');
  summary.append(element('span', 'review-name', submission.after?.name || submission.before?.name || '菜品申请'), icon('chevron', 'icon review-chevron'));
  summary.addEventListener('click', event => { if (busyIds.has(submission.id)) event.preventDefault(); });
  const details = element('div', 'review-details');
  card.append(summary, details);
  const heading = element('div', 'review-card-header');
  const titleBlock = element('div');
  const titleRow = element('div', 'review-title-row');
  titleRow.append(element('span', `pill pill-${String(submission.operation).toLowerCase()}`, operations[submission.operation] || submission.operation));
  titleBlock.append(titleRow, element('p', 'review-id', `#${submission.id}`));
  const dateBlock = element('div', 'review-date');
  dateBlock.append(element('div', '', dateText(submission.createdAt, true)), element('span', `pill pill-${String(submission.status).toLowerCase()}`, statuses[submission.status] || submission.status));
  heading.append(titleBlock, dateBlock);
  const comparison = element('div', 'comparison');
  comparison.append(snapshot(submission.before, 'before', submission.operation), snapshot(submission.after, 'after', submission.operation));
  details.append(heading, comparison);
  if (submission.status === 'PENDING') {
    const actions = element('div', 'review-actions');
    const field = element('div', 'field');
    const noteId = `review-note-${submission.id}`;
    const label = element('label', '', '审核备注'); label.htmlFor = noteId;
    const note = element('textarea'); note.id = noteId; note.rows = 1; note.maxLength = 1000; note.value = drafts.get(submission.id) || '';
    note.addEventListener('input', () => drafts.set(submission.id, note.value));
    field.append(label, note);
    const buttons = element('div', 'button-row');
    const reject = element('button', 'button secondary');
    reject.type = 'button'; reject.append(icon('close'), element('span', '', '驳回'));
    reject.addEventListener('click', () => decide(submission, 'reject', card));
    const approve = element('button', 'button primary');
    approve.type = 'button'; approve.append(icon('check'), element('span', '', '通过'));
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
    details.append(actions, error);
  } else {
    const result = element('div', 'review-result');
    result.append(element('span', '', `审核：${submission.reviewedBy || '厨师'}`), element('span', '', dateText(submission.reviewedAt, true) || '—'));
    if (submission.reviewNote) result.append(element('p', 'review-note', `审核备注：${submission.reviewNote}`));
    details.append(result);
  }
  if (busyIds.has(submission.id)) card.querySelectorAll('button, textarea').forEach(control => { control.disabled = true; });
  return card;
}

function renderReviews() {
  content.setAttribute('aria-busy', 'false');
  $('#stat-pending').textContent = String(submissions.filter(item => item.status === 'PENDING').length);
  $('#stat-approved').textContent = String(submissions.filter(item => item.status === 'APPROVED').length);
  $('#stat-rejected').textContent = String(submissions.filter(item => item.status === 'REJECTED').length);
  const visible = submissions.filter(item => activeStatus === 'ALL' || item.status === activeStatus);
  $('#review-count').textContent = `${visible.length} 条`;
  if (!visible.length) {
    const pending = activeStatus === 'PENDING';
    content.replaceChildren(emptyState({ title: pending ? '暂无待审核' : '暂无记录', symbol: pending ? 'check' : 'bowl' }));
    return;
  }
  const list = element('div', 'review-list');
  visible.forEach(submission => list.append(reviewCard(submission)));
  content.replaceChildren(list);
}

export async function loadReviews() {
  if (refreshing) return;
  refreshing = true;
  $('#refresh-reviews').disabled = true;
  loading(content, '加载中…');
  try {
    const data = await api('/api/chef/submissions?status=ALL');
    if (!Array.isArray(data)) throw new Error('记录读取失败，请重试。');
    submissions = data; renderReviews();
  } catch (error) {
    if (authExpired(error)) return;
    content.setAttribute('aria-busy', 'false');
    content.replaceChildren(emptyState({ title: '加载失败', message: error.message, actionText: '重试', action: loadReviews, symbol: 'refresh' }));
  } finally { refreshing = false; $('#refresh-reviews').disabled = false; }
}

async function decide(submission, decision, card, dialog = null) {
  if (busyIds.has(submission.id)) return;
  busyIds.add(submission.id);
  card.setAttribute('aria-busy', 'true');
  $('.review-summary', card).setAttribute('aria-disabled', 'true');
  document.querySelectorAll('[data-status], #refresh-reviews').forEach(control => { control.disabled = true; });
  const errorNode = dialog ? $('#approve-delete-error') : $('.review-error', card);
  showError(errorNode, '');
  card.querySelectorAll('button, textarea').forEach(control => { control.disabled = true; });
  if (dialog) { setDialogBusy(dialog, true); $('#approve-delete-submit').textContent = '处理中…'; }
  try {
    const updated = await writeApi(`/api/chef/submissions/${encodeURIComponent(submission.id)}/${decision}`, { headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ note: (drafts.get(submission.id) || '').trim() }) });
    if (dialog) dialog.close();
    drafts.delete(submission.id);
    openReviews.delete(submission.id);
    const index = submissions.findIndex(item => item.id === submission.id);
    if (index !== -1 && updated?.id) submissions[index] = updated;
    toast(decision === 'approve' ? '已通过' : '已驳回');
    if (updated?.id) renderReviews(); else await loadReviews();
    document.dispatchEvent(new CustomEvent('menu:changed'));
  } catch (error) {
    if (!authExpired(error)) showError(errorNode, error.message);
  } finally {
    busyIds.delete(submission.id);
    card.removeAttribute('aria-busy');
    $('.review-summary', card).removeAttribute('aria-disabled');
    document.querySelectorAll('[data-status]').forEach(control => { control.disabled = busyIds.size > 0; });
    $('#refresh-reviews').disabled = refreshing || busyIds.size > 0;
    card.querySelectorAll('button, textarea').forEach(control => { control.disabled = false; });
    if (dialog) { setDialogBusy(dialog, false); $('#approve-delete-submit').textContent = '确认删除'; }
  }
}

export function initializeReviews() {
  if (initialized) return;
  initialized = true;
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
}
