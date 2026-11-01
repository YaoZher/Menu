// Animate only the navigation marker; view visibility and focus always update immediately.
export function createNavigationMotion(nav) {
  if (!nav) return { sync() {}, destroy() {} };
  const marker = document.createElement('span');
  marker.className = 'chef-nav-marker';
  marker.setAttribute('aria-hidden', 'true');
  marker.hidden = true;
  nav.prepend(marker);
  const preference = window.matchMedia('(prefers-reduced-motion: reduce)');
  let animation = null;
  let ready = false;
  let destroyed = false;

  function sync({ animate = true } = {}) {
    if (destroyed) return;
    const selected = nav.querySelector('a[aria-current="page"]');
    if (!selected || !nav.getClientRects().length) {
      animation?.cancel(); animation = null; ready = false;
      marker.hidden = true; nav.classList.remove('has-nav-marker');
      return;
    }
    const origin = nav.getBoundingClientRect();
    const target = selected.getBoundingClientRect();
    const frame = rect => ({
      transform: `translate(${rect.left - origin.left - nav.clientLeft}px, ${rect.top - origin.top - nav.clientTop}px)`,
      width: `${rect.width}px`, height: `${rect.height}px`
    });
    // Read the in-flight visual position before cancelling so fast taps do not jump backwards.
    const previous = ready ? frame(marker.getBoundingClientRect()) : null;
    animation?.cancel(); animation = null;
    const next = frame(target);
    Object.assign(marker.style, next);
    marker.hidden = false;
    nav.classList.add('has-nav-marker');
    if (ready && animate && !preference.matches && typeof marker.animate === 'function'
        && Object.keys(next).some(key => previous[key] !== next[key])) {
      const current = marker.animate([previous, next], {
        duration: 200, easing: 'cubic-bezier(.22, 1, .36, 1)'
      });
      animation = current;
      current.finished.then(() => { if (animation === current) animation = null; }).catch(() => {});
    }
    ready = true;
  }

  const align = () => sync({ animate: false });
  const observer = typeof ResizeObserver === 'function' ? new ResizeObserver(align) : null;
  if (observer) {
    observer.observe(nav);
    nav.querySelectorAll('a').forEach(link => observer.observe(link));
  } else window.addEventListener('resize', align);
  preference.addEventListener('change', align);
  document.fonts?.ready.then(align);

  return {
    sync,
    destroy() {
      destroyed = true;
      animation?.cancel();
      observer?.disconnect();
      window.removeEventListener('resize', align);
      preference.removeEventListener('change', align);
      marker.remove();
      nav.classList.remove('has-nav-marker');
    }
  };
}
