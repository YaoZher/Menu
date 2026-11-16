// Page and filter changes move the same plate arcs to new resting positions.
// No timers or continuous animation run while the interface is idle.
export function createBackgroundMotion() {
  const backdrop = document.createElement('div');
  backdrop.className = 'menu-backdrop menu-backdrop-expressive';
  backdrop.setAttribute('aria-hidden', 'true');
  backdrop.inert = true;
  const namespace = 'http://www.w3.org/2000/svg';
  const svgNode = (name, attributes = {}) => {
    const node = document.createElementNS(namespace, name);
    for (const [key, value] of Object.entries(attributes)) node.setAttribute(key, String(value));
    return node;
  };
  const glow = document.createElement('div');
  glow.className = 'menu-backdrop-glow';
  backdrop.append(glow);
  const arc = (radius, start, end) => {
    const point = angle => {
      const radians = angle * Math.PI / 180;
      return `${600 + radius * Math.cos(radians)} ${600 + radius * Math.sin(radians)}`;
    };
    return `M ${point(start)} A ${radius} ${radius} 0 1 1 ${point(end)}`;
  };
  const orbits = ['near', 'far'].map((name, index) => {
    const svg = svgNode('svg', { viewBox: '0 0 1200 1200', focusable: 'false', class: `menu-orbit menu-orbit--${name}` });
    const defs = svgNode('defs');
    const gradientId = `menu-orbit-ink-${name}`;
    const gradient = svgNode('linearGradient', { id: gradientId, x1: '0%', y1: '100%', x2: '100%', y2: '0%' });
    for (const [offset, color, opacity] of [['0%', '#91ad80', .05], ['38%', '#6b926b', .46], ['72%', '#456f59', .72], ['100%', '#b5c8a0', .08]]) {
      gradient.append(svgNode('stop', { offset, 'stop-color': color, 'stop-opacity': opacity }));
    }
    defs.append(gradient); svg.append(defs);
    for (const [radius, width, opacity, trim] of [[275, 18, .9, 0], [357, 8, .65, 18], [442, 14, .6, -10], [524, 2, .75, 8]]) {
      svg.append(svgNode('path', {
        d: arc(radius, 115 + trim + index * 24, 425 + trim + index * 24),
        fill: 'none', stroke: `url(#${gradientId})`, 'stroke-width': width,
        'stroke-linecap': 'round', opacity
      }));
    }
    backdrop.append(svg);
    return svg;
  });
  document.body.prepend(backdrop);
  const layers = [...orbits, glow];
  const preference = window.matchMedia('(prefers-reduced-motion: reduce)');
  const animations = new Map();
  const keys = new Map();
  let view = 'menu';
  let initialized = false;
  let destroyed = false;
  const compositions = {
    menu: [[0, 0, -8, 1], [0, 0, 19, 1], [0, 0, 0, 1]],
    shopping: [[-8, 8, 14, .94], [14, -10, -8, 1.08], [-5, 7, 0, 1.05]],
    review: [[-13, 16, 27, 1.1], [10, -18, -25, .98], [-9, 12, 0, 1.1]],
    detail: [[4, -8, -20, 1.04], [-6, -4, 8, 1.04], [6, -5, 0, 1.02]]
  };
  function phaseFor(key) {
    if (!key || key === 'all') return 0;
    const phases = { '主食': -3, '前菜': -2, '主菜': -1, '配菜': 1, '汤': 2, '水果': 3,
      PENDING: -2, APPROVED: -.5, REJECTED: 1.5, ALL: 3 };
    if (Object.hasOwn(phases, key)) return phases[key];
    let hash = 0;
    for (const letter of String(key)) hash = (hash * 31 + letter.codePointAt(0)) >>> 0;
    return (hash % 601 - 300) / 100;
  }
  function targets() {
    const phase = phaseFor(keys.get(view));
    return compositions[view].map(([x, y, rotation, scale], index) => {
      const weight = index === 1 ? -.65 : index === 2 ? .4 : 1;
      return {
        transform: `translate(${x + phase * .85 * weight}%, ${y - phase * .45 * weight}%) rotate(${rotation + phase * 2.4 * weight}deg) scale(${scale})`,
        opacity: index === 0 ? '0.36' : index === 1 ? '0.25' : '1'
      };
    });
  }
  function stop() {
    for (const animation of animations.values()) animation.cancel();
    animations.clear();
  }
  function play(options = {}) {
    if (destroyed) return;
    const kind = options.kind || (initialized ? 'page' : 'initial');
    if (Object.hasOwn(compositions, options.view)) view = options.view;
    if (options.key !== undefined) keys.set(view, String(options.key));
    const direction = options.direction === -1 ? -1 : 1;
    const next = targets();
    // Sample before cancellation so rapid taps continue from the visible frame.
    const previous = layers.map(layer => {
      const style = getComputedStyle(layer);
      return { transform: style.transform, opacity: style.opacity };
    });
    stop();
    layers.forEach((layer, index) => { Object.assign(layer.style, next[index]); });
    backdrop.dataset.scene = view;
    backdrop.dataset.motionKind = kind;
    if (!preference.matches && typeof backdrop.animate === 'function') {
      layers.forEach((layer, index) => {
        const first = !initialized || kind === 'initial';
        const from = first ? {
          transform: `translate(${direction * (index === 1 ? -4 : 4)}%, 3%) rotate(${direction * 7}deg) scale(1.035)`,
          opacity: '0'
        } : previous[index];
        const animation = layer.animate([from, next[index]], {
          duration: kind === 'filter' ? 420 : first ? 900 : 760,
          easing: 'cubic-bezier(.22, 1, .36, 1)'
        });
        animations.set(layer, animation);
        animation.finished.then(() => {
          if (animations.get(layer) === animation) animations.delete(layer);
        }).catch(() => {});
      });
    }
    initialized = true;
  }
  const preferenceChanged = () => { if (preference.matches) stop(); };
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
