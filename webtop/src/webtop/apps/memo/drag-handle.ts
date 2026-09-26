// Block drag handle for the Memo editor.
//
// A grip that appears in the left gutter next to the top-level block under
// the pointer (paragraph, heading, list, table, dataset block, …). Dragging it
// moves that block: the handle selects the block as a NodeSelection and hands
// ProseMirror the selection's slice as `view.dragging`, so the drop is the
// editor's own move (the dropcursor from StarterKit shows where it lands, and
// the original is removed once dropped). A click on the grip only selects the
// block.
//
// The handle lives outside the editor's DOM (in the tab's mount, which is
// positioned) so ProseMirror never sees it as content; it is positioned from
// the block's box on every mouse move and hidden as soon as the document
// changes, since a stale position would point at the wrong block.
import { Extension } from '@tiptap/core';
import { Plugin, PluginKey, NodeSelection } from '@tiptap/pm/state';
import { DOMSerializer } from '@tiptap/pm/model';
import type { EditorView } from '@tiptap/pm/view';

export const DRAG_HANDLE_EXTENSION = 'memoDragHandle';
// The gutter between the handle and the block's left edge.
const HANDLE_GAP = 4;

export interface DragHandleHost {
	title(): string;
}

export function createDragHandleExtension(host: DragHandleHost) {
	return Extension.create({
		name: DRAG_HANDLE_EXTENSION,
		addProseMirrorPlugins() {
			return [new Plugin({
				key: new PluginKey(DRAG_HANDLE_EXTENSION),
				view: (view) => new DragHandleView(view, host),
			})];
		},
	});
}

class DragHandleView {
	private readonly handle: HTMLElement;
	private readonly mount: HTMLElement | null;
	// Position of the block the handle stands next to; null while hidden.
	private blockPos: number | null = null;
	private dragging = false;

	private readonly onMove = (e: MouseEvent) => this.follow(e);
	private readonly onLeave = () => { if (!this.dragging) this.hide(); };
	private readonly onDragStart = (e: DragEvent) => this.startDrag(e);
	private readonly onDragEnd = () => { this.dragging = false; this.handle.classList.remove('is-dragging'); this.hide(); };
	private readonly onClick = () => this.selectBlock();

	constructor(private readonly view: EditorView, host: DragHandleHost) {
		this.mount = view.dom.parentElement;
		this.handle = document.createElement('div');
		this.handle.className = 'memo-drag-handle';
		this.handle.draggable = true;
		this.handle.title = host.title();
		const grip = document.createElement('i');
		grip.className = 'bi bi-grip-vertical';
		this.handle.appendChild(grip);
		if (this.mount) {
			this.mount.appendChild(this.handle);
			this.mount.addEventListener('mousemove', this.onMove);
			this.mount.addEventListener('mouseleave', this.onLeave);
		}
		this.handle.addEventListener('dragstart', this.onDragStart);
		this.handle.addEventListener('dragend', this.onDragEnd);
		this.handle.addEventListener('click', this.onClick);
	}

	// ProseMirror plugin view contract: a document change may have moved or
	// removed the block, so the handle steps back until the next mouse move.
	update(_view: EditorView, prevState: any): void {
		if (this.dragging) return;
		if (this.blockPos != null && !this.view.state.doc.eq(prevState.doc)) this.hide();
	}

	destroy(): void {
		if (this.mount) {
			this.mount.removeEventListener('mousemove', this.onMove);
			this.mount.removeEventListener('mouseleave', this.onLeave);
		}
		this.handle.remove();
	}

	private hide(): void {
		this.blockPos = null;
		this.handle.classList.remove('is-visible');
	}

	// Stand next to the top-level block under the pointer.
	private follow(e: MouseEvent): void {
		if (this.dragging || !this.mount) return;
		if (this.handle.contains(e.target as Node | null)) return;
		const pos = this.blockAt(e.clientX, e.clientY);
		if (pos == null) { this.hide(); return; }
		const dom = this.view.nodeDOM(pos);
		if (!(dom instanceof HTMLElement)) { this.hide(); return; }
		const rect = dom.getBoundingClientRect();
		const mountRect = this.mount.getBoundingClientRect();
		this.blockPos = pos;
		this.handle.style.top = `${rect.top - mountRect.top}px`;
		this.handle.style.left = `${rect.left - mountRect.left - this.handle.offsetWidth - HANDLE_GAP}px`;
		this.handle.classList.add('is-visible');
	}

	// The position of the top-level block at the given point, or null.
	private blockAt(left: number, top: number): number | null {
		const view = this.view;
		let found: { pos: number; inside: number } | null;
		try { found = view.posAtCoords({ left, top }); } catch { return null; }
		if (!found) return null;
		const doc = view.state.doc;
		const pos = found.inside >= 0 ? found.inside : found.pos;
		if (pos < 0 || pos > doc.content.size) return null;
		const $pos = doc.resolve(pos);
		if ($pos.depth === 0) {
			// Between top-level blocks, or right before one (an atom such as the
			// dataset block): the block that follows.
			return $pos.nodeAfter ? pos : null;
		}
		return $pos.before(1);
	}

	private selectBlock(): void {
		const pos = this.blockPos;
		if (pos == null) return;
		const view = this.view;
		if (!view.state.doc.nodeAt(pos)) return;
		view.dispatch(view.state.tr.setSelection(NodeSelection.create(view.state.doc, pos)));
		view.focus();
	}

	// Start moving the block: select it and give ProseMirror the slice, the
	// way its own drag of a selection does. The clipboard data is for targets
	// outside the editor; the editor itself drops `view.dragging`.
	private startDrag(e: DragEvent): void {
		const pos = this.blockPos;
		const view = this.view;
		if (pos == null || !e.dataTransfer || !view.state.doc.nodeAt(pos)) { e.preventDefault(); return; }
		const selection = NodeSelection.create(view.state.doc, pos);
		const slice = selection.content();
		const serializer = DOMSerializer.fromSchema(view.state.schema);
		const wrapper = document.createElement('div');
		wrapper.appendChild(serializer.serializeFragment(slice.content));
		e.dataTransfer.clearData();
		e.dataTransfer.setData('text/html', wrapper.innerHTML);
		e.dataTransfer.setData('text/plain', slice.content.textBetween(0, slice.content.size, '\n\n'));
		e.dataTransfer.effectAllowed = 'copyMove';
		const dom = view.nodeDOM(pos);
		if (dom instanceof HTMLElement) e.dataTransfer.setDragImage(dom, 0, 0);
		view.dragging = { slice, move: true };
		view.dispatch(view.state.tr.setSelection(selection));
		this.dragging = true;
		this.handle.classList.add('is-dragging');
	}
}
