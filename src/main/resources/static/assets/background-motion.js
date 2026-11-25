const actionEvent = 'menu:background-action';
let sceneRevision = 0;

export function signalBackground(kind) {
  document.dispatchEvent(new CustomEvent(actionEvent, { detail: kind }));
}

export function captureBackground() {
  const revision = sceneRevision;
  return kind => { if (revision === sceneRevision) signalBackground(kind); };
}

const compositions = {
  menu: [[0, 0, -8, 1], [0, 0, 19, 1], [0, 0, 0, 1]],
  shopping: [[-8, 8, 14, .94], [14, -10, -8, 1.08], [-5, 7, 0, 1.05]],
  review: [[-13, 16, 27, 1.1], [10, -18, -25, .98], [-9, 12, 0, 1.1]],
  detail: [[4, -8, -20, 1.04], [-6, -4, 8, 1.04], [6, -5, 0, 1.02]],
  login: [[-11, 11, 6, 1.08], [8, -7, 28, 1.02], [0, 4, 0, 1.08]]
};

// Offsets from the resting composition: x, y, rotation, scale and light.
// Short actions return to rest. Only navigation and filters change the resting pose.
const effects = {
  page:            [730, [8,  2,   8,  .02, -.05]],
  history:         [620, [-7, -3, -9, -.03, -.12]],
  'detail-open':   [610, [-3, -7, -5,  .06,  .08]],
  'detail-back':   [570, [3,  6,   5, -.05, -.10]],
  filter:          [440, [2,  0,   5,  .01,  .04]],
  'tag-filter':    [480, [0, -2,  -7,  .05,  .08]],
  'review-filter': [460, [0,  4,   6, -.02,  .06]],
  'tags-open':     [440, [0, -3,  -4,  .04,  .08]],
  'tags-close':    [370, [0,  3,   4, -.03, -.08]],
  search:          [390, [-4, 0,   0,  .02,  .10]],
  refresh:         [570, [0,  0,  14,  0,    .06]],
  'editor-add':    [550, [1, -4,   3,  .08,  .12]],
  'editor-edit':   [460, [-4, 0,  -8,  .035, .06]],
  'editor-delete': [510, [0,  3,   5, -.06, -.13]],
  'editor-cancel': [390, [2,  2,  -4, -.025,-.09]],
  saved:           [580, [0, -3,   1,  .09,  .17]],
  submitted:       [530, [0, -6,  -4,  .05,  .11]],
  deleted:         [490, [0,  5,   6, -.08, -.16]],
  image:           [440, [2, -1,   0,  .03,  .19]],
  'image-remove':  [360, [-2, 1,   0, -.03, -.14]],
  note:            [320, [-1, 2,   2,  .01,  .04]],
  'note-step':     [340, [2, -1,  -2,  .025, .06]],
  'tag-edit':      [360, [1, -2,   4,  .015, .08]],
  'tag-create':    [450, [2, -3,  -4,  .045, .10]],
  'shopping-add':  [540, [-6, 2,   7,  .03,  .12]],
  'shopping-remove':[470,[6, -2,  -7, -.035,-.12]],
  'shopping-check':[350, [0, -2,   4,  .04,  .11]],
  'shopping-uncheck':[350,[0, 2,  -4, -.03, -.08]],
  recipe:          [470, [-2, -5, -3,  .06,  .08]],
  'recipe-close':  [410, [2,  5,   3, -.045,-.08]],
  'review-open':   [460, [5,  1,  -5,  .045, .08]],
  'review-close':  [380, [-5,-1,   5, -.03, -.08]],
  'review-approve':[590, [3, -5,   9,  .06,  .18]],
  'review-reject': [490, [-3, 5, -10, -.045,-.15]],
  'sort-start':    [490, [-5, 0,   0, -.03,  .04]],
  'sort-move':     [360, [6,  0,   3,  .01,  .06]],
  'sort-save':     [570, [2,  0,   4,  .055, .14]],
  'sort-cancel':   [470, [-6, 0,  -5, -.02, -.06]],
  password:        [350, [0,  0,  -6,  0,    .09]],
  'login-submit':  [550, [0, -4,   4, -.045, .14]],
  logout:          [480, [5,  3,  -3, -.035,-.09]],
  error:           [420, [-3, 0,  -4, -.02, -.20], [1.5, 0, 2, 0, -.07]]
};

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
  function phaseFor(key) {
    if (!key || key === 'all') return 0;
    const phases = { '主食': -3, '前菜': -2, '主菜': -1, '配菜': 1, '汤': 2, '水果': 3,
      PENDING: -2, APPROVED: -.5, REJECTED: 1.5, ALL: 3 };
    if (Object.hasOwn(phases, key)) return phases[key];
    let hash = 0;
    for (const letter of String(key)) hash = (hash * 31 + letter.codePointAt(0)) >>> 0;
    return (hash % 601 - 300) / 100;
  }
  function poses(shift = [], direction = 1) {
    const phase = phaseFor(keys.get(view));
    const [shiftX = 0, shiftY = 0, turn = 0, zoom = 0, light = 0] = shift;
    return compositions[view].map(([x, y, rotation, scale], index) => {
      const weight = index === 1 ? -.65 : index === 2 ? .4 : 1;
      const opacity = index === 0 ? .36 : index === 1 ? .25 : .88;
      const tx = x + (phase * .85 + shiftX * direction) * weight;
      const ty = y + (-phase * .45 + shiftY) * weight;
      const angle = rotation + (phase * 2.4 + turn * direction) * weight;
      return {
        transform: `translate(${tx}%, ${ty}%) rotate(${angle}deg) scale(${scale + zoom * Math.abs(weight)})`,
        opacity: String(Math.min(1, opacity + light * (index === 2 ? 1 : .55)))
      };
    });
  }
  function stop() {
    for (const animation of animations.values()) animation.cancel();
    animations.clear();
  }
  function play({ kind = 'initial', view: destination, key, direction = 1 } = {}) {
    if (destroyed) return;
    if (kind !== 'initial' && !Object.hasOwn(effects, kind)) return;
    if (Object.hasOwn(compositions, destination)) {
      if (view !== destination || ['initial', 'page', 'history', 'detail-open', 'detail-back'].includes(kind)) sceneRevision += 1;
      view = destination;
    }
    if (key !== undefined) keys.set(view, String(key));
    const initial = !initialized || kind === 'initial';
    const canAnimate = !preference.matches && typeof backdrop.animate === 'function';
    // Sample before cancellation so rapid taps continue from the visible frame.
    const previous = canAnimate && !initial ? layers.map(layer => {
      const style = animations.has(layer) ? getComputedStyle(layer) : layer.style;
      return { transform: style.transform, opacity: style.opacity };
    }) : null;
    stop();
    const next = poses();
    layers.forEach((layer, index) => { Object.assign(layer.style, next[index]); });
    backdrop.dataset.scene = view;
    backdrop.dataset.motionKind = kind;
    initialized = true;
    if (canAnimate) {
      const [duration, ...steps] = initial ? [820] : effects[kind];
      const peaks = steps.map(step => poses(step, direction));
      const entrance = initial ? poses([4, 3, 7, -.045], direction) : null;
      layers.forEach((layer, index) => {
        const from = initial ? { ...entrance[index], opacity: '0' } : previous[index];
        const frames = [from, ...peaks.map((peak, step) => ({ ...peak[index], offset: step === 0 ? .37 : .65 })), next[index]];
        const animation = layer.animate(frames, { duration, easing: 'cubic-bezier(.28, .18, .25, 1)' });
        animations.set(layer, animation);
        animation.finished.then(() => {
          if (animations.get(layer) === animation) animations.delete(layer);
        }).catch(() => {});
      });
    }
  }
  const onAction = event => play({ kind: event.detail });
  const preferenceChanged = () => { if (preference.matches) stop(); };
  document.addEventListener(actionEvent, onAction);
  preference.addEventListener('change', preferenceChanged);
  return {
    play,
    destroy() {
      destroyed = true;
      stop();
      document.removeEventListener(actionEvent, onAction);
      preference.removeEventListener('change', preferenceChanged);
      backdrop.remove();
    }
  };
}
