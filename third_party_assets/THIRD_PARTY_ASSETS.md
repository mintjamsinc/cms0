# Third Party Assets

> For licenses of third-party Java libraries shipped under `bundles/`,
> see [`bundles/THIRD_PARTY_LICENSES.md`](../bundles/THIRD_PARTY_LICENSES.md).

## Inter Font

- Bundled from the [inter-ui](https://www.npmjs.com/package/inter-ui) npm
  package (pinned in `webtop/package.json`), which mirrors the upstream
  [rsms/inter](https://github.com/rsms/inter) v4.1 release. The variable
  woff2 files and the upstream `LICENSE.txt` are copied into
  `webtop/dist/webtop/assets/vendor/inter/` at build time by
  `webtop/rollup.config.js`.
- Licensed under the SIL Open Font License 1.1.

## Bootstrap Icons

- Bundled from the [bootstrap-icons](https://icons.getbootstrap.com/) npm
  package (pinned in `webtop/package.json`) and copied into
  `webtop/dist/webtop/assets/vendor/bootstrap-icons/` at build time by
  `webtop/rollup.config.js`. The package's `LICENSE` file is copied alongside
  the bundled assets.
- Licensed under the MIT License.

## Default Wallpaper

- File: `third_party_assets/wallpapers/wallpaper-default.jpg`
- Created by: cms0 project (2026-05-13)
- Generated using Magnific (formerly Freepik) AI tools and further edited
  in Adobe Photoshop. The resulting JPEG is the work product committed to
  this repository.
- Bundled into `webtop/dist/webtop/assets/wallpapers/wallpaper-default.jpg`
  at build time by `webtop/rollup.config.js`, where the webtop runtime
  resolves it as `/assets/wallpapers/wallpaper-default.jpg`.
- Distributed separately from the software source code license.
