// Decorative plate rings: finite motion on navigation, never a continuous loop.
export function createBackgroundMotion() {
  const backdrop = document.createElement('div');
  backdrop.className = 'menu-backdrop';
  backdrop.setAttribute('aria-hidden', 'true');
  backdrop.inert = true;
  const namespace = 'http://www.w3.org/2000/svg';
  for (let group = 0; group < 2; group += 1) {
    const svg = document.createElementNS(namespace, 'svg');
    svg.classList.add('menu-backdrop-rings');
    svg.setAttribute('viewBox', '0 0 1100 1100');
    svg.setAttribute('focusable', 'false');
    for (const radius of [235, 292, 349, 406, 463, 520]) {
      const circle = document.createElementNS(namespace, 'circle');
      circle.setAttribute('cx', '550');
      circle.setAttribute('cy', '550');
      circle.setAttribute('r', String(radius));
      svg.append(circle);
    }
    backdrop.append(svg);
  }
  document.body.prepend(backdrop);
  const preference = window.matchMedia('(prefers-reduced-motion: reduce)');
  let animation = null;
  let firstPlay = true;
  let destroyed = false;

  function stop() {
    animation?.cancel();
    animation = null;
  }

  function play() {
    if (destroyed) return;
    // Read the current visual position before cancellation so rapid navigation
    // continues from the interrupted frame instead of flashing back to start.
    const current = animation ? getComputedStyle(backdrop) : null;
    const from = current ? { opacity: current.opacity, transform: current.transform }
      : { opacity: firstPlay ? 0 : .65, transform: firstPlay ? 'translateY(22px) scale(1.025)' : 'translateY(9px) scale(1.012)' };
    stop();
    firstPlay = false;
    if (preference.matches || typeof backdrop.animate !== 'function') return;
    animation = backdrop.animate([from, { opacity: 1, transform: 'none' }], {
      duration: 800,
      easing: 'cubic-bezier(.22, 1, .36, 1)'
    });
    const running = animation;
    running.addEventListener('finish', () => {
      if (animation === running) animation = null;
    }, { once: true });
  }

  function preferenceChanged() {
    if (preference.matches) stop();
  }
  preference.addEventListener('change', preferenceChanged);
  return {
    play,
    destroy() {
      destroyed = true;
      stop();
      preference.removeEventListener('change', preferenceChanged);
      backdrop.remove();
    }
  };
}
