// <wt-dropzone> — dashed drag & drop target with a built-in highlight state.
//
//     <wt-dropzone drop-effect="copy" @dropped="onScanDrop($event.detail)">
//         <i class="bi bi-file-earmark-arrow-down"></i>
//         <span>{{ t('scan.dropText') }}</span>
//     </wt-dropzone>
//
// The component owns the dragover / dragleave visual state (the per-app
// `dropHighlight` flags disappear). On drop it emits 'dropped' with the
// original DragEvent as the detail — NOT 'drop', which would collide with
// the native drop event bubbling out of the zone. Payload handling stays
// host work. Slot content is arbitrary; the scope exposes `highlighted`.

import { defineComponent } from '@mintjamsinc/ichigojs';

defineComponent('wt-dropzone', {
	template: '#wt-dropzone',
	props: {
		/** dataTransfer.dropEffect to advertise while dragging over ('' = leave as is). */
		dropEffect: { type: String, default: '' },
	},
	emits: ['dropped'],
	data() {
		return { highlighted: false };
	},
	methods: {
		onDragOver(this: any, event: DragEvent): void {
			if (this.dropEffect && event.dataTransfer) {
				event.dataTransfer.dropEffect = this.dropEffect;
			}
			this.highlighted = true;
		},
		onDrop(this: any, event: DragEvent): void {
			this.highlighted = false;
			this.$emit('dropped', event);
		},
	},
});
