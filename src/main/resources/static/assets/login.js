import { $, writeApi, showError } from './common.js';

const form = $('#login-form');
const submit = $('#login-submit');
let submitting = false;
if (new URLSearchParams(location.search).has('expired')) $('#login-notice').hidden = false;

$('#password-toggle').addEventListener('click', () => {
  const password = $('#password');
  const visible = password.type === 'password';
  password.type = visible ? 'text' : 'password';
  $('#password-toggle').setAttribute('aria-label', visible ? '隐藏密码' : '显示密码');
  $('#password-toggle').setAttribute('aria-pressed', String(visible));
});

form.addEventListener('submit', async event => {
  event.preventDefault();
  if (submitting || !form.reportValidity()) return;
  showError($('#login-error'), '');
  const username = $('#username').value.trim();
  if (!username) { showError($('#login-error'), '请输入管理员账号。'); $('#username').focus(); return; }
  const body = new URLSearchParams({ username, password: $('#password').value });
  submitting = true;
  form.querySelectorAll('button, input').forEach(control => { control.disabled = true; });
  submit.textContent = '正在登录…';
  try {
    await writeApi('/api/auth/login', { headers: { 'Content-Type': 'application/x-www-form-urlencoded' }, body });
    location.assign('/admin');
  } catch (error) {
    showError($('#login-error'), error.status === 401 ? '账号或密码不正确，请重新输入。' : error.message);
    $('#password').value = '';
    submitting = false;
    form.querySelectorAll('button, input').forEach(control => { control.disabled = false; });
    submit.textContent = '登录工作台 →';
    $('#password').focus();
  }
});

