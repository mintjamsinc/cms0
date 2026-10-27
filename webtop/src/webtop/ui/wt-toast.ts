// <wt-toast> — a short note at the bottom of the window that goes away by
// itself: the completion of something the screen does not show on its own (a
// changed password, a saved search, a file written elsewhere).
//
//     <wt-toast v-model:message="toast"></wt-toast>
//
//     this.toast = { text: t('msg.saved') };
//     this.toast = { text: t('msg.saved'), link: { href: url, label: t('open') }, duration: 8000 };
//
// Each new message object starts the note over; the component clears the
// message (update:message → null) when its time is up or the close button is
// pressed. A note with a link can be clicked and gets a close button; a plain
// one lets clicks through to what is under it. Errors belong inline next to
// what failed (<wt-inline-message>), not here.

import { defineComponent } from '@mintjamsinc/ichigojs';

export interface WtToastMessage {
	text: string;
	/** success (default) / info / warning / error: the icon. */
	type?: 'success' | 'info' | 'warning' | 'error';
	/** A link after the text, e.g. to the saved file at the provider. */
	link?: { href: string; label: string } | null;
	/** How long it stays, in ms; the component's `duration` otherwise. */
	duration?: number;
}

const TYPE_ICONS: Record<string, string> = {
	info: 'bi-info-circle',
	success: 'bi-check-circle',
	warning: 'bi-exclamation-triangle',
	error: 'bi-x-circle',
};

defineComponent('wt-toast', {
	template: '#wt-toast',
	props: {
		/** The note shown (v-model:message); null when none. */
		message: { type: Object, default: null },
		/** How long a note stays, in ms, when the message does not say. */
		duration: { type: Number, default: 2500 },
	},
	emits: ['update:message'],
	data() {
		return {
			_timer: null as ReturnType<typeof setTimeout> | null,
		};
	},
	computed: {
		toastClass(this: any): string {
			const m: WtToastMessage | null = this.message;
			return m ? 'wt-' + (m.type || 'success') + (m.link ? ' wt-interactive' : '') : '';
		},
		iconClass(this: any): string {
			return TYPE_ICONS[this.message?.type] ?? TYPE_ICONS.success;
		},
	},
	watch: {
		message(this: any, value: WtToastMessage | null): void {
			this.stopTimer();
			if (value) {
				this._timer = setTimeout(() => {
					this._timer = null;
					this.dismiss();
				}, value.duration || this.duration);
			}
		},
	},
	methods: {
		onUnmount(this: any): void {
			this.stopTimer();
		},
		stopTimer(this: any): void {
			if (this._timer) {
				clearTimeout(this._timer);
				this._timer = null;
			}
		},
		dismiss(this: any): void {
			this.stopTimer();
			this.$emit('update:message', null);
		},
	},
});
