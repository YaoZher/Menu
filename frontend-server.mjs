import http from 'node:http';
import { isIP } from 'node:net';
import { spawn } from 'node:child_process';
import { existsSync, mkdirSync, statSync } from 'node:fs';
import { isAbsolute, resolve } from 'node:path';
import { pathToFileURL } from 'node:url';

const HOP_HEADERS = new Set([
  'connection', 'keep-alive', 'proxy-authenticate', 'proxy-authorization',
  'proxy-connection', 'te', 'trailer', 'transfer-encoding', 'upgrade',
]);

function cleanHeaders(source) {
  const connectionTokens = String(source.connection || '').toLowerCase().split(',').map(value => value.trim());
  const excluded = new Set([...HOP_HEADERS, ...connectionTokens]);
  return Object.fromEntries(Object.entries(source).filter(([name]) => !excluded.has(name.toLowerCase())));
}

function isLoopback(address = '') {
  return address === '::1' || /^(?:::ffff:)?127\.\d+\.\d+\.\d+$/.test(address);
}

function forwardedHeaders(request) {
  const headers = cleanHeaders(request.headers);
  for (const name of Object.keys(headers)) {
    if (name === 'forwarded' || name.startsWith('x-forwarded-') || name === 'cf-connecting-ip') delete headers[name];
  }
  const host = request.headers.host || `localhost:${request.socket.localPort}`;
  const cloudflareIp = request.headers['cf-connecting-ip'];
  const trustedCloudflare = isLoopback(request.socket.remoteAddress)
    && /^menu\.yaozher\.com(?::(?:80|443))?$/i.test(host)
    && typeof cloudflareIp === 'string' && isIP(cloudflareIp) !== 0;
  const protocol = trustedCloudflare && request.headers['x-forwarded-proto'] === 'https' ? 'https' : 'http';
  let port = protocol === 'https' ? '443' : '80';
  if (!trustedCloudflare) {
    try { port = new URL(`http://${host}`).port || '80'; } catch { /* Keep the HTTP default. */ }
  }
  headers.host = host;
  headers['x-forwarded-for'] = trustedCloudflare ? cloudflareIp : request.socket.remoteAddress || '127.0.0.1';
  headers['x-forwarded-proto'] = protocol;
  headers['x-forwarded-host'] = host;
  headers['x-forwarded-port'] = port;
  return headers;
}

function portNumber(value, name) {
  if (!/^\d+$/.test(String(value))) throw new Error(`${name} must be a port number.`);
  const port = Number(value);
  if (port < 1 || port > 65535) throw new Error(`${name} must be between 1 and 65535.`);
  return port;
}

export function createFrontendServer({ backendPort = 12233, upstreamTimeoutMs = 30_000 } = {}) {
  const port = portNumber(backendPort, 'Backend port');
  return http.createServer((request, response) => {
    if (!request.url?.startsWith('/')) {
      response.writeHead(400, { 'Content-Type': 'text/plain; charset=utf-8' });
      response.end('Invalid request target.');
      return;
    }
    const upstream = http.request({
      hostname: '127.0.0.1', port, method: request.method, path: request.url,
      headers: forwardedHeaders(request),
    });
    const fail = () => {
      if (response.destroyed) return;
      if (response.headersSent) { response.destroy(); return; }
      response.writeHead(502, { 'Content-Type': 'text/plain; charset=utf-8', 'Cache-Control': 'no-store' });
      response.end('Menu backend is unavailable.');
    };
    upstream.setTimeout(upstreamTimeoutMs, () => upstream.destroy(new Error('Backend timed out.')));
    upstream.on('error', fail);
    upstream.on('response', incoming => {
      if (response.destroyed) { incoming.destroy(); return; }
      incoming.on('error', () => response.destroy());
      incoming.on('aborted', () => response.destroy());
      response.writeHead(incoming.statusCode || 502, cleanHeaders(incoming.headers));
      incoming.pipe(response);
    });
    request.on('aborted', () => upstream.destroy());
    request.on('error', () => upstream.destroy());
    response.on('close', () => { if (!response.writableEnded) upstream.destroy(); });
    request.pipe(upstream);
  });
}

function run() {
  const args = process.argv.slice(2);
  const options = {};
  for (let index = 0; index < args.length; index += 2) {
    if (!['--jar', '--tmp'].includes(args[index]) || !args[index + 1]) throw new Error('Usage: node frontend-server.mjs [--jar <absolute.jar> --tmp <absolute-directory>]');
    options[args[index].slice(2)] = args[index + 1];
  }
  if (Boolean(options.jar) !== Boolean(options.tmp)) throw new Error('--jar and --tmp must be supplied together.');
  if (options.jar) {
    if (!isAbsolute(options.jar) || !existsSync(options.jar) || !statSync(options.jar).isFile()) throw new Error('--jar must identify an existing absolute JAR path.');
    if (!isAbsolute(options.tmp)) throw new Error('--tmp must be an absolute directory path.');
    mkdirSync(options.tmp, { recursive: true });
  }
  const backendPort = portNumber(process.env.PORT || 12233, 'PORT');
  const frontendPort = portNumber(process.env.FRONTEND_PORT || 34455, 'FRONTEND_PORT');
  const server = createFrontendServer({ backendPort });
  let child;
  let stopping = false;
  let forceTimer;
  const stop = (code, signal = 'SIGTERM') => {
    if (stopping) return;
    stopping = true;
    process.exitCode = code;
    server.close();
    server.closeAllConnections();
    if (child && child.exitCode === null && child.signalCode === null) {
      child.kill(signal);
      forceTimer = setTimeout(() => child.kill('SIGKILL'), 5000);
      forceTimer.unref();
    }
  };
  process.on('SIGINT', () => stop(130, 'SIGINT'));
  process.on('SIGTERM', () => stop(143));
  process.on('SIGHUP', () => stop(129));
  process.on('exit', () => {
    if (child && child.exitCode === null && child.signalCode === null) child.kill();
  });
  server.on('error', error => { console.error(`Frontend failed: ${error.message}`); stop(1); });
  server.listen(frontendPort, '127.0.0.1', () => {
    console.log(`Frontend: http://127.0.0.1:${frontendPort}/`);
    console.log(`Public URL: https://menu.yaozher.com/`);
    if (!options.jar) return;
    child = spawn('java', [
      '-Xms64m', '-Xmx384m', `-Djava.io.tmpdir=${options.tmp}`, `-Djdk.net.unixdomain.tmpdir=${options.tmp}`,
      '-jar', options.jar,
    ], { stdio: 'inherit', windowsHide: true, env: { ...process.env, PORT: String(backendPort), SERVER_ADDRESS: '127.0.0.1' } });
    child.on('error', error => { console.error(`Java failed: ${error.message}`); stop(1); });
    child.on('exit', (code, signal) => {
      if (forceTimer) clearTimeout(forceTimer);
      if (!stopping) {
        if (signal) console.error(`Java exited after ${signal}.`);
        stop(code ?? 1);
      }
    });
  });
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  try { run(); }
  catch (error) { console.error(`Startup failed: ${error.message}`); process.exitCode = 1; }
}
