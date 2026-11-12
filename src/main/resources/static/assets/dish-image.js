import { element } from './common.js';

// Attach listeners before assigning src so cached images follow the same reveal path.
export function createDishImage(url, name, { eager = false, onError = () => {} } = {}) {
  const image = element('img', 'dish-image');
  image.alt = name;
  image.loading = eager ? 'eager' : 'lazy';
  image.decoding = 'async';
  let settled = false;
  const failed = () => {
    if (settled) return;
    settled = true;
    onError();
  };
  const loaded = async () => {
    if (settled) return;
    settled = true;
    try { await image.decode(); } catch { /* Some browsers cannot decode an already loaded image again. */ }
    if (!image.naturalWidth) { onError(); return; }
    requestAnimationFrame(() => requestAnimationFrame(() => image.classList.add('is-loaded')));
  };
  image.addEventListener('load', loaded, { once: true });
  image.addEventListener('error', failed, { once: true });
  image.src = url;
  if (image.complete) queueMicrotask(() => image.naturalWidth ? loaded() : failed());
  return image;
}
