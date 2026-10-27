import test from 'node:test';
import assert from 'node:assert/strict';
import { imageTypeFromBytes, prepareUploadImage, validateImageDimensions, extractVideoUrl } from './src/main/resources/static/assets/submission-input.js';

const png = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jS8QAAAAASUVORK5CYII=', 'base64');

test('PNG contents take precedence over mobile MIME and filename metadata', async () => {
  for (const type of ['', 'image/x-png', 'application/octet-stream', 'image/jpeg']) {
    const original = new File([png], 'SCREENSHOT.PNG', { type, lastModified: 123 });
    const normalized = await prepareUploadImage(original);
    assert.equal(normalized.type, 'image/png');
    assert.equal(normalized.name, original.name);
    assert.equal(normalized.lastModified, original.lastModified);
    assert.deepEqual(new Uint8Array(await normalized.arrayBuffer()), new Uint8Array(png));
  }
  assert.equal((await prepareUploadImage(new File([png], 'screenshot.bin'))).type, 'image/png');
});

test('recognizes JPEG bytes without trusting a PNG suffix', async () => {
  const bytes = Uint8Array.of(0xff, 0xd8, 0xff, 0xe0);
  assert.equal(imageTypeFromBytes(bytes), 'image/jpeg');
  assert.equal((await prepareUploadImage(new File([bytes], 'photo.png', { type: 'image/png' }))).type, 'image/jpeg');
});

test('rejects empty/fake/unsupported images and oversized files distinctly', async () => {
  for (const bytes of [new Uint8Array(), new TextEncoder().encode('<svg/>'), Uint8Array.of(0x89, 0x50, 0x4e, 0x47)]) {
    assert.equal(imageTypeFromBytes(bytes), null);
    await assert.rejects(prepareUploadImage(new File([bytes], 'fake.png', { type: 'image/png' })), /有效的 JPG 或 PNG/u);
  }
  await assert.rejects(prepareUploadImage(new File([new Uint8Array(5 * 1024 * 1024 + 1)], 'large.png')), /5 MB/u);
});

test('enforces side and total pixel limits independently of image format', () => {
  validateImageDimensions(4096, 3906);
  validateImageDimensions(4000, 4000);
  for (const size of [[0, 1], [1, 4097], [4096, 4096]]) {
    assert.throws(() => validateImageDimensions(...size), /4096.*1600 万/u);
  }
});

test('extracts short and full video links from sharing text without changing query or fragment', () => {
  const samples = [
    ['', ''],
    [' \n ', ''],
    ['https://example.com/video?a=1&b=2#part', 'https://example.com/video?a=1&b=2#part'],
    ['打开抖音观看 视频链接：https://v.douyin.com/AbC/ 复制打开', 'https://v.douyin.com/AbC/'],
    ['链接:https://b23.tv/AbC\n复制打开哔哩哔哩', 'https://b23.tv/AbC'],
    ['这道菜【https://www.bilibili.com/video/BV123/?share_source=copy&vd_source=abc#reply】很好吃', 'https://www.bilibili.com/video/BV123/?share_source=copy&vd_source=abc#reply'],
    ['做法 https://www.xiaohongshu.com/explore/123?xsec_token=abc%2B123&xsec_source=pc_share。', 'https://www.xiaohongshu.com/explore/123?xsec_token=abc%2B123&xsec_source=pc_share'],
    ['《https://mp.weixin.qq.com/s?__biz=abc&mid=123&sn=456》', 'https://mp.weixin.qq.com/s?__biz=abc&mid=123&sn=456'],
    ['看这里 (https://example.com/video_(part)).', 'https://example.com/video_(part)'],
    ['HTTPS://example.com/video http://example.com/other', 'HTTPS://example.com/video'],
    ['http://localhost:12233/视频?tag[]=one#step[2]', 'http://localhost:12233/视频?tag[]=one#step[2]'],
    ['http://[::1]:1234/video', 'http://[::1]:1234/video'],
  ];
  for (const [input, expected] of samples) assert.equal(extractVideoUrl(input), expected, input);
});

test('rejects unsafe or malformed first links and limits text and final URL separately', () => {
  const invalid = [
    '复制这段文案，没有链接', 'javascript:alert(1)', 'javascript:https://example.com',
    'ftp://https://example.com', 'abchttps://example.com',
    'https://name:password@example.com', 'https://example.com:0/video', 'https://example.com:65536',
    'https://example..com/video', 'https://-example.com', 'https://example.com/a[b]',
    'https://example.com/invalid%zz', 'https://example.com/a\\b',
    'https://user@example.com https://example.com/valid', 'https:// https://example.com/valid',
  ];
  for (const input of invalid) assert.throws(() => extractVideoUrl(input), /有效的视频链接/u, input);
  assert.throws(() => extractVideoUrl('a'.repeat(8193)), /8192/u);
  assert.throws(() => extractVideoUrl('https://example.com/' + 'a'.repeat(2048)), /2048/u);
  assert.equal(extractVideoUrl('分享'.repeat(2000) + '\nhttps://b23.tv/abc'), 'https://b23.tv/abc');
});
