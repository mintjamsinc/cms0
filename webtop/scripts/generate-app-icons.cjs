/* eslint-disable */
/**
 * Generates per-app icon.svg files from the design in memos/App Icons.html.
 * Re-run when the design source changes.
 */
const fs = require('fs');
const path = require('path');

const SIZE = 180;
const RADIUS = 42;

function svgOpen(id) {
  return `<svg viewBox="0 0 ${SIZE} ${SIZE}" xmlns="http://www.w3.org/2000/svg" role="img" aria-label="${id}">
  <defs>
    <clipPath id="clip-${id}"><rect x="0" y="0" width="${SIZE}" height="${SIZE}" rx="${RADIUS}" ry="${RADIUS}"/></clipPath>
    <linearGradient id="hl-${id}" x1="0" y1="0" x2="0" y2="1">
      <stop offset="0%" stop-color="white" stop-opacity="0.25"/>
      <stop offset="55%" stop-color="white" stop-opacity="0"/>
    </linearGradient>
  </defs>`;
}

function svgClose(id) {
  return `
  <g clip-path="url(#clip-${id})">
    <rect x="0" y="0" width="${SIZE}" height="${SIZE}" fill="url(#hl-${id})"/>
    <rect x="0.5" y="0.5" width="${SIZE - 1}" height="${SIZE - 1}" rx="${RADIUS - 0.5}" ry="${RADIUS - 0.5}"
          fill="none" stroke="white" stroke-opacity="0.18" stroke-width="1"/>
  </g>
</svg>
`;
}

function grad(id, c1, c2, dir = 'd') {
  const coords = dir === 'v' ? 'x1="0" y1="0" x2="0" y2="1"' :
                 dir === 'h' ? 'x1="0" y1="0" x2="1" y2="0"' :
                 'x1="0" y1="0" x2="1" y2="1"';
  return `<linearGradient id="${id}" ${coords}>
    <stop offset="0%" stop-color="${c1}"/>
    <stop offset="100%" stop-color="${c2}"/>
  </linearGradient>`;
}

function bg(id, c1, c2, dir = 'd') {
  return `
  <defs>${grad('bg-' + id, c1, c2, dir)}</defs>
  <rect x="0" y="0" width="${SIZE}" height="${SIZE}" rx="${RADIUS}" ry="${RADIUS}" fill="url(#bg-${id})"/>`;
}

function iconBpmManager() {
  const id = 'bpm-mgr';
  return svgOpen(id) +
    bg(id, '#1e6cf3', '#0a3a8c') +
    `
  <g clip-path="url(#clip-${id})" fill="none" stroke="white" stroke-linecap="round">
    <path d="M 46 118 A 44 44 0 1 1 134 118" stroke-width="10" stroke-opacity="0.35"/>
    <path d="M 46 118 A 44 44 0 0 1 102 76" stroke-width="10"/>
    <line x1="90" y1="118" x2="120" y2="80" stroke-width="6"/>
    <circle cx="90" cy="118" r="6" fill="white" stroke="none"/>
    <g stroke-width="3" stroke-opacity="0.7">
      <line x1="46" y1="118" x2="40" y2="118"/>
      <line x1="134" y1="118" x2="140" y2="118"/>
      <line x1="90" y1="64" x2="90" y2="56"/>
    </g>
  </g>` +
    svgClose(id);
}

function iconBpmnModeler() {
  const id = 'bpmn-mod';
  return svgOpen(id) +
    bg(id, '#3a86ff', '#1d4ed8') +
    `
  <g clip-path="url(#clip-${id})">
    <g stroke="white" stroke-width="4" stroke-linecap="round" fill="none" stroke-opacity="0.85">
      <line x1="58" y1="90" x2="78" y2="90"/>
      <line x1="102" y1="90" x2="118" y2="76"/>
      <line x1="102" y1="90" x2="118" y2="104"/>
    </g>
    <circle cx="48" cy="90" r="14" fill="none" stroke="white" stroke-width="5"/>
    <rect x="78" y="76" width="24" height="28" rx="5" fill="white"/>
    <circle cx="132" cy="76" r="11" fill="white"/>
    <circle cx="132" cy="104" r="11" fill="none" stroke="white" stroke-width="5"/>
  </g>` +
    svgClose(id);
}

function iconTasks() {
  const id = 'tasks';
  return svgOpen(id) +
    bg(id, '#22c55e', '#0f7a3a') +
    `
  <g clip-path="url(#clip-${id})">
    <rect x="38" y="50" width="104" height="80" rx="12" fill="white" fill-opacity="0.95"/>
    <g stroke="#0f7a3a" stroke-width="5" stroke-linecap="round" fill="none">
      <polyline points="52,72 60,80 74,66"/>
      <line x1="84" y1="74" x2="128" y2="74"/>
      <polyline points="52,98 60,106 74,92"/>
      <line x1="84" y1="100" x2="128" y2="100"/>
    </g>
    <line x1="52" y1="120" x2="120" y2="120" stroke="#0f7a3a" stroke-width="5" stroke-linecap="round" stroke-opacity="0.4"/>
  </g>` +
    svgClose(id);
}

function iconContentBrowser() {
  const id = 'content';
  return svgOpen(id) +
    bg(id, '#f59e0b', '#b45309') +
    `
  <g clip-path="url(#clip-${id})">
    <rect x="40" y="58" width="100" height="82" rx="10" fill="white" fill-opacity="0.92"/>
    <rect x="40" y="58" width="56" height="14" rx="6" fill="white" fill-opacity="0.92"/>
    <g fill="#b45309">
      <rect x="52" y="82" width="22" height="22" rx="3"/>
      <rect x="80" y="82" width="22" height="22" rx="3"/>
      <rect x="108" y="82" width="22" height="22" rx="3"/>
      <rect x="52" y="110" width="22" height="22" rx="3" fill-opacity="0.55"/>
      <rect x="80" y="110" width="22" height="22" rx="3" fill-opacity="0.55"/>
      <rect x="108" y="110" width="22" height="22" rx="3" fill-opacity="0.55"/>
    </g>
  </g>` +
    svgClose(id);
}

function iconEipModeler() {
  const id = 'eip-mod';
  return svgOpen(id) +
    bg(id, '#06b6d4', '#155e75') +
    `
  <g clip-path="url(#clip-${id})">
    <path d="M 40 90 H 70 A 12 12 0 0 1 82 102 V 120 A 12 12 0 0 0 94 132 H 110"
          fill="none" stroke="white" stroke-width="7" stroke-linecap="round" stroke-opacity="0.4"/>
    <path d="M 40 90 H 70 A 12 12 0 0 1 82 102 V 120 A 12 12 0 0 0 94 132 H 110"
          fill="none" stroke="white" stroke-width="3" stroke-linecap="round" stroke-dasharray="4 6"/>
    <circle cx="40" cy="90" r="10" fill="white"/>
    <rect x="100" y="122" width="34" height="20" rx="4" fill="white"/>
    <rect x="78" y="48" width="36" height="22" rx="4" fill="white"/>
    <line x1="96" y1="70" x2="96" y2="90" stroke="white" stroke-width="4" stroke-linecap="round"/>
    <circle cx="96" cy="90" r="6" fill="white"/>
  </g>` +
    svgClose(id);
}

function iconEipManager() {
  const id = 'eip-mgr';
  return svgOpen(id) +
    bg(id, '#0ea5e9', '#0c4a6e') +
    `
  <g clip-path="url(#clip-${id})">
    <g stroke="white" stroke-opacity="0.25" stroke-width="2">
      <line x1="40" y1="60" x2="140" y2="60"/>
      <line x1="40" y1="90" x2="140" y2="90"/>
      <line x1="40" y1="120" x2="140" y2="120"/>
    </g>
    <g fill="white" fill-opacity="0.55">
      <rect x="50" y="100" width="12" height="36" rx="2"/>
      <rect x="70" y="80" width="12" height="56" rx="2"/>
      <rect x="90" y="92" width="12" height="44" rx="2"/>
      <rect x="110" y="68" width="12" height="68" rx="2"/>
    </g>
    <polyline points="50,96 76,72 96,86 122,54"
              fill="none" stroke="white" stroke-width="5" stroke-linecap="round" stroke-linejoin="round"/>
    <g fill="white">
      <circle cx="50" cy="96" r="4"/>
      <circle cx="76" cy="72" r="4"/>
      <circle cx="96" cy="86" r="4"/>
      <circle cx="122" cy="54" r="4"/>
    </g>
  </g>` +
    svgClose(id);
}

function iconIdentity() {
  const id = 'identity';
  return svgOpen(id) +
    bg(id, '#a855f7', '#5b21b6') +
    `
  <g clip-path="url(#clip-${id})" fill="white">
    <circle cx="62" cy="74" r="14" fill-opacity="0.7"/>
    <path d="M 36 130 a 26 26 0 0 1 52 0 z" fill-opacity="0.7"/>
    <circle cx="112" cy="68" r="18"/>
    <path d="M 80 138 a 32 32 0 0 1 64 0 z"/>
  </g>` +
    svgClose(id);
}

function iconOsgi() {
  const id = 'osgi';
  return svgOpen(id) +
    bg(id, '#475569', '#0f172a') +
    `
  <g clip-path="url(#clip-${id})">
    <rect x="34" y="46" width="112" height="88" rx="10" fill="#0b1220" stroke="white" stroke-opacity="0.25" stroke-width="1.5"/>
    <line x1="34" y1="64" x2="146" y2="64" stroke="white" stroke-opacity="0.18" stroke-width="1.5"/>
    <g fill="#7dd3fc">
      <circle cx="46" cy="55" r="3"/>
      <circle cx="56" cy="55" r="3" fill="#fbbf24"/>
      <circle cx="66" cy="55" r="3" fill="#34d399"/>
    </g>
    <g stroke="#7dd3fc" stroke-width="3" stroke-linecap="round" fill="none">
      <polyline points="46,80 54,86 46,92"/>
    </g>
    <g fill="white">
      <rect x="60" y="82" width="40" height="6" rx="2" fill-opacity="0.85"/>
      <rect x="46" y="100" width="56" height="6" rx="2" fill-opacity="0.5"/>
      <rect x="46" y="114" width="36" height="6" rx="2" fill-opacity="0.5"/>
    </g>
    <g fill="#7dd3fc" fill-opacity="0.85">
      <rect x="112" y="98" width="22" height="22" rx="3"/>
    </g>
  </g>` +
    svgClose(id);
}

function iconPreferences() {
  const id = 'prefs';
  const teeth = [];
  const cx = 90, cy = 90, rOuter = 50, rInner = 38, w = 14;
  for (let i = 0; i < 8; i++) {
    const a = (i * Math.PI * 2) / 8;
    const x = cx + Math.cos(a) * rOuter - w / 2;
    const y = cy + Math.sin(a) * rOuter - w / 2;
    teeth.push(`    <rect x="${x.toFixed(2)}" y="${y.toFixed(2)}" width="${w}" height="${w}" rx="3" transform="rotate(${(a * 180 / Math.PI).toFixed(2)} ${(x + w / 2).toFixed(2)} ${(y + w / 2).toFixed(2)})"/>`);
  }
  return svgOpen(id) +
    bg(id, '#94a3b8', '#334155') +
    `
  <g clip-path="url(#clip-${id})" fill="white">
${teeth.join('\n')}
    <circle cx="${cx}" cy="${cy}" r="${rInner}"/>
    <circle cx="${cx}" cy="${cy}" r="14" fill="#334155"/>
  </g>` +
    svgClose(id);
}

function iconSchema() {
  const id = 'schema';
  return svgOpen(id) +
    bg(id, '#ec4899', '#9d174d') +
    `
  <g clip-path="url(#clip-${id})">
    <rect x="68" y="42" width="44" height="22" rx="5" fill="white"/>
    <g stroke="white" stroke-width="4" fill="none" stroke-linecap="round">
      <path d="M 90 64 V 78 H 56 V 92"/>
      <path d="M 90 78 V 92"/>
      <path d="M 90 64 V 78 H 124 V 92"/>
    </g>
    <g fill="white">
      <rect x="36" y="92" width="40" height="20" rx="4" fill-opacity="0.85"/>
      <rect x="70" y="92" width="40" height="20" rx="4" fill-opacity="0.85"/>
      <rect x="104" y="92" width="40" height="20" rx="4" fill-opacity="0.85"/>
    </g>
    <g stroke="white" stroke-width="4" fill="none" stroke-linecap="round" stroke-opacity="0.85">
      <path d="M 56 112 V 124 H 90 V 134"/>
    </g>
    <rect x="70" y="134" width="40" height="14" rx="4" fill="white" fill-opacity="0.7"/>
  </g>` +
    svgClose(id);
}

function iconTextEditor() {
  const id = 'text';
  return svgOpen(id) +
    bg(id, '#e2e8f0', '#94a3b8') +
    `
  <g clip-path="url(#clip-${id})">
    <path d="M 50 36 H 116 L 138 58 V 144 H 50 Z" fill="white"/>
    <path d="M 116 36 V 58 H 138" fill="none" stroke="#94a3b8" stroke-width="2"/>
    <g stroke="#475569" stroke-width="4" stroke-linecap="round">
      <line x1="62" y1="74" x2="118" y2="74"/>
      <line x1="62" y1="88" x2="124" y2="88"/>
      <line x1="62" y1="102" x2="110" y2="102"/>
      <line x1="62" y1="116" x2="126" y2="116" stroke-opacity="0.55"/>
      <line x1="62" y1="130" x2="100" y2="130" stroke-opacity="0.55"/>
    </g>
    <g transform="rotate(35 130 132)">
      <rect x="116" y="124" width="34" height="10" rx="2" fill="#f59e0b"/>
      <polygon points="150,124 158,129 150,134" fill="#1f2937"/>
      <rect x="112" y="124" width="6" height="10" fill="#0f172a"/>
    </g>
  </g>` +
    svgClose(id);
}

function iconMemos() {
  const id = 'memos';
  return svgOpen(id) +
    bg(id, '#fde68a', '#d97706') +
    `
  <g clip-path="url(#clip-${id})">
    <path d="M 44 44 H 124 L 142 62 V 142 H 44 Z" fill="white"/>
    <path d="M 124 44 V 62 H 142" fill="none" stroke="#d97706" stroke-width="2"/>
    <g fill="#d97706">
      <rect x="56" y="60" width="14" height="14" rx="2"/>
      <rect x="74" y="60" width="14" height="14" rx="2" fill-opacity="0.7"/>
      <rect x="92" y="60" width="14" height="14" rx="2" fill-opacity="0.5"/>
    </g>
    <g stroke="#92400e" stroke-width="4" stroke-linecap="round">
      <line x1="56" y1="90" x2="128" y2="90"/>
      <line x1="56" y1="104" x2="116" y2="104" stroke-opacity="0.75"/>
      <line x1="56" y1="118" x2="124" y2="118" stroke-opacity="0.6"/>
      <line x1="56" y1="132" x2="100" y2="132" stroke-opacity="0.45"/>
    </g>
  </g>` +
    svgClose(id);
}

function iconWorkspaces() {
  const id = 'workspaces';
  return svgOpen(id) +
    bg(id, '#2dd4bf', '#0f766e') +
    `
  <g clip-path="url(#clip-${id})" fill="white">
    <path d="M 90 38 L 146 66 L 90 94 L 34 66 Z"/>
    <path d="M 46 84 L 34 90 L 90 118 L 146 90 L 134 84 L 90 106 Z" fill-opacity="0.75"/>
    <path d="M 46 108 L 34 114 L 90 142 L 146 114 L 134 108 L 90 130 Z" fill-opacity="0.5"/>
  </g>` +
    svgClose(id);
}

const APP_ICONS = {
  'bpm-manager':     iconBpmManager,
  'bpmn-modeler':    iconBpmnModeler,
  'tasks':           iconTasks,
  'content-browser': iconContentBrowser,
  'eip-modeler':     iconEipModeler,
  'eip-manager':     iconEipManager,
  'identity-manager':iconIdentity,
  'osgi-console':    iconOsgi,
  'preferences':     iconPreferences,
  'schema-manager':  iconSchema,
  'text-editor':     iconTextEditor,
  'memo':            iconMemos,
  'workspace-manager': iconWorkspaces,
};

const REPO_ROOT = path.resolve(__dirname, '..');
const APPS_DIR = path.join(REPO_ROOT, 'src', 'webtop', 'apps');

let written = 0;
let skipped = 0;
for (const [appName, builder] of Object.entries(APP_ICONS)) {
  const target = path.join(APPS_DIR, appName, 'assets', 'icons', 'icon.svg');
  if (!fs.existsSync(path.dirname(target))) {
    console.log(`skip ${appName} (directory not found)`);
    skipped++;
    continue;
  }
  fs.writeFileSync(target, builder(), 'utf8');
  console.log(`wrote ${path.relative(REPO_ROOT, target)}`);
  written++;
}
console.log(`\n${written} written, ${skipped} skipped`);
