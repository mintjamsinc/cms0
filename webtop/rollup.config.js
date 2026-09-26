// rollup.config.js
import resolve from '@rollup/plugin-node-resolve';
import commonjs from '@rollup/plugin-commonjs';
import typescript from 'rollup-plugin-typescript2';
import terser from '@rollup/plugin-terser';
import copy from 'rollup-plugin-copy';
import { transformSync } from 'esbuild';

// Build mode is selected via the BUILD env var.
//   BUILD=development (default) -> unminified output, inline sourcemaps
//   BUILD=production            -> minified JS + minified CSS, external sourcemaps
// Unrecognized values fall back to development with a warning so a typo
// never silently ships a development bundle as production.
const rawMode = process.env.BUILD;
if (rawMode && rawMode !== 'development' && rawMode !== 'production') {
  console.warn(`[rollup] Unknown BUILD=${rawMode}; falling back to "development".`);
}
const isProduction = rawMode === 'production';

// Filter targets via TARGETS env var (comma-separated).
// e.g.  TARGETS=webtop,content-browser npx rollup -c
// When unset, all targets are built.
const targetFilter = process.env.TARGETS
  ? new Set(process.env.TARGETS.split(',').map(s => s.trim()))
  : null;

function include(name) {
  return !targetFilter || targetFilter.has(name);
}

// Minify CSS via esbuild. Used by production builds to overwrite the
// unminified CSS that the asset copy plugin places in dist/.
function minifyCss(contents) {
  return transformSync(contents.toString(), { loader: 'css', minify: true }).code;
}

// Cache-busting version stamp. A single token is computed once per build
// and substituted into both copied HTML files (via the copy plugin's
// transform hook) and emitted JS chunks (via renderChunk below). Filenames
// stay constant so rebuilds never leave orphan files behind; callers append
// "?v=<BUILD_VERSION>" to asset URLs so browsers refetch after each build.
// scripts/build.mjs runs one rollup process per target and passes the stamp
// in BUILD_VERSION so every target of one build shares it; a bare `rollup -c`
// makes its own.
const BUILD_VERSION = process.env.BUILD_VERSION || Date.now().toString(36);
if (!process.env.BUILD_VERSION) console.log(`[rollup] BUILD_VERSION=${BUILD_VERSION}`);

// Replace __BUILD_VERSION__ tokens in copied text assets (HTML).
function stampVersion(contents) {
  return contents.toString().replaceAll('__BUILD_VERSION__', BUILD_VERSION);
}

// Rollup plugin: replace __BUILD_VERSION__ in emitted JS chunks. Runs in
// renderChunk so it executes after terser; terser preserves string
// literals so substituting here keeps the minified output valid.
function versionStampPlugin() {
  return {
    name: 'build-version-stamp',
    renderChunk(code) {
      if (!code.includes('__BUILD_VERSION__')) return null;
      return { code: code.replaceAll('__BUILD_VERSION__', BUILD_VERSION), map: null };
    },
  };
}

// Shared TypeScript plugin options. The plugin only transpiles: type
// checking is done once for the whole of src/ by scripts/build.mjs (tsc
// --noEmit) rather than once per target, which is what made a full build
// slow and, with a program per target held in one process, run out of heap.
// A bare `rollup -c` therefore does not report type errors; run
// `npm run typecheck` or the build script.
function tsPlugin() {
  return typescript({
    tsconfig: './tsconfig.json',
    useTsconfigDeclarationDir: false,
    clean: true,
    check: false,
  });
}

// Build a single rollup config for one target. Dev vs Prod is decided by
// `isProduction`; the rest of the shape (inputs, outputs, asset copies, CSS
// minify targets) is supplied by the caller. Returns null when the target is
// filtered out via TARGETS so the result can be dropped with .filter(Boolean).
function makeConfig({
  name,
  input,
  outputFile,
  outputExtra = {},
  copyTargets = [],
  cssMinifyTargets = [],
}) {
  if (!include(name)) return null;

  const plugins = [
    resolve({ moduleDirectories: ['node_modules'] }),
    commonjs(),
    tsPlugin(),
  ];

  if (isProduction) {
    plugins.push(terser());
  }

  // Must run after terser so the literal __BUILD_VERSION__ in the
  // emitted bundle is replaced with the build version stamp.
  plugins.push(versionStampPlugin());

  // Asset copy always runs so a production-only build is self-contained
  // (previously production relied on the development build having copied
  // static assets first).
  if (copyTargets.length) {
    plugins.push(copy({ targets: copyTargets, hook: 'writeBundle' }));
  }

  // Re-emit CSS with __BUILD_VERSION__ stamped into @import URLs (and
  // minified in production). This ALWAYS runs — not just in production —
  // because the plain assets-directory copy above writes CSS verbatim and
  // never substitutes the version token, so CSS @import cache-busting would
  // otherwise be broken in development and the literal "__BUILD_VERSION__"
  // would ship unsubstituted.
  //   dev  -> stamp only
  //   prod -> stamp, then minify
  // Stamp before minify so the minifier only sees a resolved query string.
  // Must run on closeBundle (not writeBundle): rollup executes writeBundle
  // hooks in parallel, so a recursive asset directory copy can otherwise
  // finish after — and silently clobber — this output. closeBundle is
  // guaranteed to run after all writeBundle hooks complete.
  if (cssMinifyTargets.length) {
    const stampCss = isProduction
      ? (contents) => minifyCss(stampVersion(contents))
      : stampVersion;
    plugins.push(copy({
      targets: cssMinifyTargets.map(t => ({ ...t, transform: stampCss })),
      hook: 'closeBundle',
    }));
  }

  return {
    input,
    output: {
      file: outputFile,
      format: 'esm',
      sourcemap: isProduction ? true : 'inline',
      ...(isProduction ? { sourcemapExcludeSources: false } : {}),
      ...outputExtra,
    },
    plugins,
  };
}

// Standard webtop app config: src/webtop/apps/<name>/app.ts ->
// dist/webtop/apps/<name>/app.js, with index.html/assets/app.yml and the
// app-scoped i18n bundles copied, and any CSS under assets/css/ minified in
// production builds. `extraCopyTargets` adds app-specific runtime files
// (e.g. a library's worker and data files) that cannot be bundled.
function makeAppConfig(name, { extraCopyTargets = [] } = {}) {
  return makeConfig({
    name,
    input: `src/webtop/apps/${name}/app.ts`,
    outputFile: `dist/webtop/apps/${name}/app.js`,
    copyTargets: [
      { src: `src/webtop/apps/${name}/index.html`, dest: `dist/webtop/apps/${name}`, transform: stampVersion },
      { src: `src/webtop/apps/${name}/assets`, dest: `dist/webtop/apps/${name}` },
      { src: `src/webtop/apps/${name}/app.yml`, dest: `dist/webtop/apps/${name}` },
      // App-scoped message bundles (<app>/i18n/<locale>.json) deploy with the
      // app itself; the shell's I18nService discovers them per app folder.
      { src: `src/webtop/apps/${name}/i18n`, dest: `dist/webtop/apps/${name}` },
      ...extraCopyTargets,
    ],
    cssMinifyTargets: [
      {
        src: `src/webtop/apps/${name}/assets/css/*.css`,
        dest: `dist/webtop/apps/${name}/assets/css`,
      },
    ],
  });
}

const webtopCoreConfig = makeConfig({
  name: 'webtop',
  input: 'src/webtop/index.ts',
  outputFile: 'dist/webtop/webtop.js',
  outputExtra: { inlineDynamicImports: true },
  copyTargets: [
    { src: 'src/webtop/index.gsp', dest: 'dist/webtop', transform: stampVersion },
    // Service worker. Must sit at the webtop root so its registration scope
    // covers every app iframe (including the text-editor preview frame).
    { src: 'src/webtop/sw.js', dest: 'dist/webtop' },
    { src: 'src/webtop/assets', dest: 'dist/webtop' },
    { src: 'src/webtop/components/*.html', dest: 'dist/webtop/components' },
    { src: 'src/webtop/components/*.css', dest: 'dist/webtop/components' },
    // wt-* UI framework component templates (loaded at runtime by initUi()).
    { src: 'src/webtop/ui/wt-*.html', dest: 'dist/webtop/ui' },
    // The read-only <eip-canvas> reuses the modeler's node icon sprite. It has a
    // single source (the modeler) and is copied to components/ so any app that
    // mounts the shared canvas can resolve it at ../../components/.
    { src: 'src/webtop/apps/eip-modeler/assets/icons/icons.svg', dest: 'dist/webtop/components', rename: 'eip-canvas-icons.svg' },
    // Bundle Bootstrap Icons (CSS + fonts) so apps can load it locally
    // instead of relying on a CDN. The CSS references fonts via the
    // relative path "fonts/bootstrap-icons.{woff,woff2}", so the resolved
    // layout under dist/webtop/vendor/bootstrap-icons/ mirrors the
    // upstream package directly.
    { src: 'node_modules/bootstrap-icons/font/bootstrap-icons.css', dest: 'dist/webtop/vendor/bootstrap-icons' },
    { src: 'node_modules/bootstrap-icons/font/bootstrap-icons.min.css', dest: 'dist/webtop/vendor/bootstrap-icons' },
    { src: 'node_modules/bootstrap-icons/font/fonts', dest: 'dist/webtop/vendor/bootstrap-icons' },
    { src: 'node_modules/bootstrap-icons/LICENSE', dest: 'dist/webtop/vendor/bootstrap-icons' },
    // Bundle Inter (variable woff2) so deployments no longer need to
    // manually drop fonts under assets/fonts/. style.css and webtop-app.css
    // reference these via ../../vendor/inter/.
    { src: 'node_modules/inter-ui/variable/InterVariable.woff2', dest: 'dist/webtop/vendor/inter' },
    { src: 'node_modules/inter-ui/variable/InterVariable-Italic.woff2', dest: 'dist/webtop/vendor/inter' },
    { src: 'node_modules/inter-ui/LICENSE.txt', dest: 'dist/webtop/vendor/inter' },
    // Bundle Noto Sans JP (variable woff2) for Japanese coverage. Unlike
    // Inter, the upstream package ships ~120 unicode-range subsets, so the
    // @font-face blocks are not inlined into our stylesheets: index.css is
    // copied verbatim and @import-ed by style.css and webtop-app.css. Its
    // font URLs are relative ("./files/..."), so the resolved layout under
    // dist/webtop/vendor/noto-sans-jp/ mirrors the upstream package
    // directly. Browsers download only the subsets a page actually needs.
    { src: 'node_modules/@fontsource-variable/noto-sans-jp/index.css', dest: 'dist/webtop/vendor/noto-sans-jp' },
    { src: 'node_modules/@fontsource-variable/noto-sans-jp/files', dest: 'dist/webtop/vendor/noto-sans-jp' },
    { src: 'node_modules/@fontsource-variable/noto-sans-jp/LICENSE', dest: 'dist/webtop/vendor/noto-sans-jp' },
    // Bundle the default wallpaper from third_party_assets/ so the webtop
    // runtime can resolve /assets/wallpapers/wallpaper-default.jpg without
    // an additional manual upload at deploy time.
    { src: '../third_party_assets/wallpapers/wallpaper-default.jpg', dest: 'dist/webtop/assets/wallpapers' },
  ],
  cssMinifyTargets: [
    { src: 'src/webtop/assets/css/*.css', dest: 'dist/webtop/assets/css' },
    { src: 'src/webtop/components/*.css', dest: 'dist/webtop/components' },
  ],
});

// BPMN-form distribution bundle: ichigo.js + every wt-* component in one
// self-contained ESM. Ships with its
// stylesheet and the component templates so a target that builds only this
// bundle is complete on its own (the webtop core target copies the same
// templates — the duplicate copy is harmless).
const uiStandaloneConfig = makeConfig({
  name: 'webtop-ui-standalone',
  input: 'src/webtop/ui/standalone.ts',
  outputFile: 'dist/webtop/ui/wt-ui.esm.js',
  outputExtra: { inlineDynamicImports: true },
  copyTargets: [
    { src: 'src/webtop/ui/wt-*.html', dest: 'dist/webtop/ui' },
  ],
  cssMinifyTargets: [
    { src: 'src/webtop/ui/wt-ui.css', dest: 'dist/webtop/ui' },
  ],
});

// PDF Viewer: pdf.js runs its parser in a Web Worker and loads CMaps (CJK
// text), standard fonts, ICC profiles and wasm image decoders at runtime, so
// these ship next to the app instead of inside app.js. The worker is renamed
// from .mjs to .js because it is started as a module worker, which requires a
// JavaScript MIME type, and the server maps .js but not .mjs. It must come
// from the same pdfjs-dist version as the bundled library (pdf.js checks).
const PDFJS_DIST = 'node_modules/pdfjs-dist';
const PDFJS_VENDOR = 'dist/webtop/apps/pdf-viewer/vendor/pdfjs';
const pdfViewerConfig = makeAppConfig('pdf-viewer', {
  extraCopyTargets: [
    { src: `${PDFJS_DIST}/build/pdf.worker.min.mjs`, dest: PDFJS_VENDOR, rename: 'pdf.worker.min.js' },
    { src: `${PDFJS_DIST}/cmaps`, dest: PDFJS_VENDOR },
    { src: `${PDFJS_DIST}/standard_fonts`, dest: PDFJS_VENDOR },
    { src: `${PDFJS_DIST}/iccs`, dest: PDFJS_VENDOR },
    { src: `${PDFJS_DIST}/wasm`, dest: PDFJS_VENDOR },
    // Text layer / annotation layer styles; its url(images/...) references
    // resolve against the copied images/ folder beside it.
    { src: `${PDFJS_DIST}/web/pdf_viewer.css`, dest: PDFJS_VENDOR },
    { src: `${PDFJS_DIST}/web/images`, dest: PDFJS_VENDOR },
    { src: `${PDFJS_DIST}/LICENSE`, dest: PDFJS_VENDOR },
  ],
});

const mailConfig = makeAppConfig('mail', {
  extraCopyTargets: [
    { src: 'src/webtop/apps/mail/attachment.groovy', dest: 'dist/webtop/apps/mail' },
  ],
});

// Radio: two scripts run on the server next to the app and are copied with
// the static files. icy.groovy reads the ICY metadata a browser cannot see;
// stream.groovy relays a plain-http station to an https desktop.
const radioConfig = makeAppConfig('radio', {
  extraCopyTargets: [
    { src: 'src/webtop/apps/radio/icy.groovy', dest: 'dist/webtop/apps/radio' },
    { src: 'src/webtop/apps/radio/stream.groovy', dest: 'dist/webtop/apps/radio' },
  ],
});

// Every target by name, in build order. scripts/build.mjs reads this list to
// run one rollup process per target, so a target added here is picked up by
// `npm run build` without a second list to keep in sync. Targets with extra
// copy steps are built above; the rest are plain makeAppConfig targets.
const specialConfigs = {
  'webtop': webtopCoreConfig,
  'webtop-ui-standalone': uiStandaloneConfig,
  'pdf-viewer': pdfViewerConfig,
  'radio': radioConfig,
  'mail': mailConfig,
};
export const TARGET_NAMES = [
  'webtop',
  'webtop-ui-standalone',
  'content-browser',
  'memo',
  'text-editor',
  'text-editor-preview',
  'pdf-viewer',
  'bpmn-modeler',
  'eip-modeler',
  'schema-manager',
  'identity-manager',
  'preferences',
  'bpm-console',
  'eip-console',
  'tasks',
  'osgi-console',
  'dashboard',
  'workspace-manager',
  'radio',
  'mail',
];

if (targetFilter) {
  for (const name of targetFilter) {
    if (!TARGET_NAMES.includes(name)) console.warn(`[rollup] Unknown target in TARGETS: ${name}`);
  }
}

export default TARGET_NAMES
  .map(name => (name in specialConfigs ? specialConfigs[name] : makeAppConfig(name)))
  .filter(Boolean);
