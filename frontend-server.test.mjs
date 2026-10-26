import test from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import { once } from 'node:events';
import { spawn, spawnSync } from 'node:child_process';
import { tmpdir } from 'node:os';
import { fileURLToPath } from 'node:url';
import { createFrontendServer } from './frontend-server.mjs';

async function listen(server, t) {
  server.listen(0, '127.0.0.1');
  await once(server, 'listening');
  t.after(() => new Promise(resolve => { server.close(resolve); server.closeAllConnections(); }));
  return server.address().port;
}

async function proxyFor(t, handler) {
  const backend = http.createServer(handler);
  const backendPort = await listen(backend, t);
  const frontend = createFrontendServer({ backendPort, upstreamTimeoutMs: 1000 });
  const port = await listen(frontend, t);
  return { port, backend, frontend };
}

function requestTo(port, options = {}, body = '') {
  return new Promise((resolve, reject) => {
    const request = http.request({ hostname: '127.0.0.1', port, path: '/', ...options }, response => {
      const chunks = [];
      response.on('data', chunk => chunks.push(chunk));
      response.on('error', reject);
      response.on('end', () => resolve({ status: response.statusCode, headers: response.headers, body: Buffer.concat(chunks).toString() }));
    });
    request.on('error', reject);
    request.end(body);
  });
}

test('streams uploads before the body ends and preserves cookies, CSRF, and redirects', async t => {
  let firstChunk;
  const firstChunkSeen = new Promise(resolve => { firstChunk = resolve; });
  let observed;
  const { port } = await proxyFor(t, (request, response) => {
    const chunks = [];
    request.on('data', chunk => { chunks.push(chunk); firstChunk(); });
    request.on('end', () => {
      observed = { method: request.method, url: request.url, headers: request.headers, body: Buffer.concat(chunks).toString() };
      response.writeHead(302, { Location: '/login?next=%2Fadmin', 'Set-Cookie': ['JSESSIONID=test; Path=/; HttpOnly; SameSite=Strict', 'other=value; Path=/'] });
      response.end('redirect');
    });
  });
  let upload;
  const result = new Promise((resolve, reject) => {
    upload = http.request({ hostname: '127.0.0.1', port, path: '/api/submissions?check=1', method: 'POST', headers: {
      Host: 'menu.yaozher.com', Cookie: 'JSESSIONID=before', 'X-CSRF-TOKEN': 'csrf-token', 'Content-Type': 'multipart/form-data; boundary=test',
    } }, response => {
      response.resume();
      response.on('end', () => resolve({ status: response.statusCode, headers: response.headers }));
    });
    upload.on('error', reject);
  });
  upload.write('--test\r\nfirst');
  await firstChunkSeen;
  upload.end('\r\nlast\r\n--test--');
  const response = await result;
  assert.equal(observed.body, '--test\r\nfirst\r\nlast\r\n--test--');
  assert.equal(observed.method, 'POST');
  assert.equal(observed.url, '/api/submissions?check=1');
  assert.equal(observed.headers.cookie, 'JSESSIONID=before');
  assert.equal(observed.headers['x-csrf-token'], 'csrf-token');
  assert.equal(observed.headers.host, 'menu.yaozher.com');
  assert.equal(response.status, 302);
  assert.equal(response.headers.location, '/login?next=%2Fadmin');
  assert.deepEqual(response.headers['set-cookie'], ['JSESSIONID=test; Path=/; HttpOnly; SameSite=Strict', 'other=value; Path=/']);
});

test('rebuilds trusted Cloudflare forwarding headers and removes hop-by-hop headers in both directions', async t => {
  let seen;
  const { port } = await proxyFor(t, (request, response) => {
    seen = request.headers;
    response.writeHead(200, { Connection: 'x-response-secret', 'X-Response-Secret': 'removed', 'Proxy-Authenticate': 'removed', 'X-Application': 'kept' });
    response.end('ok');
  });
  const result = await requestTo(port, { headers: {
    Host: 'menu.yaozher.com', Connection: 'keep-alive, x-remove-me', 'X-Remove-Me': 'removed', 'Proxy-Authorization': 'removed',
    Forwarded: 'for=1.1.1.1;host=evil.example', 'X-Forwarded-For': '1.1.1.1', 'X-Forwarded-Host': 'evil.example',
    'X-Forwarded-Port': '666', 'X-Forwarded-Prefix': '/evil', 'X-Forwarded-Proto': 'https', 'CF-Connecting-IP': '203.0.113.9',
  } });
  assert.equal(seen['x-forwarded-for'], '203.0.113.9');
  assert.equal(seen['x-forwarded-proto'], 'https');
  assert.equal(seen['x-forwarded-host'], 'menu.yaozher.com');
  assert.equal(seen['x-forwarded-port'], '443');
  for (const header of ['forwarded', 'x-forwarded-prefix', 'x-remove-me', 'proxy-authorization', 'cf-connecting-ip']) assert.equal(seen[header], undefined);
  assert.equal(result.headers['x-response-secret'], undefined);
  assert.equal(result.headers['proxy-authenticate'], undefined);
  assert.equal(result.headers['x-application'], 'kept');
});

test('ignores forged forwarding headers on local requests and invalid Cloudflare IPs', async t => {
  const { port } = await proxyFor(t, (request, response) => response.end(JSON.stringify(request.headers)));
  for (const [host, cloudflareIp, expectedPort] of [[`localhost:${port}`, '203.0.113.9', String(port)], ['menu.yaozher.com', 'not-an-ip', '80']]) {
    const result = await requestTo(port, { headers: { Host: host, 'X-Forwarded-Proto': 'https', 'X-Forwarded-For': '9.9.9.9', 'CF-Connecting-IP': cloudflareIp } });
    const headers = JSON.parse(result.body);
    assert.equal(headers['x-forwarded-for'], '127.0.0.1');
    assert.equal(headers['x-forwarded-proto'], 'http');
    assert.equal(headers['x-forwarded-port'], expectedPort);
    assert.equal(headers.host, host);
  }
});

test('returns 502 when the backend is unavailable and rejects absolute proxy targets', async t => {
  const temporary = http.createServer();
  temporary.listen(0, '127.0.0.1');
  await once(temporary, 'listening');
  const backendPort = temporary.address().port;
  await new Promise(resolve => temporary.close(resolve));
  const port = await listen(createFrontendServer({ backendPort }), t);
  assert.equal((await requestTo(port)).status, 502);
  assert.equal((await requestTo(port, { path: 'http://example.com/private' })).status, 400);
});

test('backend disconnect during a response does not crash the proxy', async t => {
  let calls = 0;
  const { port } = await proxyFor(t, (request, response) => {
    calls++;
    if (calls === 1) {
      response.writeHead(200, { 'Content-Length': '100' });
      response.write('partial');
      setTimeout(() => response.destroy(), 10);
    } else response.end('healthy');
  });
  await assert.rejects(requestTo(port));
  assert.equal((await requestTo(port)).body, 'healthy');
});

test('aborted client upload closes its backend request and the proxy remains usable', async t => {
  let markStarted;
  let markAborted;
  const started = new Promise(resolve => { markStarted = resolve; });
  const aborted = new Promise(resolve => { markAborted = resolve; });
  const { port } = await proxyFor(t, (request, response) => {
    if (request.method === 'POST') {
      request.on('data', markStarted);
      request.on('aborted', markAborted);
      request.on('error', () => {});
    } else response.end('healthy');
  });
  const upload = http.request({ hostname: '127.0.0.1', port, method: 'POST' });
  upload.on('error', () => {});
  upload.write('first');
  await started;
  upload.destroy();
  await aborted;
  assert.equal((await requestTo(port)).body, 'healthy');
});

async function availablePort() {
  const server = http.createServer();
  server.listen(0, '127.0.0.1');
  await once(server, 'listening');
  const port = server.address().port;
  await new Promise(resolve => server.close(resolve));
  return port;
}

function runCli(port, environment = {}) {
  return new Promise((resolve, reject) => {
    const child = spawn(process.execPath, [fileURLToPath(new URL('./frontend-server.mjs', import.meta.url)),
      '--jar', fileURLToPath(import.meta.url), '--tmp', tmpdir()], {
      env: { ...process.env, FRONTEND_PORT: String(port), ...environment },
      windowsHide: true,
    });
    let stdout = '';
    let stderr = '';
    child.stdout.on('data', chunk => { stdout += chunk; });
    child.stderr.on('data', chunk => { stderr += chunk; });
    child.on('error', reject);
    child.on('close', code => resolve({ code, stdout, stderr }));
  });
}

test('CLI closes the frontend and exits with failure when Java cannot be launched', async () => {
  const port = await availablePort();
  const env = {};
  for (const key of Object.keys(process.env)) if (key.toLowerCase() === 'path') env[key] = '';
  const result = await runCli(port, env);
  assert.equal(result.code, 1);
  assert.match(result.stdout, /Frontend:/);
  assert.match(result.stderr, /Java failed:/);
  await assert.rejects(requestTo(port));
});

test('CLI preserves Java failure exit status and closes its listening frontend', async t => {
  const java = spawnSync('java', ['-version'], { windowsHide: true, stdio: 'ignore' });
  if (java.error) { t.skip('Java is not installed.'); return; }
  const port = await availablePort();
  // An existing non-JAR file makes the real Java process terminate after spawn.
  const result = await runCli(port);
  assert.equal(result.code, 1);
  assert.match(result.stdout, /Frontend:/);
  await assert.rejects(requestTo(port));
});
