// <wt-splitter> — drag handle between panes, driving a size binding.
//
//     <div class="sidebar-panel" :style="{ width: sidebarWidth + 'px' }">...</div>
//     <wt-splitter v-model:size="sidebarWidth" :min="160" :max="600"></wt-splitter>
//     <div class="content-main">...</div>
//
//     <!-- pane on the right/bottom side of the handle: reverse the drag -->
//     <wt-splitter v-model:size="detailWidth" reverse :min="200"></wt-splitter>
//     <div class="detail-panel" :style="{ width: detailWidth + 'px' }">...</div>
//
// direction: col (vertical handle, resizes a width) / row (horizontal
// handle, resizes a height). Replaces the eleven identical resize-handle
// CSS copies and the per-app drag logic.

import { defineComponent } from '@mintjamsinc/ichigojs';

defineComponent('wt-splitter', {
	template: '#wt-splitter',
	props: {
		/** Size of the controlled pane in px (v-model:size). */
		size: { type: Number, default: 0 },
		/** col = vertical handle (width) / row = horizontal handle (height). */
		direction: { type: String, default: 'col', validator: (v: any) => v === 'col' || v === 'row' },
		/** Invert the drag: the controlled pane sits after (right/below) the handle. */
		reverse: { type: Boolean, default: false },
		/** Minimum size in px (0 = no limit). */
		min: { type: Number, default: 0 },
		/** Maximum size in px (0 = no limit). */
		max: { type: Number, default: 0 },
	},
	emits: ['resize-start', 'resize-end'],
	data() {
		const self = this as any;
		return {
			resizing: false,
			_: self.$markRaw({ startPos: 0, startSize: 0, onMove: undefined, onUp: undefined }),
		};
	},
	methods: {
		onDown(this: any, event: MouseEvent): void {
			event.preventDefault();
			const horizontal = this.direction !== 'row';
			this._.startPos = horizontal ? event.clientX : event.clientY;
			this._.startSize = Number(this.size) || 0;

			this._.onMove = (ev: MouseEvent) => {
				const pos = horizontal ? ev.clientX : ev.clientY;
				let delta = pos - this._.startPos;
				if (this.reverse) {
					delta = -delta;
				}
				let next = this._.startSize + delta;
				if (this.min > 0) {
					next = Math.max(this.min, next);
				}
				if (this.max > 0) {
					next = Math.min(this.max, next);
				}
				this.$emit('update:size', Math.round(next));
			};
			this._.onUp = () => {
				document.removeEventListener('mousemove', this._.onMove);
				document.removeEventListener('mouseup', this._.onUp);
				this.resizing = false;
				this.$emit('resize-end');
			};

			document.addEventListener('mousemove', this._.onMove);
			document.addEventListener('mouseup', this._.onUp);
			this.resizing = true;
			this.$emit('resize-start');
		},
	},
});
