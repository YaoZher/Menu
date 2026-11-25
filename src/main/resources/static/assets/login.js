import { $, writeApi, showError, setButtonPending } from './common.js';
import { createBackgroundMotion, captureBackground, signalBackground } from './background-motion.js';

const backgroundMotion = createBackgroundMotion();
backgroundMotion.play({ kind: 'initial', view: 'login' });
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
  signalBackground('password');
});

form.addEventListener('submit', async event => {
  event.preventDefault();
  if (submitting || !form.reportValidity()) return;
  showError($('#login-error'), '');
  const username = $('#username').value.trim();
  if (!username) {
    showError($('#login-error'), '请输入账号。');
    $('#username').focus();
    signalBackground('error');
    return;
  }
  const body = new URLSearchParams({ username, password: $('#password').value });
  const motion = captureBackground();
  submitting = true;
  form.querySelectorAll('button, input').forEach(control => { control.disabled = true; });
  setButtonPending(submit, true, '登录中…');
  motion('login-submit');
  try {
    await writeApi('/api/chef/login', { headers: { 'Content-Type': 'application/x-www-form-urlencoded' }, body });
    location.assign('/');
  } catch (error) {
    showError($('#login-error'), error.status === 401 ? '账号或密码错误。' : error.message);
    motion('error');
    $('#password').value = '';
    submitting = false;
    form.querySelectorAll('button, input').forEach(control => { control.disabled = false; });
    setButtonPending(submit, false);
    $('#password').focus();
  }
});

