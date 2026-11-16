const easeOut = 'cubic-bezier(.22, 1, .36, 1)';

// Motion never owns visibility, focus or navigation: it only decorates the
// already committed interface, so an interrupted transition cannot blank it.
export function createInterfaceMotion() {
  const preference = window.matchMedia('(prefers-reduced-motion: reduce)');
  const running = new Map();
  let destroyed = false;

  function stop() {
    for (const animation of running.values()) animation.cancel();
    running.clear();
  }

  function animate(node, frames, options) {
    if (destroyed || preference.matches || typeof node.animate !== 'function') return;
    const animation = node.animate(frames, { easing: easeOut, ...options });
    running.set(node, animation);
    animation.finished.then(() => {
      if (running.get(node) === animation) running.delete(node);
    }).catch(() => {});
  }

  function page(node, { direction = 1, initial = false } = {}) {
    stop();
    if (!node?.isConnected || !node.getClientRects().length) return;
    const sign = direction < 0 ? -1 : 1;
    animate(node, [
      { opacity: .72, transform: `translate(${initial ? 0 : sign * 12}px, ${initial ? 14 : 6}px) scale(.994)` },
      { opacity: 1, transform: 'none' }
    ], { duration: initial ? 400 : 320 });
  }

  function filter(container, { direction = 1 } = {}) {
    stop();
    if (!container?.isConnected || !container.getClientRects().length) return;
    const sign = direction < 0 ? -1 : 1;
    const candidates = [...container.querySelectorAll('.dish-card, .review-entry')];
    const rows = candidates.length ? candidates : [...container.querySelectorAll('.empty-state')];
    // Only animate rows actually visible in the viewport. Large menus should
    // neither create hundreds of animations nor replay when scrolled later.
    const visible = rows.filter(node => {
      const rect = node.getBoundingClientRect();
      return rect.width > 0 && rect.height > 0 && rect.bottom > 0 && rect.top < innerHeight;
    });
    visible.forEach((node, index) => animate(node, [
      { opacity: .64, transform: `translate(${sign * 8}px, 5px)` },
      { opacity: 1, transform: 'none' }
    ], { duration: 240, delay: Math.min(index * 18, 60), fill: 'backwards' }));
  }

  const preferenceChanged = () => { if (preference.matches) stop(); };
  preference.addEventListener('change', preferenceChanged);
  return {
    page, filter,
    destroy() {
      destroyed = true;
      stop();
      preference.removeEventListener('change', preferenceChanged);
    }
  };
}

// The marker lives in the scrollable content coordinates, so category strips
// on phones keep their selected pill aligned while being swiped horizontally.
export function createFilterMotion(group) {
  if (!group) return { sync() {}, destroy() {} };
  const marker = document.createElement('span');
  marker.className = 'filter-selection-marker';
  marker.setAttribute('aria-hidden', 'true');
  marker.hidden = true;
  group.classList.add('motion-filter-group');
  group.prepend(marker);
  const preference = window.matchMedia('(prefers-reduced-motion: reduce)');
  let animation = null;
  let ready = false;
  let destroyed = false;

  function sync({ animate = true } = {}) {
    if (destroyed) return;
    const selected = group.querySelector(':scope > button[aria-pressed="true"]');
    if (!selected || !group.getClientRects().length || !selected.getClientRects().length) {
      animation?.cancel(); animation = null;
      ready = false; marker.hidden = true;
      group.classList.remove('has-filter-marker');
      return;
    }
    const origin = group.getBoundingClientRect();
    const scaleX = origin.width / group.offsetWidth || 1;
    const scaleY = origin.height / group.offsetHeight || 1;
    const previousRect = ready ? marker.getBoundingClientRect() : null;
    const previous = previousRect ? {
      transform: `translate(${(previousRect.left - origin.left) / scaleX - group.clientLeft + group.scrollLeft}px, ${(previousRect.top - origin.top) / scaleY - group.clientTop + group.scrollTop}px)`,
      width: `${previousRect.width / scaleX}px`, height: `${previousRect.height / scaleY}px`
    } : null;
    animation?.cancel(); animation = null;
    const next = {
      transform: `translate(${selected.offsetLeft}px, ${selected.offsetTop}px)`,
      width: `${selected.offsetWidth}px`, height: `${selected.offsetHeight}px`
    };
    Object.assign(marker.style, next);
    marker.hidden = false;
    group.classList.add('has-filter-marker');
    if (ready && animate && !preference.matches && typeof marker.animate === 'function'
        && Object.keys(next).some(key => previous[key] !== next[key])) {
      const current = marker.animate([previous, next], { duration: 260, easing: easeOut });
      animation = current;
      current.finished.then(() => { if (animation === current) animation = null; }).catch(() => {});
    }
    ready = true;
  }

  const align = () => sync({ animate: false });
  const observer = typeof ResizeObserver === 'function' ? new ResizeObserver(align) : null;
  if (observer) {
    observer.observe(group);
    group.querySelectorAll(':scope > button').forEach(button => observer.observe(button));
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
      group.classList.remove('motion-filter-group', 'has-filter-marker');
    }
  };
}
