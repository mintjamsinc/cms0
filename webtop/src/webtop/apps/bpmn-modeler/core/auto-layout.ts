// =============================================================================
// Auto Layout for Missing Diagram Interchange
// =============================================================================
// A BPMN file may carry its process without <bpmndi:BPMNShape>/<BPMNEdge> for
// some or all of its elements (files written by hand, by a script or by an AI
// assistant). Only elements with a shape are drawn, so such elements would be
// invisible and could not be reached to fix by hand. This pass gives every
// such element a position; elements that already have a shape keep theirs.
//
// - A container (the process, a Pool, a Sub-Process) none of whose flow nodes
//   has a shape is laid out as a whole: flow nodes in layers left to right
//   along the sequence flows, one band per Lane, boundary events on their
//   host's bottom edge, data and annotations next to the element they are
//   associated with. Pools and Sub-Processes are sized around their content.
// - An element missing among placed ones is put next to a placed neighbour.
// - A flow without an edge gets one without waypoints, which the renderer and
//   the serializer route automatically.
// =============================================================================

import {
	BpmnModelStore,
	type BpmnSemantic,
	type Bounds,
	generateUUID,
} from './bpmn-model-types.js';
import { ELEMENT_SIZES } from './element-config.js';

const H_GAP = 60;
const V_GAP = 50;
const PAD = 40;
const POOL_HEADER = 30;
const SUBPROCESS_HEADER = 20;
const ARTIFACT_GAP = 30;
const CONTAINER_GAP = 80;
const ORIGIN = { x: 100, y: 100 };
const BOUNDARY_SIZE = 36;

const ARTIFACT_TYPES = new Set(['dataObject', 'dataStore', 'textAnnotation']);

interface Size {
	width: number;
	height: number;
}

interface Placement {
	bounds: Bounds;
	isExpanded?: boolean;
}

/**
 * Relative layout of one container's content. Item coordinates are relative
 * to the content's top-left corner; a nested expanded Sub-Process carries its
 * own content, relative to the Sub-Process interior.
 */
interface Content {
	items: Map<string, Placement>;
	nested: Map<string, Content>;
	width: number;
	height: number;
	bandTops: number[];
	bandSpans: number[];
}

const isFlowNode = (s: BpmnSemantic): boolean =>
	s.type !== 'pool' && s.type !== 'lane' && s.type !== 'boundaryEvent' && !ARTIFACT_TYPES.has(s.type);

const overlaps = (a: Bounds, b: Bounds): boolean =>
	a.x < b.x + b.width && b.x < a.x + a.width && a.y < b.y + b.height && b.y < a.y + a.height;

/**
 * Give every semantic element without a shape, and every flow without an
 * edge, a diagram representation.
 *
 * @returns the number of shapes and edges added
 */
export function layoutMissingDi(store: BpmnModelStore): number {
	const semantics = store.getAllSemantics();
	const byId = new Map(semantics.map(s => [s.id, s]));
	const flows = store.getAllFlows();

	// Current bounds of every element, existing and newly placed.
	const bounds = new Map<string, Bounds>();
	semantics.forEach(s => {
		const shape = store.getShapesForSemantic(s.id)[0];
		if (shape) bounds.set(s.id, { ...shape.bounds });
	});
	const missing = new Set(semantics.filter(s => !bounds.has(s.id)).map(s => s.id));
	const added = new Map<string, Placement>();
	const grown = new Set<string>();

	const place = (id: string, b: Bounds, isExpanded?: boolean): void => {
		bounds.set(id, b);
		missing.delete(id);
		added.set(id, { bounds: b, isExpanded });
	};
	const resize = (id: string, b: Bounds): void => {
		bounds.set(id, b);
		const placement = added.get(id);
		if (placement) placement.bounds = b;
		else grown.add(id);
	};

	const childrenOf = (containerId: string | undefined): BpmnSemantic[] =>
		semantics.filter(s => s.type !== 'pool' && s.type !== 'lane' && (s.parentId || undefined) === containerId);
	const lanesOf = (poolId: string): BpmnSemantic[] =>
		semantics.filter(s => s.type === 'lane' && s.parentPoolId === poolId);
	const hasFlowChildren = (id: string): boolean => childrenOf(id).some(isFlowNode);
	// A container is laid out as a whole when none of its flow nodes is placed.
	const isFresh = (containerId: string | undefined): boolean => {
		const nodes = childrenOf(containerId).filter(isFlowNode);
		return nodes.length > 0 && nodes.every(n => missing.has(n.id));
	};

	const sequenceFlows = flows.filter(f => !f.connectionType || f.connectionType === 'sequenceFlow');
	const artifactFlows = flows.filter(f => f.connectionType === 'association' || f.connectionType === 'dataAssociation');
	// Flows out of a boundary event lay out as flows out of its host.
	const flowSource = (id: string): string => {
		const s = byId.get(id);
		return s?.type === 'boundaryEvent' && s.attachedToRef ? s.attachedToRef : id;
	};
	const associatedWith = (artifactId: string): string[] => artifactFlows
		.filter(f => f.sourceRef === artifactId || f.targetRef === artifactId)
		.map(f => f.sourceRef === artifactId ? f.targetRef : f.sourceRef)
		.filter(id => id !== artifactId && byId.has(id));

	const sizeOf = (s: BpmnSemantic): Size => {
		if (s.type === 'subProcess') return ELEMENT_SIZES.subProcess;
		return ELEMENT_SIZES[s.type] || { width: 100, height: 80 };
	};

	// =========================================================================
	// Whole-container layout
	// =========================================================================

	const layoutContent = (containerId: string | undefined, lanes: BpmnSemantic[]): Content => {
		const children = childrenOf(containerId);
		const nodes = children.filter(s => isFlowNode(s) && missing.has(s.id));
		const ids = nodes.map(n => n.id);
		const idSet = new Set(ids);
		const laneMode = lanes.length > 0;
		const bandCount = laneMode ? lanes.length : 1;

		// Sizes, laying out expanded Sub-Processes first.
		const sizes = new Map<string, Size>();
		const nested = new Map<string, Content>();
		nodes.forEach(n => {
			if (n.type === 'subProcess' && isFresh(n.id)) {
				const inner = layoutContent(n.id, []);
				nested.set(n.id, inner);
				sizes.set(n.id, {
					width: Math.max(inner.width + PAD * 2, ELEMENT_SIZES.subProcess.width),
					height: Math.max(inner.height + PAD * 2 + SUBPROCESS_HEADER, ELEMENT_SIZES.subProcess.height),
				});
			} else {
				sizes.set(n.id, sizeOf(n));
			}
		});

		const bandOf = new Map<string, number>();
		ids.forEach(id => {
			const lane = lanes.findIndex(l => l.flowNodeRefs?.includes(id));
			bandOf.set(id, Math.max(0, lane));
		});

		// Graph over the container's nodes.
		const succ = new Map<string, string[]>(ids.map(id => [id, []]));
		// Targets reached only through a boundary event: the exception path,
		// which goes below the main path.
		const viaBoundary = new Set<string>();
		const viaNormal = new Set<string>();
		sequenceFlows.forEach(f => {
			const source = flowSource(f.sourceRef);
			if (idSet.has(source) && idSet.has(f.targetRef) && source !== f.targetRef) {
				succ.get(source)!.push(f.targetRef);
				(source === f.sourceRef ? viaNormal : viaBoundary).add(f.targetRef);
			}
		});
		viaNormal.forEach(id => viaBoundary.delete(id));
		const hasPred = new Set<string>();
		succ.forEach(targets => targets.forEach(t => hasPred.add(t)));

		// Depth-first from the start events; edges back into the current path
		// close a loop and do not count for layering.
		const order = [
			...nodes.filter(n => n.type === 'startEvent' && !hasPred.has(n.id)).map(n => n.id),
			...ids.filter(id => !hasPred.has(id)),
			...ids,
		];
		const state = new Map<string, number>();
		const dagSucc = new Map<string, string[]>(ids.map(id => [id, []]));
		const dagPred = new Map<string, string[]>(ids.map(id => [id, []]));
		const postOrder: string[] = [];
		const preIndex = new Map<string, number>();
		const visit = (u: string): void => {
			state.set(u, 1);
			preIndex.set(u, preIndex.size);
			succ.get(u)!.forEach(v => {
				if (state.get(v) === 1) return;
				dagSucc.get(u)!.push(v);
				dagPred.get(v)!.push(u);
				if (!state.has(v)) visit(v);
			});
			state.set(u, 2);
			postOrder.push(u);
		};
		order.forEach(id => {
			if (!state.has(id)) visit(id);
		});
		const topo = postOrder.reverse();

		// Longest-path layering.
		const layer = new Map<string, number>(ids.map(id => [id, 0]));
		topo.forEach(u => dagSucc.get(u)!.forEach(v => {
			layer.set(v, Math.max(layer.get(v)!, layer.get(u)! + 1));
		}));
		const layerCount = ids.length > 0 ? Math.max(...ids.map(id => layer.get(id)!)) + 1 : 0;

		// Rows within each band: a node follows its predecessors' rows.
		const row = new Map<string, number>();
		for (let l = 0; l < layerCount; l++) {
			const inLayer = topo.filter(id => layer.get(id) === l).map(id => {
				const preds = dagPred.get(id)!.filter(p => bandOf.get(p) === bandOf.get(id) && row.has(p));
				const desired = preds.length > 0
					? Math.floor(preds.reduce((sum, p) => sum + row.get(p)!, 0) / preds.length)
					: undefined;
				return { id, desired };
			});
			inLayer.sort((a, b) => {
				if (a.desired !== undefined && b.desired !== undefined && a.desired !== b.desired) return a.desired - b.desired;
				if (a.desired === undefined && b.desired !== undefined) return 1;
				if (a.desired !== undefined && b.desired === undefined) return -1;
				const boundaryOrder = Number(viaBoundary.has(a.id)) - Number(viaBoundary.has(b.id));
				if (boundaryOrder !== 0) return boundaryOrder;
				// Siblings keep the order of their flows in the file.
				return preIndex.get(a.id)! - preIndex.get(b.id)!;
			});
			const lastRow = new Map<number, number>();
			inLayer.forEach(({ id, desired }) => {
				const band = bandOf.get(id)!;
				const next = (lastRow.get(band) ?? -1) + 1;
				const r = desired !== undefined ? Math.max(desired, next) : next;
				row.set(id, r);
				lastRow.set(band, r);
			});
		}

		// Column widths and row heights.
		const colWidth: number[] = new Array(layerCount).fill(0);
		const rowHeight: number[][] = Array.from({ length: bandCount }, () => []);
		ids.forEach(id => {
			const size = sizes.get(id)!;
			const l = layer.get(id)!;
			const band = bandOf.get(id)!;
			const r = row.get(id)!;
			colWidth[l] = Math.max(colWidth[l], size.width);
			rowHeight[band][r] = Math.max(rowHeight[band][r] || 0, size.height);
		});
		const colX: number[] = [];
		colWidth.reduce((x, w, l) => {
			colX[l] = x;
			return x + w + H_GAP;
		}, 0);
		const rowY: number[][] = rowHeight.map(heights => {
			const ys: number[] = [];
			let y = 0;
			for (let r = 0; r < heights.length; r++) {
				ys[r] = y;
				y += (heights[r] || 0) + V_GAP;
			}
			return ys;
		});

		// Items in band-local coordinates.
		const items = new Map<string, Placement>();
		const itemBand = new Map<string, number>();
		ids.forEach(id => {
			const size = sizes.get(id)!;
			const l = layer.get(id)!;
			const band = bandOf.get(id)!;
			const r = row.get(id)!;
			items.set(id, {
				bounds: {
					x: colX[l] + (colWidth[l] - size.width) / 2,
					y: rowY[band][r] + (rowHeight[band][r] - size.height) / 2,
					width: size.width,
					height: size.height,
				},
				isExpanded: nested.has(id) ? true : undefined,
			});
			itemBand.set(id, band);
		});

		// Boundary events on the bottom edge of their host.
		ids.forEach(hostId => {
			const host = items.get(hostId)!.bounds;
			const attached = children.filter(s => s.type === 'boundaryEvent' && s.attachedToRef === hostId && missing.has(s.id));
			attached.forEach((be, i) => {
				items.set(be.id, {
					bounds: {
						x: host.x + host.width * (i + 1) / (attached.length + 1) - BOUNDARY_SIZE / 2,
						y: host.y + host.height - BOUNDARY_SIZE / 2,
						width: BOUNDARY_SIZE,
						height: BOUNDARY_SIZE,
					},
				});
				itemBand.set(be.id, itemBand.get(hostId)!);
			});
		});

		// Artifacts next to the element they are associated with. Those tied
		// only to elements outside this container are placed later, next to
		// their element; unassociated ones go below the content.
		const unassociated: BpmnSemantic[] = [];
		children.filter(s => ARTIFACT_TYPES.has(s.type) && missing.has(s.id)).forEach(a => {
			const partners = associatedWith(a.id);
			const local = partners.find(p => items.has(p) && !ARTIFACT_TYPES.has(byId.get(p)!.type));
			if (!local) {
				if (partners.length === 0) unassociated.push(a);
				return;
			}
			const size = sizeOf(a);
			const anchor = items.get(local)!.bounds;
			const band = itemBand.get(local)!;
			const b: Bounds = a.type === 'textAnnotation'
				? { x: anchor.x + anchor.width / 2, y: anchor.y - size.height - ARTIFACT_GAP, ...size }
				: { x: anchor.x + (anchor.width - size.width) / 2, y: anchor.y + anchor.height + ARTIFACT_GAP, ...size };
			const sameBand = () => Array.from(items.entries())
				.filter(([id]) => itemBand.get(id) === band)
				.map(([, p]) => p.bounds);
			for (let tries = 0; tries < 50 && sameBand().some(o => overlaps(o, b)); tries++) {
				b.x += size.width + 20;
			}
			items.set(a.id, { bounds: b });
			itemBand.set(a.id, band);
		});

		// Normalize: each band starts at y = 0, the content at x = 0.
		const bandHeights: number[] = new Array(bandCount).fill(0);
		const all = Array.from(items.entries());
		for (let band = 0; band < bandCount; band++) {
			const inBand = all.filter(([id]) => itemBand.get(id) === band).map(([, p]) => p.bounds);
			if (inBand.length === 0) continue;
			const minY = Math.min(...inBand.map(b => b.y));
			const maxY = Math.max(...inBand.map(b => b.y + b.height));
			inBand.forEach(b => {
				b.y -= minY;
			});
			bandHeights[band] = maxY - minY;
		}
		const minX = all.length > 0 ? Math.min(...all.map(([, p]) => p.bounds.x)) : 0;
		all.forEach(([, p]) => {
			p.bounds.x -= minX;
		});
		let width = all.length > 0 ? Math.max(...all.map(([, p]) => p.bounds.x + p.bounds.width)) : 0;

		// Unassociated artifacts in a row below the last band.
		if (unassociated.length > 0) {
			const band = bandCount - 1;
			const top = bandHeights[band] > 0 ? bandHeights[band] + V_GAP : 0;
			let x = 0;
			let bottom = bandHeights[band];
			unassociated.forEach(a => {
				const size = sizeOf(a);
				items.set(a.id, { bounds: { x, y: top, ...size } });
				itemBand.set(a.id, band);
				x += size.width + 20;
				bottom = Math.max(bottom, top + size.height);
			});
			bandHeights[band] = bottom;
			width = Math.max(width, x - 20);
		}

		// Stack the bands. In lane mode each band is a Lane with padding.
		const bandTops: number[] = [];
		const bandSpans: number[] = [];
		let top = 0;
		for (let band = 0; band < bandCount; band++) {
			const span = laneMode
				? Math.max(bandHeights[band] + PAD * 2, ELEMENT_SIZES.lane.height)
				: bandHeights[band];
			const offset = laneMode ? (span - bandHeights[band]) / 2 : 0;
			items.forEach((p, id) => {
				if (itemBand.get(id) === band) p.bounds.y += top + offset;
			});
			bandTops.push(top);
			bandSpans.push(span);
			top += span;
		}

		return { items, nested, width, height: top, bandTops, bandSpans };
	};

	// Write a content's items (and nested Sub-Process content) at an offset.
	const emit = (content: Content, dx: number, dy: number): void => {
		content.items.forEach((p, id) => {
			const b = { ...p.bounds, x: p.bounds.x + dx, y: p.bounds.y + dy };
			place(id, b, p.isExpanded);
			const inner = content.nested.get(id);
			if (inner) emit(inner, b.x + PAD, b.y + PAD + SUBPROCESS_HEADER);
		});
	};

	const contentBounds = (): Bounds | undefined => {
		const all = Array.from(bounds.values());
		if (all.length === 0) return undefined;
		const minX = Math.min(...all.map(b => b.x));
		const minY = Math.min(...all.map(b => b.y));
		return {
			x: minX,
			y: minY,
			width: Math.max(...all.map(b => b.x + b.width)) - minX,
			height: Math.max(...all.map(b => b.y + b.height)) - minY,
		};
	};

	const layoutPool = (pool: BpmnSemantic): { content: Content; width: number; height: number; laneMode: boolean } => {
		const lanes = lanesOf(pool.id);
		const laneMode = lanes.length > 0 && lanes.every(l => missing.has(l.id));
		const content = layoutContent(pool.id, laneMode ? lanes : []);
		const width = Math.max(POOL_HEADER + PAD * 2 + content.width, ELEMENT_SIZES.pool.width);
		const height = laneMode
			? content.height
			: content.items.size > 0
				? Math.max(content.height + PAD * 2, ELEMENT_SIZES.pool.height)
				: ELEMENT_SIZES.lane.height;
		return { content, width, height, laneMode };
	};

	if (missing.size > 0) {
		// Top level: the process content outside any Pool, then the Pools,
		// stacked below whatever is already placed.
		const existing = contentBounds();
		const x = existing ? existing.x : ORIGIN.x;
		let y = existing ? existing.y + existing.height + CONTAINER_GAP : ORIGIN.y;

		if (isFresh(undefined)) {
			const content = layoutContent(undefined, []);
			emit(content, x, y);
			y += content.height + CONTAINER_GAP;
		}

		const freshPools = semantics.filter(s => s.type === 'pool' && missing.has(s.id) &&
			childrenOf(s.id).filter(isFlowNode).every(n => missing.has(n.id)));
		const poolLayouts = freshPools.map(pool => ({ pool, ...layoutPool(pool) }));
		const poolWidth = Math.max(0, ...poolLayouts.map(p => p.width));
		poolLayouts.forEach(({ pool, content, height, laneMode }) => {
			place(pool.id, { x, y, width: poolWidth, height });
			const contentY = laneMode ? y : y + (height - content.height) / 2;
			emit(content, x + POOL_HEADER + PAD, contentY);
			if (laneMode) {
				lanesOf(pool.id).forEach((lane, i) => {
					place(lane.id, {
						x: x + POOL_HEADER,
						y: y + content.bandTops[i],
						width: poolWidth - POOL_HEADER,
						height: content.bandSpans[i],
					});
				});
			}
			y += height + CONTAINER_GAP;
		});
	}

	// =========================================================================
	// Elements missing among placed ones
	// =========================================================================

	const interiorOf = (containerId: string | undefined): { x: number; y: number } => {
		const container = containerId ? byId.get(containerId) : undefined;
		const b = containerId ? bounds.get(containerId) : undefined;
		if (container && b) {
			return container.type === 'pool'
				? { x: b.x + POOL_HEADER + PAD, y: b.y + PAD }
				: { x: b.x + PAD, y: b.y + PAD + SUBPROCESS_HEADER };
		}
		const all = contentBounds();
		return all ? { x: all.x, y: all.y + all.height + CONTAINER_GAP } : { ...ORIGIN };
	};

	// Grow a Pool or Sub-Process (and a Pool's Lanes) to hold its children.
	const fitContainer = (containerId: string | undefined): void => {
		if (!containerId) return;
		const container = byId.get(containerId);
		const b = bounds.get(containerId);
		if (!container || !b) return;
		const kids = childrenOf(containerId).map(k => bounds.get(k.id)).filter((k): k is Bounds => !!k);
		if (kids.length === 0) return;
		const right = Math.max(...kids.map(k => k.x + k.width)) + PAD;
		const bottom = Math.max(...kids.map(k => k.y + k.height)) + PAD;
		if (right <= b.x + b.width && bottom <= b.y + b.height) return;
		const width = Math.max(b.width, right - b.x);
		const height = Math.max(b.height, bottom - b.y);
		resize(containerId, { ...b, width, height });
		if (container.type === 'pool') {
			const lanes = lanesOf(containerId)
				.filter(l => bounds.has(l.id))
				.sort((l1, l2) => bounds.get(l1.id)!.y - bounds.get(l2.id)!.y);
			lanes.forEach((lane, i) => {
				const lb = bounds.get(lane.id)!;
				const laneHeight = i === lanes.length - 1 ? b.y + height - lb.y : lb.height;
				resize(lane.id, { ...lb, width: b.x + width - lb.x, height: laneHeight });
			});
		}
		fitContainer(container.parentId);
	};

	// Nudge a new shape down until it is clear of the shapes around it.
	const clear = (id: string, b: Bounds): Bounds => {
		const ancestors = new Set<string>();
		for (let p = byId.get(id)?.parentId; p; p = byId.get(p)?.parentId) ancestors.add(p);
		const obstacles = Array.from(bounds.entries())
			.filter(([other]) => other !== id && !ancestors.has(other) && byId.get(other)?.type !== 'pool' && byId.get(other)?.type !== 'lane')
			.map(([, ob]) => ob);
		for (let tries = 0; tries < 50; tries++) {
			const hit = obstacles.find(o => overlaps(o, b));
			if (!hit) break;
			b.y = hit.y + hit.height + ARTIFACT_GAP;
		}
		return b;
	};

	const placeNear = (s: BpmnSemantic, fallback: boolean): boolean => {
		const container = s.parentId || undefined;
		if (container && !bounds.has(container)) return false;

		if (s.type === 'boundaryEvent') {
			const host = s.attachedToRef ? bounds.get(s.attachedToRef) : undefined;
			if (!host) {
				if (!fallback) return false;
			} else {
				const siblings = semantics.filter(o => o.type === 'boundaryEvent' && o.attachedToRef === s.attachedToRef);
				const i = siblings.indexOf(s);
				place(s.id, {
					x: host.x + host.width * (i + 1) / (siblings.length + 1) - BOUNDARY_SIZE / 2,
					y: host.y + host.height - BOUNDARY_SIZE / 2,
					width: BOUNDARY_SIZE,
					height: BOUNDARY_SIZE,
				});
				return true;
			}
		}

		if (s.type === 'lane') {
			const pool = s.parentPoolId ? bounds.get(s.parentPoolId) : undefined;
			if (!pool) return false;
			const placedLanes = lanesOf(s.parentPoolId!).filter(l => bounds.has(l.id)).map(l => bounds.get(l.id)!);
			const y = placedLanes.length > 0 ? Math.max(...placedLanes.map(l => l.y + l.height)) : pool.y;
			const height = placedLanes.length > 0 ? ELEMENT_SIZES.lane.height : pool.height;
			place(s.id, { x: pool.x + POOL_HEADER, y, width: pool.width - POOL_HEADER, height });
			if (y + height > pool.y + pool.height) resize(s.parentPoolId!, { ...pool, height: y + height - pool.y });
			return true;
		}

		if (s.type === 'pool') {
			const kids = childrenOf(s.id).map(k => bounds.get(k.id)).filter((k): k is Bounds => !!k);
			if (kids.length === 0) {
				if (!fallback) return false;
				const at = interiorOf(undefined);
				place(s.id, { x: at.x, y: at.y, width: ELEMENT_SIZES.pool.width, height: ELEMENT_SIZES.lane.height });
				return true;
			}
			const minX = Math.min(...kids.map(k => k.x)) - POOL_HEADER - PAD;
			const minY = Math.min(...kids.map(k => k.y)) - PAD;
			const maxX = Math.max(...kids.map(k => k.x + k.width)) + PAD;
			const maxY = Math.max(...kids.map(k => k.y + k.height)) + PAD;
			place(s.id, { x: minX, y: minY, width: maxX - minX, height: maxY - minY });
			return true;
		}

		const size = s.type === 'subProcess' && hasFlowChildren(s.id) ? ELEMENT_SIZES.expandedSubProcess : sizeOf(s);
		const isExpanded = s.type === 'subProcess' && hasFlowChildren(s.id) ? true : undefined;
		let b: Bounds | undefined;

		if (ARTIFACT_TYPES.has(s.type)) {
			const anchor = associatedWith(s.id).map(p => bounds.get(p)).find((p): p is Bounds => !!p);
			if (anchor) {
				b = s.type === 'textAnnotation'
					? { x: anchor.x + anchor.width / 2, y: anchor.y - size.height - ARTIFACT_GAP, ...size }
					: { x: anchor.x + (anchor.width - size.width) / 2, y: anchor.y + anchor.height + ARTIFACT_GAP, ...size };
			}
		} else {
			const pred = sequenceFlows.filter(f => f.targetRef === s.id).map(f => bounds.get(f.sourceRef)).find((p): p is Bounds => !!p);
			const succ = sequenceFlows.filter(f => flowSource(f.sourceRef) === s.id).map(f => bounds.get(f.targetRef)).find((p): p is Bounds => !!p);
			if (pred) {
				b = { x: pred.x + pred.width + H_GAP, y: pred.y + pred.height / 2 - size.height / 2, ...size };
			} else if (succ) {
				b = { x: succ.x - H_GAP - size.width, y: succ.y + succ.height / 2 - size.height / 2, ...size };
			}
			// Keep a node inside its Lane.
			const lane = semantics.find(l => l.type === 'lane' && l.flowNodeRefs?.includes(s.id) && bounds.has(l.id));
			if (b && lane) {
				const lb = bounds.get(lane.id)!;
				if (b.y < lb.y || b.y + b.height > lb.y + lb.height) b.y = lb.y + (lb.height - b.height) / 2;
			}
		}

		if (!b) {
			if (!fallback) return false;
			const siblings = childrenOf(container).map(k => bounds.get(k.id)).filter((k): k is Bounds => !!k);
			const at = siblings.length > 0
				? { x: Math.min(...siblings.map(k => k.x)), y: Math.max(...siblings.map(k => k.y + k.height)) + V_GAP }
				: interiorOf(container);
			b = { ...at, ...size };
		}

		place(s.id, clear(s.id, b), isExpanded);
		fitContainer(container);
		return true;
	};

	// Containers placed already whose children are all missing are laid out
	// as a whole inside the container; other missing elements go next to a
	// placed neighbour. Repeat until nothing is left, falling back to an
	// empty spot when no neighbour is placed.
	for (let guard = semantics.length * 2 + 10; missing.size > 0 && guard > 0; guard--) {
		let progress = false;

		semantics
			.filter(c => (c.type === 'pool' || c.type === 'subProcess') && bounds.has(c.id) && isFresh(c.id))
			.forEach(c => {
				const at = interiorOf(c.id);
				emit(layoutContent(c.id, []), at.x, at.y);
				fitContainer(c.id);
				progress = true;
			});

		semantics.filter(s => missing.has(s.id)).forEach(s => {
			if (missing.has(s.id) && placeNear(s, false)) progress = true;
		});

		if (!progress) {
			const next = semantics.find(s => missing.has(s.id) && placeNear(s, true));
			if (!next) break;
		}
	}

	// =========================================================================
	// Write back
	// =========================================================================

	const plane = store.getAllDiagrams()[0]?.plane;
	added.forEach((p, id) => {
		const s = byId.get(id)!;
		const shape = {
			id: generateUUID(),
			bpmnElement: id,
			bounds: p.bounds,
			...(p.isExpanded !== undefined ? { isExpanded: p.isExpanded } : {}),
			...(s.type === 'pool' || s.type === 'lane' ? { isHorizontal: s.isHorizontal ?? true } : {}),
		};
		store.addShape(shape);
		plane?.shapes.push(shape);
	});
	grown.forEach(id => {
		const shape = store.getShapesForSemantic(id)[0];
		if (shape) store.updateShape(shape.id, { bounds: bounds.get(id)! });
	});

	// Flows: an edge without waypoints is routed by the renderer. An existing
	// edge whose endpoint was just placed points nowhere useful; reroute it.
	let edgesAdded = 0;
	flows.forEach(f => {
		const edges = store.getEdgesForFlow(f.id);
		if (edges.length === 0) {
			if (!bounds.has(f.sourceRef) || !bounds.has(f.targetRef)) return;
			const edge = { id: generateUUID(), bpmnElement: f.id, waypoints: [] };
			store.addEdge(edge);
			plane?.edges.push(edge);
			edgesAdded++;
		} else if (added.has(f.sourceRef) || added.has(f.targetRef)) {
			edges.forEach(e => store.updateEdge(e.id, { waypoints: [] }));
		}
	});

	return added.size + edgesAdded;
}
