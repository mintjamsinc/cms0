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

// Cache-busting version stamp. A single token is computed once per rollup
// invocation and substituted into both copied HTML files (via the copy
// plugin's transform hook) and emitted JS chunks (via renderChunk below).
// Filenames stay constant so rebuilds never leave orphan files behind;
// callers append "?v=<BUILD_VERSION>" to asset URLs so browsers refetch
// after each build.
const BUILD_VERSION = Date.now().toString(36);
console.log(`[rollup] BUILD_VERSION=${BUILD_VERSION}`);

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

// Shared TypeScript plugin options.
function tsPlugin() {
  return typescript({
    tsconfig: './tsconfig.json',
    useTsconfigDeclarationDir: false,
    clean: true,
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
// dist/webtop/apps/<name>/app.js, with index.html/assets/app.yml copied and
// any CSS under assets/css/ minified in production builds.
function makeAppConfig(name) {
  return makeConfig({
    name,
    input: `src/webtop/apps/${name}/app.ts`,
    outputFile: `dist/webtop/apps/${name}/app.js`,
    copyTargets: [
      { src: `src/webtop/apps/${name}/index.html`, dest: `dist/webtop/apps/${name}`, transform: stampVersion },
      { src: `src/webtop/apps/${name}/assets`, dest: `dist/webtop/apps/${name}` },
      { src: `src/webtop/apps/${name}/app.yml`, dest: `dist/webtop/apps/${name}` },
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
    { src: 'src/webtop/assets', dest: 'dist/webtop' },
    { src: 'src/webtop/components/*.html', dest: 'dist/webtop/components' },
    { src: 'src/webtop/components/*.css', dest: 'dist/webtop/components' },
    // Bundle Bootstrap Icons (CSS + fonts) so apps can load it locally
    // instead of relying on a CDN. The CSS references fonts via the
    // relative path "fonts/bootstrap-icons.{woff,woff2}", so the resolved
    // layout under dist/webtop/assets/vendor/bootstrap-icons/ mirrors the
    // upstream package directly.
    { src: 'node_modules/bootstrap-icons/font/bootstrap-icons.css', dest: 'dist/webtop/assets/vendor/bootstrap-icons' },
    { src: 'node_modules/bootstrap-icons/font/bootstrap-icons.min.css', dest: 'dist/webtop/assets/vendor/bootstrap-icons' },
    { src: 'node_modules/bootstrap-icons/font/fonts', dest: 'dist/webtop/assets/vendor/bootstrap-icons' },
    { src: 'node_modules/bootstrap-icons/LICENSE', dest: 'dist/webtop/assets/vendor/bootstrap-icons' },
    // Bundle Inter (variable woff2) so deployments no longer need to
    // manually drop fonts under assets/fonts/. style.css and webtop-app.css
    // reference these via ../vendor/inter/.
    { src: 'node_modules/inter-ui/variable/InterVariable.woff2', dest: 'dist/webtop/assets/vendor/inter' },
    { src: 'node_modules/inter-ui/variable/InterVariable-Italic.woff2', dest: 'dist/webtop/assets/vendor/inter' },
    { src: 'node_modules/inter-ui/LICENSE.txt', dest: 'dist/webtop/assets/vendor/inter' },
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

export default [
  webtopCoreConfig,
  makeAppConfig('content-browser'),
  makeAppConfig('text-editor'),
  makeAppConfig('text-editor-preview'),
  makeAppConfig('bpmn-modeler'),
  makeAppConfig('eip-modeler'),
  makeAppConfig('schema-manager'),
  makeAppConfig('identity-manager'),
  makeAppConfig('preferences'),
  makeAppConfig('bpm-console'),
  makeAppConfig('eip-console'),
  makeAppConfig('tasks'),
  makeAppConfig('osgi-console'),
  makeAppConfig('dashboard'),
].filter(Boolean);
