// hls.js ships its light build (no subtitles, alternate audio, EME or CMCD —
// none of which a radio stream needs) as "hls.js/light" without a type
// declaration of its own. The light build has the same API, so reuse the
// full build's types.
declare module 'hls.js/light' {
	export * from 'hls.js';
	export { default } from 'hls.js';
}
