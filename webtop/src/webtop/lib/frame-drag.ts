// Drag regions inside a same-origin app iframe.
//
// Any element with `.window-drag-region` inside the frame's document acts as
// a drag handle for whatever hosts the frame (a window, a desktop widget).
// Clicks on interactive children (button, input, etc.) are excluded.
//
// mousemove/mouseup are bound on BOTH the iframe document and the parent
// document because mouse-capture stays within whichever document received the
// original mousedown. While dragging, <html> of the frame carries
// `wt-window-dragging` (see webtop-app.css), which disables pointer events on
// nested iframes so the drag cannot stall when the cursor crosses one.

export interface FrameDragHandlers {
	/** Whether a drag may start now (e.g. not while the window is maximized). */
	canStart(): boolean;
	/** The drag has started. */
	onStart(): void;
	/** The cursor moved; dx/dy are page pixels from where the drag started. */
	onMove(dx: number, dy: number): void;
	/** The drag ended. */
	onEnd(): void;
	/** A drag region was double-clicked. */
	onDoubleClick?(): void;
}

function isInteractiveTarget(el: HTMLElement | null): boolean {
	return !!el && !!el.closest('button, a, input, textarea, select, [contenteditable="true"], [role="button"], [role="textbox"]');
}

/**
 * Attach drag-region handling to the frame's current document. Returns the
 * teardown. Does nothing (and returns a no-op) for a cross-origin frame.
 */
export function attachFrameDragRegion(frame: HTMLIFrameElement, handlers: FrameDragHandlers): () => void {
	let doc: Document | null = null;
	try {
		doc = frame.contentDocument;
	} catch (_) { /* cross-origin */ }
	if (!doc) return () => { /* nothing attached */ };

	const onMouseDown = (e: MouseEvent) => {
		if (e.button !== 0) return;
		const target = e.target as HTMLElement;
		if (!target.closest('.window-drag-region')) return;
		if (isInteractiveTarget(target)) return;
		if (e.detail > 1) return;
		if (!handlers.canStart()) return;

		handlers.onStart();
		// preventDefault below would block focus from transferring to the
		// iframe, which would silently break in-iframe keyboard shortcuts
		// (Delete, Escape, Ctrl+I, ...). Force focus first.
		try { frame.contentWindow?.focus(); } catch (_) { /* cross-origin */ }
		try { frame.contentDocument?.documentElement.classList.add('wt-window-dragging'); } catch (_) { /* cross-origin */ }
		const rect0 = frame.getBoundingClientRect();
		const startPageX = e.clientX + rect0.left;
		const startPageY = e.clientY + rect0.top;

		const updateFromIframe = (ev: MouseEvent) => {
			const r = frame.getBoundingClientRect();
			handlers.onMove(ev.clientX + r.left - startPageX, ev.clientY + r.top - startPageY);
		};
		const updateFromParent = (ev: MouseEvent) => {
			handlers.onMove(ev.clientX - startPageX, ev.clientY - startPageY);
		};
		const cleanup = () => {
			try {
				frame.contentDocument?.removeEventListener('mousemove', updateFromIframe, true);
				frame.contentDocument?.removeEventListener('mouseup', cleanup, true);
				frame.contentDocument?.documentElement.classList.remove('wt-window-dragging');
			} catch (_) { /* cross-origin */ }
			document.removeEventListener('mousemove', updateFromParent, true);
			document.removeEventListener('mouseup', cleanup, true);
			handlers.onEnd();
		};
		try {
			frame.contentDocument?.addEventListener('mousemove', updateFromIframe, true);
			frame.contentDocument?.addEventListener('mouseup', cleanup, true);
		} catch (_) { /* cross-origin */ }
		document.addEventListener('mousemove', updateFromParent, true);
		document.addEventListener('mouseup', cleanup, true);
		e.preventDefault();
	};
	const onDblClick = (e: MouseEvent) => {
		if (!handlers.onDoubleClick) return;
		const target = e.target as HTMLElement;
		if (!target.closest('.window-drag-region')) return;
		if (isInteractiveTarget(target)) return;
		handlers.onDoubleClick();
	};

	doc.addEventListener('mousedown', onMouseDown, true);
	doc.addEventListener('dblclick', onDblClick, true);
	const attachedDoc = doc;
	return () => {
		try {
			attachedDoc.removeEventListener('mousedown', onMouseDown, true);
			attachedDoc.removeEventListener('dblclick', onDblClick, true);
		} catch (_) { /* cross-origin */ }
	};
}
