// =============================================================================
// XML serialization helpers
// =============================================================================
// Small pure utilities used by the BPMN serializers. Kept separate so they
// can be unit-tested without pulling in a full DOMParser environment.
// =============================================================================

import type { Bounds, Point } from './bpmn-model-types.js';

/**
 * Escape characters that have special meaning in XML text / attribute
 * values. Suitable for element text and most attribute uses.
 */
export function escapeXml(str: string): string {
	return str
		.replace(/&/g, '&amp;')
		.replace(/</g, '&lt;')
		.replace(/>/g, '&gt;')
		.replace(/"/g, '&quot;')
		.replace(/'/g, '&apos;');
}

/**
 * Escape string for use in XML attributes.
 * In addition to standard XML escaping, this also escapes newlines and tabs
 * which are not allowed as literal characters in XML attribute values.
 */
export function escapeXmlAttr(str: string): string {
	return str
		.replace(/&/g, '&amp;')
		.replace(/</g, '&lt;')
		.replace(/>/g, '&gt;')
		.replace(/"/g, '&quot;')
		.replace(/'/g, '&apos;')
		.replace(/\n/g, '&#10;')
		.replace(/\r/g, '&#13;')
		.replace(/\t/g, '&#9;');
}

/**
 * Pretty-print an arbitrary XML element to indented lines, preserving its tag
 * (with namespace prefix), attributes and nested structure. Used to round-trip
 * extension elements the editor does not model: they are captured verbatim on
 * parse and re-emitted on save instead of being dropped.
 *
 * Lines are produced with 2-space indentation starting at `indent` (base column
 * 0 by default); callers prepend their own base indent when splicing the block
 * into a larger document.
 *
 * Text content is XML-escaped. Mixed content (significant text alongside child
 * elements) does not occur in Camunda extension subtrees and is not preserved;
 * whitespace-only text between child elements is normalised away.
 */
export function serializeElementToLines(el: Element, indent: string = ''): string[] {
	const tag = el.tagName; // qualified name, e.g. "camunda:taskListener"
	let attrs = '';
	for (const attr of Array.from(el.attributes)) {
		attrs += ` ${attr.name}="${escapeXmlAttr(attr.value)}"`;
	}

	const childElements = Array.from(el.children);
	if (childElements.length === 0) {
		const text = el.textContent ?? '';
		if (text.trim() === '') {
			return [`${indent}<${tag}${attrs}/>`];
		}
		return [`${indent}<${tag}${attrs}>${escapeXml(text)}</${tag}>`];
	}

	const lines: string[] = [`${indent}<${tag}${attrs}>`];
	for (const child of childElements) {
		lines.push(...serializeElementToLines(child, indent + '  '));
	}
	lines.push(`${indent}</${tag}>`);
	return lines;
}

// =============================================================================
// Geometry helpers used by the store-mode serializer to compute the
// waypoints written to bpmndi:BPMNEdge when an edge has none.
// =============================================================================

export function getCenterForSave(b: Bounds): Point {
	return { x: b.x + b.width / 2, y: b.y + b.height / 2 };
}

export function getBestCompassPointForSave(b: Bounds, targetPoint: Point): Point {
	const cx = b.x + b.width / 2;
	const cy = b.y + b.height / 2;
	const points = [
		{ x: cx, y: b.y },                  // N
		{ x: cx, y: b.y + b.height },       // S
		{ x: b.x + b.width, y: cy },        // E
		{ x: b.x, y: cy }                   // W
	];
	let minKey = 0;
	let minDist = Number.MAX_VALUE;
	points.forEach((p, i) => {
		const d = (p.x - targetPoint.x) ** 2 + (p.y - targetPoint.y) ** 2;
		if (d < minDist) { minDist = d; minKey = i; }
	});
	return points[minKey];
}

export function getPerimeterPointForSave(b: Bounds, targetPoint: Point, isDiamond: boolean): Point {
	const cx = b.x + b.width / 2;
	const cy = b.y + b.height / 2;
	const dx = targetPoint.x - cx;
	const dy = targetPoint.y - cy;
	if (dx === 0 && dy === 0) return { x: cx, y: cy };

	if (isDiamond) {
		const w2 = b.width / 2;
		const h2 = b.height / 2;
		const t = 1 / ((Math.abs(dx) / w2) + (Math.abs(dy) / h2));
		return { x: cx + dx * t, y: cy + dy * t };
	} else {
		const w2 = b.width / 2;
		const h2 = b.height / 2;
		if (Math.abs(dx / w2) > Math.abs(dy / h2)) {
			const sign = dx > 0 ? 1 : -1;
			return { x: cx + sign * w2, y: cy + dy * Math.abs(w2 / dx) };
		} else {
			const sign = dy > 0 ? 1 : -1;
			return { x: cx + dx * Math.abs(h2 / dy), y: cy + sign * h2 };
		}
	}
}
