export class ImageValidationError extends Error {}

export function imageTypeFromBytes(bytes) {
  const png = [0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a];
  if (png.every((byte, index) => bytes[index] === byte)) return 'image/png';
  if (bytes[0] === 0xff && bytes[1] === 0xd8 && bytes[2] === 0xff) return 'image/jpeg';
  return null;
}

export async function prepareUploadImage(file) {
  if (file.size > 5 * 1024 * 1024) throw new ImageValidationError('图片不能超过 5 MB，请压缩后重新选择。');
  const bytes = new Uint8Array(await file.slice(0, 8).arrayBuffer());
  const type = imageTypeFromBytes(bytes);
  if (!type) throw new ImageValidationError('请选择有效的 JPG 或 PNG 图片。');
  // Mobile photo providers may omit MIME or report a generic/nonstandard type.
  return new File([file], file.name, { type, lastModified: file.lastModified });
}

export function validateImageDimensions(width, height) {
  if (width < 1 || height < 1 || width > 4096 || height > 4096 || width * height > 16_000_000) {
    throw new ImageValidationError('图片宽高不能超过 4096 像素，且总像素不能超过 1600 万，请缩小后重新选择。');
  }
}

function trimLinkPunctuation(value) {
  let previous;
  do {
    previous = value;
    value = value.replace(/[.,!?;:]+$/u, '');
    for (const [open, close] of [['(', ')'], ['[', ']'], ['{', '}']]) {
      const count = character => [...value].filter(item => item === character).length;
      if (value.endsWith(close) && count(close) > count(open)) value = value.slice(0, -1);
    }
  } while (previous !== value);
  return value;
}

export function extractVideoUrl(value) {
  if (value.length > 8192) throw new Error('分享内容不能超过 8192 个字符。');
  if (!value.trim()) return '';
  const match = /https?:\/\/[^\s<>"'`，。！？；：、（）【】《》“”‘’「」『』]*/iu.exec(value);
  const invalid = () => new Error('未找到有效的视频链接，请粘贴包含 http:// 或 https:// 的分享内容。');
  if (!match) throw invalid();
  const before = value[match.index - 1];
  const beforeColon = value[match.index - 2];
  const labelColon = before === ':' && (!beforeColon || /\s/u.test(beforeColon) || beforeColon.charCodeAt(0) >= 128);
  if (before && !labelColon && !/[\s([{<"'`]/u.test(before) && before.charCodeAt(0) < 128) throw invalid();
  const candidate = trimLinkPunctuation(match[0]);
  if (candidate.length > 2048) throw new Error('视频链接不能超过 2048 个字符。');
  try {
    const url = new URL(candidate);
    const authority = /^https?:\/\/([^/?#]*)/iu.exec(candidate)?.[1];
    // Keep validation aligned with the server URI parser; URL() alone repairs malformed input.
    if (!authority || /[^\x21-\x7e]|[\\%@]/u.test(authority) || candidate.includes('\\') ||
        !['http:', 'https:'].includes(url.protocol) || !url.hostname || url.username || url.password || url.port === '0') throw invalid();
    if (!/^(?:[a-z0-9](?:[a-z0-9.-]*[a-z0-9])?|\[[a-f0-9:.]+\])(?::\d+)?$/iu.test(authority)) throw invalid();
    if (!authority.startsWith('[') && authority.split(':')[0].split('.').some(label => !/^[a-z0-9](?:[a-z0-9-]*[a-z0-9])?$/iu.test(label))) throw invalid();
    // Java URI rejects malformed percent escapes and unescaped ASCII brackets/braces in paths.
    const path = candidate.slice(candidate.indexOf(authority) + authority.length).split(/[?#]/u)[0];
    if (/%(?![a-f0-9]{2})|[\u0000-\u0020{}|^`]/iu.test(candidate) || /[\[\]]/u.test(path)) throw invalid();
    return candidate;
  } catch { throw invalid(); }
}
