/**
 * What the editors share for "Save As" (<wt-save-as>): the labels of the
 * dialog and of the formats, read from the global bundle
 * (/etc/i18n/wt-save-as.<locale>.json, keys webtop.saveAs.*).
 */

export type { SaveAsFormat, SaveAsSaved, SaveAsTarget } from '../ui/wt-save-as.js';

export type Translate = (messageId: string, params?: Record<string, any>, fallback?: string) => string;

/** The labels <wt-save-as> takes, in the reader's language. */
export function saveAsLabels(t: Translate): Record<string, string> {
	return {
		title: t('webtop.saveAs.title', undefined, 'Save As'),
		fileName: t('webtop.saveAs.fileName', undefined, 'File name'),
		format: t('webtop.saveAs.format', undefined, 'Format'),
		dragHint: t('webtop.saveAs.dragHint', undefined, 'Drag this file to where it should be saved: a folder in the Content Browser, or the Desktop.'),
		// The component fills {targets} and {name} itself.
		onlyTo: t('webtop.saveAs.onlyTo', { targets: '{targets}' }, 'This format can be saved to {targets} only.'),
		targetRepository: t('webtop.saveAs.target.repository', undefined, 'the repository'),
		targetGoogle: t('webtop.saveAs.target.google', undefined, 'Google Drive'),
		targetMicrosoft: t('webtop.saveAs.target.microsoft', undefined, 'OneDrive'),
		saved: t('webtop.saveAs.saved', { name: '{name}' }, 'Saved {name}.'),
		open: t('webtop.saveAs.open', undefined, 'Open'),
		untitled: t('webtop.saveAs.untitled', undefined, 'untitled'),
	};
}

const FORMAT_LABELS: Record<string, string> = {
	memo: 'Memo',
	html: 'HTML',
	googleDocument: 'Google document',
	text: 'Plain text',
	asNamed: 'As named (the type follows the extension)',
	bpmn: 'BPMN XML',
	eip: 'EIP XML',
};

/** The label of a format shared by the editors: memo, html, googleDocument, text, asNamed, bpmn, eip. */
export function saveAsFormatLabel(t: Translate, key: string): string {
	return t(`webtop.saveAs.format.${key}`, undefined, FORMAT_LABELS[key] || key);
}
