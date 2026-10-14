/**
 * File types offered by "New File".
 *
 * Shared by Content Browser and the desktop's empty-area menu so both create
 * the same kinds of file with the same starting content.
 *
 * `id`        — value of the type selector.
 * `label`     — English fallback; localized via `<prefix>.<labelKey>` at the
 *               call site.
 * `extension` — appended to the entered name unless it already ends with it.
 * `content`   — initial file content (ASCII); the file starts empty without it.
 */
export interface NewFileType {
	id: string;
	label: string;
	labelKey: string;
	extension: string;
	mimeType: string;
	content?: string;
}

export const NEW_FILE_TYPES: NewFileType[] = [
	{ id: 'text', label: 'Text Document', labelKey: 'text', extension: '.txt', mimeType: 'text/plain' },
	{ id: 'html', label: 'HTML Document', labelKey: 'html', extension: '.html', mimeType: 'text/html' },
	{ id: 'css', label: 'CSS Stylesheet', labelKey: 'css', extension: '.css', mimeType: 'text/css' },
	{ id: 'javascript', label: 'JavaScript', labelKey: 'javascript', extension: '.js', mimeType: 'application/javascript' },
	{ id: 'json', label: 'JSON', labelKey: 'json', extension: '.json', mimeType: 'application/json' },
	{ id: 'xml', label: 'XML Document', labelKey: 'xml', extension: '.xml', mimeType: 'application/xml' },
	{ id: 'markdown', label: 'Markdown', labelKey: 'markdown', extension: '.md', mimeType: 'text/markdown' },
	{ id: 'csv', label: 'CSV', labelKey: 'csv', extension: '.csv', mimeType: 'text/csv' },
	{ id: 'yml', label: 'YAML Document', labelKey: 'yml', extension: '.yml', mimeType: 'application/yaml' },
	{ id: 'bpmn', label: 'BPMN Document', labelKey: 'bpmn', extension: '.bpmn', mimeType: 'application/bpmn+xml' },
	{ id: 'eip.xml', label: 'EIP/Route Document', labelKey: 'eipxml', extension: '.eip.xml', mimeType: 'application/vnd.webtop.eip+xml' },
	// Starts as the empty document the Memo app writes for a new memo.
	{ id: 'memo', label: 'Memo', labelKey: 'memo', extension: '.memo', mimeType: 'application/vnd.mintjams.cms.memo+json',
		content: JSON.stringify({ version: 1, type: 'tiptap', doc: { type: 'doc', content: [{ type: 'paragraph' }] } }) },
];

/** The entered name with the type's extension appended unless already present. */
export function withExtension(name: string, type: NewFileType): string {
	return name.toLowerCase().endsWith(type.extension.toLowerCase()) ? name : name + type.extension;
}
