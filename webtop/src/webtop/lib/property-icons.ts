// The icon a property shows in lists, by the kind of value it holds. Shared
// by the Inspector's property rows and the memo app's dataset columns.
export function propertyTypeIcon(type: string | null | undefined): string {
	switch ((type || '').toUpperCase()) {
		case 'LONG': case 'DOUBLE': case 'DECIMAL': return 'bi-123';
		case 'BOOLEAN': return 'bi-check2-square';
		case 'DATE': return 'bi-calendar-event';
		case 'BINARY': return 'bi-file-earmark-binary';
		case 'REFERENCE': case 'WEAKREFERENCE': return 'bi-link-45deg';
		case 'PATH': return 'bi-signpost';
		case 'URI': return 'bi-globe';
		case 'NAME': return 'bi-hash';
		default: return 'bi-fonts';
	}
}
