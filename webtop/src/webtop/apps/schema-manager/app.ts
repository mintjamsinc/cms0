import { ApplicationInstance } from "../../services/webtop-service.js";

// Constants
const SCHEMAS_PATH = '/etc/metadata/schemas';
const MIXINS_PATH  = '/etc/metadata/mixins';
const MIME_TYPE = 'application/vnd.webtop.metadata-schema+json';

// Discriminator for SchemaDefinition entries. A "schema" lives under
// SCHEMAS_PATH and may declare mixin references; a "mixin" lives under
// MIXINS_PATH and supplies reusable properties that schemas pull in. The two
// share the exact same in-memory shape so they can reuse the existing editor;
// only the persistence path and which fields are meaningful differ.
type SchemaKind = 'schema' | 'mixin';

// JCR property types
const JCR_TYPES = [
	'STRING', 'LONG', 'DOUBLE', 'DECIMAL', 'DATE', 'BOOLEAN',
	'NAME', 'PATH', 'URI', 'BINARY',
	'REFERENCE', 'WEAKREFERENCE',
] as const;
type JcrType = typeof JCR_TYPES[number];

// Display labels for JCR types (Title Case)
const JCR_TYPE_LABELS: Record<string, string> = {
	STRING:        'String',
	LONG:          'Long',
	DOUBLE:        'Double',
	DECIMAL:       'Decimal',
	DATE:          'Date',
	BOOLEAN:       'Boolean',
	NAME:          'Name',
	PATH:          'Path',
	URI:           'URI',
	BINARY:        'Binary',
	REFERENCE:     'Reference',
	WEAKREFERENCE: 'WeakReference',
};

// Editor types per JCR type (no choices)
const STRING_EDITOR_TYPES = ['Text', 'Color', 'Tel', 'Email', 'URL', 'Password', 'TextArea', 'Text Editor', 'JSON', 'XML', 'HTML', 'Markdown'] as const;
const DATE_EDITOR_TYPES = ['LocalDateTime', 'LocalDate', 'LocalTime'] as const;
const BOOLEAN_EDITOR_TYPES = ['select', 'radio'] as const;
const CHOICE_EDITOR_TYPES = ['select', 'radio', 'checkbox'] as const;
type EditorType = string;

// Renderer types
const RENDERER_TYPES = ['IMAGE', 'VIDEO', 'AUDIO', 'LINK'] as const;
type RendererType = typeof RENDERER_TYPES[number];

// SemanticType is now a free-text string field
type SemanticType = string;

// JCR_DEFAULT_SEMANTIC used for scan inference only
const JCR_DEFAULT_SEMANTIC: Record<string, string> = {
	STRING:        'text',
	LONG:          'number',
	DOUBLE:        'number',
	DECIMAL:       'number',
	DATE:          'datetime',
	BOOLEAN:       'flag',
	NAME:          'identifier',
	PATH:          'node_path',
	URI:           'url',
	BINARY:        'image_data',
	REFERENCE:     'doc_ref',
	WEAKREFERENCE: 'soft_link',
};

interface Choice {
	value: string;
	label: string;
}

interface QueryConfig {
	xpath: string;
	labelKey: string;
}

// Scanned property from file analysis
interface ScannedProperty {
	checked: boolean;
	key: string;
	label: string;
	type: JcrType;
	semantic: SemanticType;
	multiple: boolean;
	sampleValue: string;
	isDuplicate: boolean;
	_selected: boolean;
}

// Scan state
interface ScanState {
	phase: '' | 'drop' | 'review';
	schemaID: string;
	sourceFileName: string;
	properties: ScannedProperty[];
	overwriteExisting: boolean;
	bulkPrefix: string;
	isDropHighlighted: boolean;
}

// Tokenize a key into words (handles camelCase, snake_case, kebab-case, dot.case)
function tokenizeKey(key: string): string[] {
	// Remove common prefixes like "dc:", "jcr:", etc.
	const colonIdx = key.indexOf(':');
	const raw = colonIdx >= 0 ? key.substring(colonIdx + 1) : key;

	// Split on separators first
	const parts = raw.split(/[_\-.]/).filter(Boolean);
	const words: string[] = [];
	for (const part of parts) {
		// Split camelCase
		const camelWords = part.replace(/([a-z])([A-Z])/g, '$1 $2')
			.replace(/([A-Z]+)([A-Z][a-z])/g, '$1 $2')
			.split(' ');
		words.push(...camelWords);
	}
	return words.map(w => w.toLowerCase()).filter(Boolean);
}

// Convert key to human-readable label
function keyToLabel(key: string): string {
	const words = tokenizeKey(key);
	return words.map(w => w.charAt(0).toUpperCase() + w.slice(1)).join(' ');
}

// Convert words to naming conventions
function toCamelCase(words: string[]): string {
	return words.map((w, i) => i === 0 ? w : w.charAt(0).toUpperCase() + w.slice(1)).join('');
}
function toSnakeCase(words: string[]): string {
	return words.join('_');
}
function toKebabCase(words: string[]): string {
	return words.join('-');
}
function toDotCase(words: string[]): string {
	return words.join('.');
}

// Infer property type from a JavaScript value
function inferType(value: any): { type: JcrType; multiple: boolean } {
	if (value === null || value === undefined) {
		return { type: 'STRING', multiple: false };
	}
	if (Array.isArray(value)) {
		if (value.length === 0) return { type: 'STRING', multiple: true };
		const inner = inferType(value[0]);
		return { type: inner.type, multiple: true };
	}
	switch (typeof value) {
		case 'boolean':
			return { type: 'BOOLEAN', multiple: false };
		case 'number':
			return { type: Number.isInteger(value) ? 'LONG' : 'DOUBLE', multiple: false };
		case 'string': {
			// Check for date-like strings
			if (/^\d{4}-\d{2}-\d{2}/.test(value)) {
				return { type: 'DATE', multiple: false };
			}
			return { type: 'STRING', multiple: false };
		}
		case 'object':
			// Nested object -> store as JSON string
			return { type: 'STRING', multiple: false };
		default:
			return { type: 'STRING', multiple: false };
	}
}

// Format sample value for display
function formatSampleValue(value: any): string {
	if (value === null || value === undefined) return '';
	if (typeof value === 'object') return JSON.stringify(value);
	return String(value);
}

interface PropertyDefinition {
	_id: string;
	key: string;
	label: string;
	description: string;
	type: JcrType;
	constraints: {
		required: boolean;
		multiple: boolean;
		minLength?: number;
		maxLength?: number;
		pattern?: string;
		minValue?: string;
		maxValue?: string;
		decimalPlaces?: number;
		choices: Choice[];
		query: QueryConfig;
	};
	behavior: {
		calculated: {
			enabled: boolean;
			formula: string;
		};
		defaultValue: {
			type: 'STATIC' | 'CALCULATED';
			value: string;
		};
	};
	uiHint: {
		readOnly: boolean;
		editorType: EditorType;
		rows?: number;
		mask?: string;
		validation?: string;
		displayFormat?: string;
		renderer?: RendererType | '';
	};
	extensions: {
		semantic: string;
	};
	_isNew: boolean;
	_isModified: boolean;
	_isDeleted: boolean;
}

interface SchemaDefinition {
	_id: string;
	kind: SchemaKind;
	key: string;
	label: string;
	description: string;
	// Mixin keys this schema pulls in (resolved order). Always empty for
	// kind === 'mixin' (Phase 1 forbids mixin nesting).
	mixins: string[];
	properties: PropertyDefinition[];
	_filePath: string;
	_isNew: boolean;
	_isModified: boolean;
	_isDeleted: boolean;
	_originalJSON: string;
	_expanded: boolean;
	// Last-saved key for this entry. Phase 2 rename-cascade compares this to
	// the current `key` on Save All: if they differ, every schema whose
	// `mixins[]` references _originalKey is auto-updated to the new key
	// before the mixin file itself is renamed on disk. Set on load and on
	// first save; never touched by editing the `key` field directly.
	_originalKey: string;
}

// Selection target: either a schema or a property within a schema
interface Selection {
	schemaID: string;
	propertyID: string; // empty if schema itself is selected
}

let idCounter = 0;
function nextID(): string {
	return '__id_' + (++idCounter);
}

function createEmptyProperty(): PropertyDefinition {
	return {
		_id: nextID(),
		key: '',
		label: '',
		description: '',
		type: 'STRING',
		constraints: {
			required: false,
			multiple: false,
			minLength: undefined,
			maxLength: undefined,
			pattern: undefined,
			minValue: undefined,
			maxValue: undefined,
			decimalPlaces: undefined,
			choices: [],
			query: { xpath: '', labelKey: '' },
		},
		behavior: {
			calculated: { enabled: false, formula: '' },
			defaultValue: { type: 'STATIC', value: '' },
		},
		uiHint: {
			readOnly: false,
			editorType: '',
			rows: undefined,
			mask: undefined,
			validation: undefined,
			displayFormat: undefined,
			renderer: '',
		},
		extensions: {
			semantic: '',
		},
		_isNew: true,
		_isModified: false,
		_isDeleted: false,
	};
}

function createEmptySchema(kind: SchemaKind = 'schema'): SchemaDefinition {
	return {
		_id: nextID(),
		kind,
		key: '',
		label: '',
		description: '',
		mixins: [],
		properties: [],
		_filePath: '',
		_isNew: true,
		_isModified: false,
		_isDeleted: false,
		_originalJSON: '',
		_expanded: true,
		_originalKey: '',
	};
}

// Deep-clone a property definition for the "Inline" delete flow: a mixin's
// properties get copied into each dependent schema's own-properties list, so
// the schema retains the same effective shape after the mixin is gone. The
// copy is marked _isNew so it round-trips through saveAll's create path; its
// `_id` is regenerated to keep DOM keys stable.
function cloneAsOwnProperty(p: PropertyDefinition): PropertyDefinition {
	return JSON.parse(JSON.stringify({
		...p,
		_id: nextID(),
		_isNew: true,
		_isModified: false,
		_isDeleted: false,
	})) as PropertyDefinition;
}

// Convert server JSON property to internal PropertyDefinition
function fromServerProperty(pd: any): PropertyDefinition {
	return {
		_id: nextID(),
		key: pd.key || '',
		label: pd.label || '',
		description: pd.description || '',
		type: pd.type || pd.jcrType || 'STRING',
		constraints: {
			required: pd.constraints?.required ?? pd.constraints?.mandatory ?? false,
			multiple: pd.constraints?.multiple ?? false,
			minLength: pd.constraints?.minLength,
			maxLength: pd.constraints?.maxLength,
			pattern: pd.constraints?.pattern,
			minValue: pd.constraints?.minValue,
			maxValue: pd.constraints?.maxValue,
			decimalPlaces: pd.constraints?.decimalPlaces,
			choices: (pd.constraints?.choices || []).map((c: any) => ({
				value: c.value || '',
				label: c.label || '',
			})),
			query: {
				xpath: pd.constraints?.query?.xpath || '',
				labelKey: pd.constraints?.query?.labelKey || '',
			},
		},
		behavior: {
			calculated: {
				enabled: pd.behavior?.calculated?.enabled ?? false,
				formula: pd.behavior?.calculated?.formula || '',
			},
			defaultValue: {
				type: pd.behavior?.defaultValue?.type || 'STATIC',
				value: pd.behavior?.defaultValue?.value || '',
			},
		},
		uiHint: {
			readOnly: pd.uiHint?.readOnly ?? false,
			editorType: pd.uiHint?.editorType || pd.uiHint?.edit?.editorType || '',
			rows: pd.uiHint?.rows,
			mask: pd.uiHint?.mask,
			validation: pd.uiHint?.validation,
			displayFormat: pd.uiHint?.displayFormat,
			renderer: pd.uiHint?.renderer || '',
		},
		extensions: {
			semantic: pd.extensions?.semantic || pd.semanticType || '',
		},
		_isNew: false,
		_isModified: false,
		_isDeleted: false,
	};
}

// Convert internal PropertyDefinition to server JSON format
function toServerProperty(prop: PropertyDefinition): any {
	const pd: any = {
		key: prop.key,
		label: prop.label,
		type: prop.type,
		constraints: {
			required: prop.constraints.required,
			multiple: prop.constraints.multiple,
		},
		behavior: {},
		uiHint: {},
		extensions: {},
	};

	// Description
	if (prop.description) {
		pd.description = prop.description;
	}

	// String constraints
	if (prop.type === 'STRING') {
		if (prop.constraints.minLength != null && prop.constraints.minLength > 0) {
			pd.constraints.minLength = prop.constraints.minLength;
		}
		if (prop.constraints.maxLength != null && prop.constraints.maxLength > 0) {
			pd.constraints.maxLength = prop.constraints.maxLength;
		}
		if (prop.constraints.pattern) {
			pd.constraints.pattern = prop.constraints.pattern;
		}
	}

	// Numeric constraints
	if (isNumericJcrType(prop.type)) {
		if (prop.constraints.minValue) pd.constraints.minValue = prop.constraints.minValue;
		if (prop.constraints.maxValue) pd.constraints.maxValue = prop.constraints.maxValue;
		if (prop.constraints.decimalPlaces != null) {
			pd.constraints.decimalPlaces = prop.constraints.decimalPlaces;
		}
	}

	// Choices
	if (prop.constraints.choices.length > 0) {
		pd.constraints.choices = prop.constraints.choices.filter((c: Choice) => c.value || c.label);
	}

	// Query
	if (prop.constraints.query.xpath) {
		pd.constraints.query = {
			xpath: prop.constraints.query.xpath,
			labelKey: prop.constraints.query.labelKey,
		};
	}

	// Calculated
	if (prop.behavior.calculated.enabled) {
		pd.behavior.calculated = {
			enabled: true,
			formula: prop.behavior.calculated.formula,
		};
	}

	// Default value
	if (prop.behavior.defaultValue.value) {
		pd.behavior.defaultValue = {
			type: prop.behavior.defaultValue.type,
			value: prop.behavior.defaultValue.value,
		};
	}

	// UI hint
	if (prop.uiHint.readOnly) {
		pd.uiHint.readOnly = true;
	}
	if (prop.uiHint.editorType) {
		pd.uiHint.editorType = prop.uiHint.editorType;
	}
	if (['TextArea', 'Text Editor', 'JSON', 'XML', 'HTML', 'Markdown'].includes(prop.uiHint.editorType) && prop.uiHint.rows) {
		pd.uiHint.rows = prop.uiHint.rows;
	}
	if (prop.uiHint.editorType === 'Password' && prop.uiHint.mask) {
		pd.uiHint.mask = prop.uiHint.mask;
	}
	if (prop.uiHint.validation) {
		pd.uiHint.validation = prop.uiHint.validation;
	}
	if (prop.uiHint.displayFormat) {
		pd.uiHint.displayFormat = prop.uiHint.displayFormat;
	}
	if (prop.uiHint.renderer) {
		pd.uiHint.renderer = prop.uiHint.renderer;
	}

	// Extensions
	if (prop.extensions.semantic) {
		pd.extensions.semantic = prop.extensions.semantic;
	}

	return pd;
}

// Serialize a single schema to JSON for comparison / saving
function schemaToJSON(schema: SchemaDefinition): string {
	const activeProps = schema.properties.filter(p => !p._isDeleted);
	const output: any = {
		kind: schema.kind,
		key: schema.key,
		label: schema.label,
	};
	if (schema.description) {
		output.description = schema.description;
	}
	// Mixin references are only meaningful on schemas. A mixin file never
	// declares its own mixins in Phase 1.
	if (schema.kind === 'schema' && schema.mixins && schema.mixins.length > 0) {
		output.mixins = [...schema.mixins];
	}
	output.properties = activeProps.map(p => toServerProperty(p));
	return JSON.stringify(output, null, 2);
}

function isNumericJcrType(jcrType: string): boolean {
	return ['DECIMAL', 'LONG', 'DOUBLE'].includes(jcrType);
}

export const App = {
	data() {
		return {
			instance: null as ApplicationInstance | null,
			isLoading: false,
			isSaving: false,
			errorMessage: '',
			messageListener: null as ((e: MessageEvent) => void) | null,
			// All schemas loaded from /etc/metadata/schemas/
			schemas: [] as SchemaDefinition[],
			// Current selection
			selection: { schemaID: '', propertyID: '' } as Selection,
			schemaFilter: '' as string,
			validationErrors: {} as Record<string, string>,
			// Drag reorder state (for properties within a schema)
			dragState: {
				draggingID: '' as string,
				overID: '' as string,
			},
			// Expose type arrays
			jcrTypes: [...JCR_TYPES],
			jcrTypeLabels: { ...JCR_TYPE_LABELS },
			stringEditorTypes: [...STRING_EDITOR_TYPES],
			dateEditorTypes: [...DATE_EDITOR_TYPES],
			choiceEditorTypes: [...CHOICE_EDITOR_TYPES],
			rendererTypes: [...RENDERER_TYPES],
			// Scan Properties state
			scanState: {
				phase: '' as '' | 'drop' | 'review',
				schemaID: '',
				sourceFileName: '',
				properties: [] as ScannedProperty[],
				overwriteExisting: false,
				bulkPrefix: '',
				selectedCase: '' as '' | 'camelCase' | 'snake_case' | 'kebab-case' | 'dot.case',
				isDropHighlighted: false,
				lastAnchorIndex: -1,
			} as ScanState & { lastAnchorIndex: number },
			// Sidebar resize / toggle state
			sidebarPanelVisible: true,
			sidebarPanelWidth: 260,
			_sidebarResizeMoveHandler: null as ((e: MouseEvent) => void) | null,
			_sidebarResizeUpHandler: null as ((e: MouseEvent) => void) | null,
			// Close confirmation dialog (shown when there are unsaved changes)
			closeConfirmDialog: {
				visible: false,
				resolve: null as null | ((result: 'save' | 'discard' | 'cancel') => void),
			},
			// Modal shown when deleting a mixin that has live dependents. The
			// user picks how to treat dependents (default: inline copy so the
			// effective shape is preserved; or detach to drop the inherited
			// properties). See confirmDeleteMixinDialog for the algorithm.
			deleteMixinDialog: {
				visible: false,
				mixinID: '' as string,
				mixinKey: '' as string,
				dependentIDs: [] as string[],
				mode: 'inline' as 'inline' | 'detach',
			},
			// Modal for Phase 2.5 "Extract to mixin": select own properties
			// from the current schema, name a new mixin, and split. The
			// extraction is in-memory only — Save All persists both the new
			// mixin file and the updated schema as a single batch.
			extractDialog: {
				visible: false,
				schemaID: '' as string,
				newKey: '' as string,
				newLabel: '' as string,
				newDescription: '' as string,
				// Selected propertyIDs from the source schema. The order
				// reflects the user's check sequence so we can preserve it
				// in the new mixin (which uses Set semantics on key only).
				selectedPropertyIDs: [] as string[],
			},
		};
	},
	computed: {
		filteredSchemas(): SchemaDefinition[] {
			const q = (this.schemaFilter || '').trim().toLowerCase();
			if (!q) return this.schemas;
			return (this.schemas as SchemaDefinition[]).filter((s: SchemaDefinition) => {
				if ((s.key || '').toLowerCase().includes(q)) return true;
				if ((s.label || '').toLowerCase().includes(q)) return true;
				return (s.properties || []).some((p: PropertyDefinition) =>
					(p.key || '').toLowerCase().includes(q) ||
					(p.label || '').toLowerCase().includes(q)
				);
			});
		},
		schemaFilterQuery(): string {
			return (this.schemaFilter || '').trim().toLowerCase();
		},
		validationPlaceholder(): string {
			return [
				'// Example: enforce max length and cross-field sum',
				'const v = ctx.getString(ctx.propertyName);',
				'if (v.length > 100) {',
				'\treturn {',
				'\t\tvalid: false,',
				'\t\terrors: [{',
				'\t\t\tmessageId: "cms.validation.string.tooLong",',
				'\t\t\tseverity: "error",',
				'\t\t\truleId: "maxLength",',
				'\t\t\tparams: { max: 100, actual: v.length },',
				'\t\t\tfallbackMessage: "Too long (max 100)"',
				'\t\t}]',
				'\t};',
				'}',
				'return { valid: true };',
			].join('\n');
		},
		selectedSchema(): SchemaDefinition | null {
			if (!this.selection.schemaID) return null;
			return this.schemas.find((s: SchemaDefinition) => s._id === this.selection.schemaID) || null;
		},
		selectedProperty(): PropertyDefinition | null {
			const schema = this.selectedSchema;
			if (!schema || !this.selection.propertyID) return null;
			return schema.properties.find((p: PropertyDefinition) => p._id === this.selection.propertyID) || null;
		},
		// Editing mode: 'schema' when a schema is selected, 'property' when a property is selected, null when nothing
		editingMode(): 'schema' | 'property' | null {
			if (this.selection.propertyID && this.selectedProperty) return 'property';
			if (this.selection.schemaID && this.selectedSchema) return 'schema';
			return null;
		},
		hasChanges(): boolean {
			return this.schemas.some((s: SchemaDefinition) => this.isSchemaChanged(s));
		},
		changedSchemaCount(): number {
			return this.schemas.filter((s: SchemaDefinition) => this.isSchemaChanged(s)).length;
		},
		// Available editor types based on type and whether choices are set
		availableEditorTypes(): string[] {
			const prop = this.selectedProperty;
			if (!prop) return [];
			const hasChoices = prop.constraints.choices.length > 0;
			if (prop.type === 'REFERENCE' || prop.type === 'WEAKREFERENCE' || prop.type === 'BINARY') return [];
			if (prop.type === 'NAME' || prop.type === 'PATH' || prop.type === 'URI') return [];
			if (hasChoices) return [...CHOICE_EDITOR_TYPES];
			if (prop.type === 'BOOLEAN') return [...BOOLEAN_EDITOR_TYPES];
			if (prop.type === 'STRING') return [...STRING_EDITOR_TYPES];
			if (prop.type === 'DATE') return [...DATE_EDITOR_TYPES];
			return [];
		},
		// Whether to show the Editor Type field
		showEditorType(): boolean {
			const prop = this.selectedProperty;
			if (!prop || prop.behavior.calculated.enabled || prop.uiHint.readOnly) return false;
			return this.availableEditorTypes.length > 0;
		},
		// Whether to show Validation field
		showValidation(): boolean {
			const prop = this.selectedProperty;
			if (!prop || prop.behavior.calculated.enabled || prop.uiHint.readOnly) return false;
			if (prop.type === 'REFERENCE' || prop.type === 'WEAKREFERENCE' || prop.type === 'BINARY') return false;
			return prop.constraints.choices.length === 0;
		},
		// Path-bar label for the currently selected schema
		schemaPathLabel(): string {
			const schema = this.selectedSchema;
			if (!schema) return '';
			const label = schema.label?.trim();
			if (label) return label;
			const key = schema.key?.trim();
			if (key) return key;
			return '(unnamed)';
		},
		// Path-bar label for the currently selected property
		propertyPathLabel(): string {
			const prop = this.selectedProperty;
			if (!prop) return '';
			const label = prop.label?.trim();
			if (label) return label;
			const key = prop.key?.trim();
			if (key) return key;
			return '(unnamed)';
		},
		// Path-bar label for the schema that scan mode is targeting
		scanSchemaLabel(): string {
			const schema = this.schemas.find(
				(s: SchemaDefinition) => s._id === this.scanState.schemaID,
			);
			if (!schema) return '';
			const label = schema.label?.trim();
			if (label) return label;
			const key = schema.key?.trim();
			if (key) return key;
			return '(unnamed)';
		},
		// Total active property count across all schemas and mixins (status bar)
		totalPropertyCount(): number {
			let total = 0;
			for (const s of this.schemas as SchemaDefinition[]) {
				if (s._isDeleted) continue;
				for (const p of s.properties) {
					if (!p._isDeleted) total++;
				}
			}
			return total;
		},
		schemaCount(): number {
			return (this.schemas as SchemaDefinition[]).filter(
				(s: SchemaDefinition) => s.kind === 'schema' && !s._isDeleted
			).length;
		},
		mixinCount(): number {
			return (this.schemas as SchemaDefinition[]).filter(
				(s: SchemaDefinition) => s.kind === 'mixin' && !s._isDeleted
			).length;
		},
		// Scan: number of checked properties
		checkedScanCount(): number {
			return this.scanState.properties.filter((p: ScannedProperty) => p.checked).length;
		},
		// Scan: number of selected rows (for bulk operations)
		selectedScanCount(): number {
			return this.scanState.properties.filter((p: ScannedProperty) => p._selected).length;
		},
		// Scan: number of duplicates
		duplicateScanCount(): number {
			return this.scanState.properties.filter((p: ScannedProperty) => p.isDuplicate).length;
		},
		// Whether scan mode is active
		isScanActive(): boolean {
			return this.scanState.phase === 'drop' || this.scanState.phase === 'review';
		},
	},
	methods: {
		async onMounted() {
			const vm = this;

			// Message listener
			vm.messageListener = async (event: MessageEvent) => {
				if (event.origin !== window.location.origin) return;
				const { type, ...payload } = event.data || {};
				if (type === 'theme-changed') {
					document.documentElement.dataset.theme = payload.theme;
					return;
				}
				if (type === 'metadata-definitions-updated') {
					// External change notification - could reload if no local changes
					return;
				}
			};
			window.addEventListener('message', vm.messageListener);

			// Keyboard shortcuts
			document.addEventListener('keydown', (e: KeyboardEvent) => {
				if ((e.ctrlKey || e.metaKey) && e.key === 's') {
					e.preventDefault();
					if (vm.hasChanges && !vm.isSaving) {
						vm.saveAll();
					}
				}
			});

			// App launch
			window.appLaunch = async (instance: ApplicationInstance, options?: {
				mode?: 'new' | 'edit';
				filePath?: string;
				path?: string;
				[key: string]: any;
			}) => {
				vm.instance = vm.$markRaw(instance);

				// Apply theme
				const theme = vm.instance.api.theme.currentTheme || 'light';
				document.documentElement.dataset.theme = theme;

				// Intercept window close to confirm unsaved changes
				instance.setBeforeCloseCallback(async () => {
					return await vm.confirmClose();
				});

				// Load all schemas
				await vm.loadAllSchemas();

				// If opened with a specific file path, select that schema
				const openPath = options?.filePath || options?.path;
				if (openPath) {
					const targetSchema = vm.schemas.find((s: SchemaDefinition) => s._filePath === openPath);
					if (targetSchema) {
						vm.selectSchema(targetSchema._id);
					}
				}

				// Set window title
				if (vm.instance) {
					vm.instance.windowTitle = 'Schema Editor';
				}

				vm.$nextTick(() => {
					instance.notifyLaunched();
				});
			};
		},

		async onUnmount() {
			const vm = this;
			if (vm.messageListener) {
				window.removeEventListener('message', vm.messageListener);
				vm.messageListener = null;
			}
			// Clean up sidebar resize listeners
			if (vm._sidebarResizeUpHandler) {
				document.removeEventListener('mouseup', vm._sidebarResizeUpHandler);
			}
			if (vm._sidebarResizeMoveHandler) {
				document.removeEventListener('mousemove', vm._sidebarResizeMoveHandler);
			}
		},

		// Window controls (custom title-bar buttons)
		onMinimizeWindow() {
			this.instance?.minimize();
		},
		onToggleMaximizeWindow() {
			this.instance?.toggleMaximize();
		},
		onCloseWindow() {
			this.instance?.requestClose();
		},

		// Toggle the left sidebar pane
		toggleSidebarPanel() {
			this.sidebarPanelVisible = !this.sidebarPanelVisible;
		},

		// Sidebar resize (drag the splitter between left and right panes)
		onSidebarResizeStart(e: MouseEvent) {
			e.preventDefault();
			const vm = this;
			const startX = e.clientX;
			const startWidth = vm.sidebarPanelWidth;

			vm._sidebarResizeMoveHandler = (moveEvent: MouseEvent) => {
				const delta = moveEvent.clientX - startX;
				vm.sidebarPanelWidth = Math.max(180, Math.min(600, startWidth + delta));
			};

			vm._sidebarResizeUpHandler = () => {
				document.removeEventListener('mousemove', vm._sidebarResizeMoveHandler!);
				document.removeEventListener('mouseup', vm._sidebarResizeUpHandler!);
				vm._sidebarResizeMoveHandler = null;
				vm._sidebarResizeUpHandler = null;
			};

			document.addEventListener('mousemove', vm._sidebarResizeMoveHandler);
			document.addEventListener('mouseup', vm._sidebarResizeUpHandler);
		},

		// ----------------------------------------------------------------
		// Popup-rendered dropdowns (replace native <select>)
		//
		// Uses the shell-side common popup (instance.popup.open). The shell
		// renders the menu above all iframes and resolves the selected id.
		// ----------------------------------------------------------------

		async openTypeDropdown(event: MouseEvent) {
			const vm = this;
			const prop = vm.selectedProperty;
			if (!prop || !vm.instance) return;
			const trigger = event.currentTarget as HTMLElement;
			const rect = trigger.getBoundingClientRect();
			const items = (vm.jcrTypes as JcrType[]).map(t => ({
				id: t,
				label: vm.jcrTypeLabels[t] || t,
				selected: prop.type === t,
			}));
			const handle = vm.instance.popup.open({
				anchor: rect,
				placement: 'bottom-start',
				minWidth: rect.width,
				items,
			});
			const result = await handle.result;
			if (result == null) return;
			prop.type = String(result) as JcrType;
			vm.onTypeChange();
		},

		async openEditorTypeDropdown(event: MouseEvent) {
			const vm = this;
			const prop = vm.selectedProperty;
			if (!prop || !vm.instance) return;
			const trigger = event.currentTarget as HTMLElement;
			const rect = trigger.getBoundingClientRect();
			const items: any[] = [
				{ id: '__auto__', label: '— Auto —', selected: !prop.uiHint.editorType },
			];
			for (const t of vm.availableEditorTypes as string[]) {
				items.push({ id: t, label: t, selected: prop.uiHint.editorType === t });
			}
			const handle = vm.instance.popup.open({
				anchor: rect,
				placement: 'bottom-start',
				minWidth: rect.width,
				items,
			});
			const result = await handle.result;
			if (result == null) return;
			prop.uiHint.editorType = result === '__auto__' ? '' : String(result);
			vm.onPropertyFieldChange();
		},

		async openScanRowTypeDropdown(event: MouseEvent, index: number) {
			const vm = this;
			if (!vm.instance) return;
			const sp = vm.scanState.properties[index] as ScannedProperty;
			if (!sp) return;
			const trigger = event.currentTarget as HTMLElement;
			const rect = trigger.getBoundingClientRect();
			const items = (vm.jcrTypes as JcrType[]).map(t => ({
				id: t,
				label: vm.jcrTypeLabels[t] || t,
				selected: sp.type === t,
			}));
			const handle = vm.instance.popup.open({
				anchor: rect,
				placement: 'bottom-start',
				minWidth: rect.width,
				items,
			});
			const result = await handle.result;
			if (result == null) return;
			sp.type = String(result) as JcrType;
			vm.onScanFieldChange(index, 'type');
		},

		async openScanCaseDropdown(event: MouseEvent) {
			const vm = this;
			if (!vm.instance) return;
			const trigger = event.currentTarget as HTMLElement;
			const rect = trigger.getBoundingClientRect();
			const items = [
				{ id: 'camelCase',  label: 'camel' },
				{ id: 'snake_case', label: 'snake' },
				{ id: 'kebab-case', label: 'kebab' },
				{ id: 'dot.case',   label: 'dot'   },
			];
			const handle = vm.instance.popup.open({
				anchor: rect,
				placement: 'bottom-start',
				minWidth: rect.width,
				items,
			});
			const result = await handle.result;
			if (result == null) return;
			vm.applyScanNamingConvention(result as any);
		},

		async openScanBulkTypeDropdown(event: MouseEvent) {
			const vm = this;
			if (!vm.instance) return;
			const trigger = event.currentTarget as HTMLElement;
			const rect = trigger.getBoundingClientRect();
			const items = (vm.jcrTypes as JcrType[]).map(t => ({
				id: t,
				label: vm.jcrTypeLabels[t] || t,
			}));
			const handle = vm.instance.popup.open({
				anchor: rect,
				placement: 'bottom-start',
				minWidth: rect.width,
				items,
			});
			const result = await handle.result;
			if (result == null) return;
			const type = String(result) as JcrType;
			for (const p of vm.scanState.properties as ScannedProperty[]) {
				if (!p._selected) continue;
				p.type = type;
				p.semantic = JCR_DEFAULT_SEMANTIC[type] || '';
			}
			vm.updateDuplicateFlags();
		},

		// Load all schema and mixin files from /etc/metadata/{schemas,mixins}/
		async loadAllSchemas() {
			const vm = this;
			vm.isLoading = true;
			vm.errorMessage = '';

			try {
				const contentService = vm.instance.api.content;

				const loadFrom = async (dirPath: string, kind: SchemaKind): Promise<SchemaDefinition[]> => {
					let dirExists = false;
					try {
						const dirNode = await contentService.getNode(dirPath);
						if (dirNode) dirExists = true;
					} catch { /* does not exist */ }
					if (!dirExists) return [];

					const out: SchemaDefinition[] = [];
					for await (const batch of contentService.listAllChildren(dirPath, 50)) {
						for (const node of batch) {
							if (!node.name?.endsWith('.json')) continue;
							if (!node.downloadUrl) continue;

							try {
								const response = await fetch(node.downloadUrl);
								if (!response.ok) continue;
								const text = await response.text();
								const data = JSON.parse(text);

								// Trust the directory we loaded from; if a file disagrees
								// with its location (legacy or hand-edited), log and force
								// the correct kind so save round-trips to the right path.
								const declaredKind = (data.kind === 'mixin' || data.kind === 'schema') ? data.kind : kind;
								if (declaredKind !== kind) {
									console.warn(`[SchemaEditor] kind mismatch: file ${node.path} declared "${declaredKind}" but lives under ${dirPath}; treating as "${kind}".`);
								}

								// Phase 1: mixins must not declare their own `mixins` field.
								const rawMixins: string[] = (kind === 'schema' && Array.isArray(data.mixins))
									? data.mixins.filter((m: any) => typeof m === 'string')
									: [];
								if (kind === 'mixin' && Array.isArray(data.mixins) && data.mixins.length > 0) {
									console.warn(`[SchemaEditor] Mixin nesting is not supported in Phase 1; ignoring "mixins" in ${node.path}.`);
								}

								const schema: SchemaDefinition = {
									_id: nextID(),
									kind,
									key: data.key || '',
									label: data.label || '',
									description: data.description || '',
									mixins: rawMixins,
									properties: (data.properties || []).map((p: any) => fromServerProperty(p)),
									_filePath: node.path,
									_isNew: false,
									_isModified: false,
									_isDeleted: false,
									_originalJSON: '',
									_expanded: false,
									_originalKey: data.key || '',
								};
								schema._originalJSON = schemaToJSON(schema);
								out.push(schema);
							} catch {
								console.warn(`[SchemaEditor] Failed to parse: ${node.path}`);
							}
						}
					}
					return out;
				};

				const [schemaItems, mixinItems] = await Promise.all([
					loadFrom(SCHEMAS_PATH, 'schema'),
					loadFrom(MIXINS_PATH, 'mixin'),
				]);

				// Mixins first so the side panel shows reusable parts above the
				// schemas that consume them; within each kind, sort by key.
				const all = [...mixinItems, ...schemaItems];
				all.sort((a, b) => {
					if (a.kind !== b.kind) return a.kind === 'mixin' ? -1 : 1;
					return a.key.localeCompare(b.key);
				});
				vm.schemas = all;
			} catch (error: any) {
				vm.errorMessage = error?.message || String(error);
			} finally {
				vm.isLoading = false;
			}
		},

		// Check if a schema has unsaved changes
		isSchemaChanged(schema: SchemaDefinition): boolean {
			if (schema._isNew) return true;
			if (schema._isDeleted) return true;
			return schemaToJSON(schema) !== schema._originalJSON;
		},

		// Select a schema (shows schema form in right pane)
		selectSchema(schemaID: string) {
			this.selection = { schemaID, propertyID: '' };
			this.validationErrors = {};
		},

		// Select a property (shows property form in right pane)
		selectProperty(schemaID: string, propertyID: string) {
			this.selection = { schemaID, propertyID };
			this.validationErrors = {};
		},

		// Toggle schema expansion
		toggleSchema(schemaID: string) {
			const schema = this.schemas.find((s: SchemaDefinition) => s._id === schemaID);
			if (schema) {
				schema._expanded = !schema._expanded;
			}
		},

		// Open the "New" menu anchored to the + button. The menu is placed at a
		// fixed position relative to the trigger (not the mouse pointer) and lets
		// the user choose between creating a schema or a mixin.
		async openAddMenu(event: MouseEvent) {
			const vm = this;
			if (!vm.instance) return;
			const trigger = event.currentTarget as HTMLElement;
			const rect = trigger.getBoundingClientRect();
			const handle = vm.instance.popup.open({
				anchor: rect,
				placement: 'bottom-start',
				items: [
					{ id: 'schema', label: 'Schema', icon: 'bi bi-collection' },
					{ id: 'mixin', label: 'Mixin', icon: 'bi bi-puzzle' },
				],
			});
			const result = await handle.result;
			if (result === 'schema') vm.addSchema();
			else if (result === 'mixin') vm.addMixin();
		},

		// Add a new schema
		addSchema() {
			const schema = createEmptySchema('schema');
			this.schemas.push(schema);
			this.selectSchema(schema._id);
		},

		// Add a new mixin (reusable property bundle, e.g. SEO, publishing).
		addMixin() {
			const mixin = createEmptySchema('mixin');
			this.schemas.push(mixin);
			this.selectSchema(mixin._id);
		},

		// Mixin keys available to attach to the currently selected schema.
		// Excludes the schema's already-attached mixins and excludes mixins
		// that have not been saved yet (no stable key to reference).
		availableMixinsFor(schema: SchemaDefinition): { key: string; label: string }[] {
			if (!schema || schema.kind !== 'schema') return [];
			const used = new Set(schema.mixins);
			return (this.schemas as SchemaDefinition[])
				.filter((s: SchemaDefinition) => s.kind === 'mixin' && !s._isDeleted && s.key && !used.has(s.key))
				.map((s: SchemaDefinition) => ({ key: s.key, label: s.label || s.key }));
		},

		// Resolve a mixin key to its in-memory definition (so the schema editor
		// can render inherited properties read-only). Returns null when the
		// referenced mixin is missing — the UI shows a dangling-reference badge.
		mixinByKey(key: string): SchemaDefinition | null {
			return (this.schemas as SchemaDefinition[])
				.find((s: SchemaDefinition) => s.kind === 'mixin' && !s._isDeleted && s.key === key) || null;
		},

		// Inherited (read-only) properties for a schema, in mixin-declaration
		// order, with the originating mixin key recorded so the UI can label
		// each row. The schema's own properties (and their overrides) are NOT
		// included here — they are rendered by the existing property list.
		inheritedProperties(schema: SchemaDefinition): { mixinKey: string; prop: PropertyDefinition; overridden: boolean }[] {
			if (!schema || schema.kind !== 'schema') return [];
			const ownKeys = new Set(
				schema.properties.filter((p: PropertyDefinition) => !p._isDeleted && p.key).map((p: PropertyDefinition) => p.key)
			);
			const seen = new Set<string>();
			const out: { mixinKey: string; prop: PropertyDefinition; overridden: boolean }[] = [];
			for (const mxKey of schema.mixins) {
				const mx = this.mixinByKey(mxKey);
				if (!mx) continue;
				for (const p of mx.properties) {
					if (p._isDeleted || !p.key) continue;
					if (seen.has(p.key)) continue;
					seen.add(p.key);
					out.push({ mixinKey: mxKey, prop: p, overridden: ownKeys.has(p.key) });
				}
			}
			return out;
		},

		addMixinReference(schema: SchemaDefinition, mixinKey: string) {
			if (!schema || schema.kind !== 'schema' || !mixinKey) return;
			if (schema.mixins.includes(mixinKey)) return;
			schema.mixins = [...schema.mixins, mixinKey];
		},

		removeMixinReference(schema: SchemaDefinition, mixinKey: string) {
			if (!schema || schema.kind !== 'schema') return;
			schema.mixins = schema.mixins.filter((k: string) => k !== mixinKey);
		},

		moveMixinReference(schema: SchemaDefinition, mixinKey: string, delta: number) {
			if (!schema || schema.kind !== 'schema') return;
			const idx = schema.mixins.indexOf(mixinKey);
			if (idx < 0) return;
			const next = idx + delta;
			if (next < 0 || next >= schema.mixins.length) return;
			const arr = [...schema.mixins];
			arr.splice(idx, 1);
			arr.splice(next, 0, mixinKey);
			schema.mixins = arr;
		},

		// How many schemas reference this mixin? Used by the mixin form to show
		// impact ("3 schemas use this mixin") and to gate deletion in future
		// phases. Returns 0 for non-mixin entries.
		mixinUsageCount(mixin: SchemaDefinition): number {
			if (!mixin || mixin.kind !== 'mixin' || !mixin.key) return 0;
			return (this.schemas as SchemaDefinition[]).filter(
				(s: SchemaDefinition) => s.kind === 'schema' && !s._isDeleted && s.mixins.includes(mixin.key)
			).length;
		},

		// Schemas that currently reference this mixin (live list, excluding
		// soft-deleted entries). Used by the mixin editor's "Used by" panel
		// and by the deletion impact dialog. Looks up by the mixin's current
		// in-memory key, so a pending rename is reflected immediately — the
		// dependent schemas under the OLD key are surfaced via _originalKey
		// instead when needed (see mixinDependentsByOriginalKey).
		mixinDependents(mixin: SchemaDefinition): SchemaDefinition[] {
			if (!mixin || mixin.kind !== 'mixin' || !mixin.key) return [];
			return (this.schemas as SchemaDefinition[]).filter(
				(s: SchemaDefinition) => s.kind === 'schema' && !s._isDeleted && s.mixins.includes(mixin.key)
			);
		},

		// Dependent schemas for the mixin's _originalKey (i.e. the key the
		// file is currently saved under). Used by the rename-cascade warning
		// so we can show "Renaming will update N schema references" before
		// the rename actually happens.
		mixinDependentsByOriginalKey(mixin: SchemaDefinition): SchemaDefinition[] {
			if (!mixin || mixin.kind !== 'mixin' || !mixin._originalKey) return [];
			return (this.schemas as SchemaDefinition[]).filter(
				(s: SchemaDefinition) => s.kind === 'schema' && !s._isDeleted && s.mixins.includes(mixin._originalKey)
			);
		},

		// True when the mixin's current `key` differs from its last-saved
		// key (and the entry actually exists on disk). Drives the rename
		// warning chip in the mixin editor.
		isMixinKeyRenamed(mixin: SchemaDefinition): boolean {
			if (!mixin || mixin.kind !== 'mixin') return false;
			if (mixin._isNew) return false;
			return mixin._originalKey !== '' && mixin._originalKey !== mixin.key;
		},

		// For each property in the mixin, count how many dependent schemas
		// shadow it via an own property of the same key. Used by the Preview
		// Impact panel and by the per-row overridden indicator.
		mixinPropertyShadowCount(mixin: SchemaDefinition, propKey: string): number {
			if (!mixin || !propKey) return 0;
			const dependents = this.mixinDependents(mixin);
			let count = 0;
			for (const s of dependents) {
				const has = s.properties.some(
					(p: PropertyDefinition) => !p._isDeleted && p.key === propKey
				);
				if (has) count++;
			}
			return count;
		},

		// Inverse of inheritedProperties(): given an own property on a schema,
		// is there a mixin in the schema's attached list that contributes a
		// same-keyed property? Returns the mixin key (for badge labelling)
		// or null. Used in the sidebar tree and in the property form so the
		// editor can flag rows like "shadows seo".
		shadowedByMixinKey(schema: SchemaDefinition, propKey: string): string | null {
			if (!schema || schema.kind !== 'schema' || !propKey) return null;
			for (const mxKey of schema.mixins) {
				const mx = this.mixinByKey(mxKey);
				if (!mx) continue;
				const hit = mx.properties.find(
					(p: PropertyDefinition) => !p._isDeleted && p.key === propKey
				);
				if (hit) return mxKey;
			}
			return null;
		},

		// Schemas that currently reference a mixin which no longer exists.
		// Used by the status bar warning and (per-schema) by the dangling
		// chip in the schema editor. Recomputed on the fly.
		schemasWithDanglingMixins(): SchemaDefinition[] {
			return (this.schemas as SchemaDefinition[]).filter((s: SchemaDefinition) => {
				if (s.kind !== 'schema' || s._isDeleted) return false;
				return s.mixins.some((mxKey: string) => !this.mixinByKey(mxKey));
			});
		},

		// Mark a schema as deleted
		markSchemaDeleted(schemaID: string) {
			const schema = this.schemas.find((s: SchemaDefinition) => s._id === schemaID);
			if (!schema) return;

			// Deleting a mixin that has live dependents is a destructive op:
			// we surface a modal so the user can pick Inline (default — keep
			// schemas' effective shape) or Detach (drop the inherited props).
			// New (never-saved) mixins skip the modal because there's nothing
			// to clean up on disk and dependents (if any) are also in-memory.
			if (schema.kind === 'mixin' && !schema._isNew) {
				const dependents = this.mixinDependents(schema);
				if (dependents.length > 0) {
					this.openDeleteMixinDialog(schema, dependents);
					return;
				}
			}

			if (schema._isNew) {
				// Remove entirely
				this.schemas = this.schemas.filter((s: SchemaDefinition) => s._id !== schemaID);
				if (this.selection.schemaID === schemaID) {
					this.selection = { schemaID: '', propertyID: '' };
				}
			} else {
				schema._isDeleted = true;
			}
		},

		// Restore a deleted schema
		restoreSchema(schemaID: string) {
			const schema = this.schemas.find((s: SchemaDefinition) => s._id === schemaID);
			if (schema) {
				schema._isDeleted = false;
			}
		},

		openDeleteMixinDialog(mixin: SchemaDefinition, dependents: SchemaDefinition[]) {
			this.deleteMixinDialog = {
				visible: true,
				mixinID: mixin._id,
				mixinKey: mixin.key,
				dependentIDs: dependents.map((s: SchemaDefinition) => s._id),
				mode: 'inline',
			};
		},

		cancelDeleteMixinDialog() {
			this.deleteMixinDialog.visible = false;
			this.deleteMixinDialog.mixinID = '';
			this.deleteMixinDialog.dependentIDs = [];
		},

		// Confirm deletion of a mixin that has dependents. "inline" copies the
		// mixin's properties into each dependent as own properties (only when
		// the dependent doesn't already shadow that key), then drops the
		// mixin reference. "detach" simply drops the reference, leaving the
		// dependent without those properties. Both paths mark the mixin as
		// deleted; the actual file I/O happens on Save All in dependency
		// order (schemas first, mixin last — see saveAll).
		confirmDeleteMixinDialog() {
			const dlg = this.deleteMixinDialog;
			const mixin = (this.schemas as SchemaDefinition[]).find(
				(s: SchemaDefinition) => s._id === dlg.mixinID
			);
			if (!mixin || mixin.kind !== 'mixin') {
				this.cancelDeleteMixinDialog();
				return;
			}
			const mixinKey = mixin.key;
			const dependents = (this.schemas as SchemaDefinition[]).filter(
				(s: SchemaDefinition) => dlg.dependentIDs.includes(s._id)
			);

			for (const dep of dependents) {
				if (dlg.mode === 'inline') {
					const ownKeys = new Set(
						dep.properties.filter((p: PropertyDefinition) => !p._isDeleted).map((p: PropertyDefinition) => p.key)
					);
					for (const src of mixin.properties) {
						if (src._isDeleted) continue;
						if (!src.key) continue;
						if (ownKeys.has(src.key)) continue;
						dep.properties.push(cloneAsOwnProperty(src));
						ownKeys.add(src.key);
					}
				}
				dep.mixins = dep.mixins.filter((k: string) => k !== mixinKey);
			}

			mixin._isDeleted = true;
			if (this.selection.schemaID === mixin._id) {
				this.selection = { schemaID: '', propertyID: '' };
			}
			this.cancelDeleteMixinDialog();
		},

		// Jump to a schema from anywhere (used by the Used-by panel and the
		// delete-mixin dialog's dependent list).
		jumpToSchema(schemaID: string) {
			this.selectSchema(schemaID);
		},

		// --------------------------------------------------------------
		// Extract to mixin (Phase 2.5)
		// --------------------------------------------------------------
		// Open the extract dialog for the given schema. The dialog lists
		// the schema's active own properties, each toggleable. Properties
		// whose key collides with one already inherited from an attached
		// mixin are surfaced with `blocked` set to true so the UI can
		// disable them — extracting would otherwise silently change the
		// schema's effective shape (mixin-mixin collisions are first-wins,
		// so the new mixin's contribution would be shadowed by the older
		// one and the override would vanish).
		openExtractDialog(schemaID: string) {
			const schema = (this.schemas as SchemaDefinition[]).find(
				(s: SchemaDefinition) => s._id === schemaID
			);
			if (!schema || schema.kind !== 'schema') return;
			this.extractDialog = {
				visible: true,
				schemaID,
				newKey: '',
				newLabel: '',
				newDescription: '',
				selectedPropertyIDs: [],
			};
		},

		cancelExtractDialog() {
			this.extractDialog.visible = false;
			this.extractDialog.schemaID = '';
			this.extractDialog.selectedPropertyIDs = [];
		},

		// Toggle a property in the extract selection.
		toggleExtractProperty(propID: string) {
			const idx = this.extractDialog.selectedPropertyIDs.indexOf(propID);
			if (idx >= 0) {
				this.extractDialog.selectedPropertyIDs.splice(idx, 1);
			} else {
				this.extractDialog.selectedPropertyIDs.push(propID);
			}
		},

		// Rows for the extract dialog: each active own property of the
		// source schema, plus a `blocked` flag for keys that would lose
		// their override on extraction (see openExtractDialog).
		extractDialogRows(): { prop: PropertyDefinition; blocked: boolean; blockedBy: string }[] {
			const schema = (this.schemas as SchemaDefinition[]).find(
				(s: SchemaDefinition) => s._id === this.extractDialog.schemaID
			);
			if (!schema) return [];
			const out: { prop: PropertyDefinition; blocked: boolean; blockedBy: string }[] = [];
			for (const p of schema.properties) {
				if (p._isDeleted) continue;
				if (!p.key) continue;
				const shadowed = this.shadowedByMixinKey(schema, p.key);
				out.push({
					prop: p,
					blocked: shadowed != null,
					blockedBy: shadowed || '',
				});
			}
			return out;
		},

		// True when the new mixin key is non-empty, syntactically valid,
		// and not already used by another mixin (including unsaved ones).
		// Schema-side uniqueness isn't a concern: mixins live in their own
		// directory so a schema may share a key.
		isExtractKeyValid(): boolean {
			const k = (this.extractDialog.newKey || '').trim();
			if (!k) return false;
			if (!/^[a-zA-Z0-9_-]+$/.test(k)) return false;
			const dupe = (this.schemas as SchemaDefinition[]).find(
				(s: SchemaDefinition) => s.kind === 'mixin' && !s._isDeleted && s.key === k
			);
			return !dupe;
		},

		canConfirmExtract(): boolean {
			if (!this.isExtractKeyValid()) return false;
			if (this.extractDialog.selectedPropertyIDs.length === 0) return false;
			// All selected properties must be non-blocked. The UI disables
			// blocked checkboxes but defensive: re-check here.
			const rows = this.extractDialogRows();
			const blockedIDs = new Set(
				rows.filter((r) => r.blocked).map((r) => r.prop._id)
			);
			return !this.extractDialog.selectedPropertyIDs.some((id: string) => blockedIDs.has(id));
		},

		// Perform the extraction:
		//   1. Build a new mixin (kind="mixin") from a deep clone of each
		//      selected property — the source schema entries will be
		//      removed, so the clones become the canonical instances.
		//   2. Mark each selected property as deleted on the source schema
		//      (soft-delete; Save All filters them out).
		//   3. Append the new mixin's key to the schema's mixins[].
		//   4. Select the source schema so the author sees the new chip
		//      and inherited-properties preview immediately.
		// Resolution order note: a NEWLY extracted mixin is appended at
		// the end of mixins[]. With first-wins mixin collisions this is
		// safe because we forbid extracting keys that already collide.
		confirmExtractDialog() {
			if (!this.canConfirmExtract()) return;
			const schema = (this.schemas as SchemaDefinition[]).find(
				(s: SchemaDefinition) => s._id === this.extractDialog.schemaID
			);
			if (!schema || schema.kind !== 'schema') {
				this.cancelExtractDialog();
				return;
			}

			const newMixin = createEmptySchema('mixin');
			newMixin.key = this.extractDialog.newKey.trim();
			newMixin.label = (this.extractDialog.newLabel || '').trim();
			newMixin.description = (this.extractDialog.newDescription || '').trim();

			// Preserve the source order of the selected properties so the
			// new mixin reads the same way as the schema did.
			const selectedSet = new Set(this.extractDialog.selectedPropertyIDs);
			for (const p of schema.properties) {
				if (!selectedSet.has(p._id)) continue;
				if (p._isDeleted) continue;
				newMixin.properties.push(cloneAsOwnProperty(p));
			}

			// Soft-delete the source-side properties. New (never saved)
			// ones can just be filtered out; existing ones need the
			// soft-delete so saveAll skips them in the new persisted JSON.
			schema.properties = schema.properties.filter((p: PropertyDefinition) => {
				if (!selectedSet.has(p._id)) return true;
				if (p._isNew) return false;
				p._isDeleted = true;
				return true;
			});

			schema.mixins = [...schema.mixins, newMixin.key];

			this.schemas.push(newMixin);

			// The mixin needs an _originalJSON baseline so isSchemaChanged
			// treats it as new-and-pending until Save All commits it.
			newMixin._originalJSON = '';

			this.cancelExtractDialog();
			this.selectSchema(schema._id);
		},

		// Add a property to the currently selected schema
		addProperty() {
			const schema = this.selectedSchema;
			if (!schema) return;
			const prop = createEmptyProperty();
			schema.properties.push(prop);
			this.selectProperty(schema._id, prop._id);
			// Ensure schema is expanded
			schema._expanded = true;
		},

		// Add a property to a specific schema (used by inline "+ Add Property" link)
		addPropertyTo(schemaID: string) {
			const schema = this.schemas.find((s: SchemaDefinition) => s._id === schemaID);
			if (!schema) return;
			const prop = createEmptyProperty();
			schema.properties.push(prop);
			this.selectProperty(schema._id, prop._id);
			schema._expanded = true;
		},

		// Mark a property as deleted
		markPropertyDeleted(schemaID: string, propertyID: string) {
			const schema = this.schemas.find((s: SchemaDefinition) => s._id === schemaID);
			if (!schema) return;

			const prop = schema.properties.find((p: PropertyDefinition) => p._id === propertyID);
			if (!prop) return;

			if (prop._isNew) {
				schema.properties = schema.properties.filter((p: PropertyDefinition) => p._id !== propertyID);
				if (this.selection.propertyID === propertyID) {
					this.selectSchema(schemaID);
				}
			} else {
				prop._isDeleted = true;
			}
		},

		// Restore a deleted property
		restoreProperty(schemaID: string, propertyID: string) {
			const schema = this.schemas.find((s: SchemaDefinition) => s._id === schemaID);
			if (!schema) return;
			const prop = schema.properties.find((p: PropertyDefinition) => p._id === propertyID);
			if (prop) {
				prop._isDeleted = false;
			}
		},

		// Field change handler - triggers reactivity for modification tracking
		onPropertyFieldChange() {
			const prop = this.selectedProperty;
			if (prop && !prop._isNew) {
				prop._isModified = true;
			}
		},

		onSchemaFieldChange() {
			// Modification is tracked via schemaToJSON comparison
		},

		// Type changed - reset editorType and type-specific constraint fields
		onTypeChange() {
			const prop = this.selectedProperty;
			if (!prop) return;
			this.onPropertyFieldChange();

			// Reset editorType: clear if no longer in available list
			const available = this.availableEditorTypes as string[];
			if (prop.uiHint.editorType && !available.includes(prop.uiHint.editorType)) {
				prop.uiHint.editorType = '';
			}

			if (prop.type !== 'STRING') {
				prop.constraints.minLength = undefined;
				prop.constraints.maxLength = undefined;
			}
			if (!isNumericJcrType(prop.type)) {
				prop.constraints.minValue = undefined;
				prop.constraints.maxValue = undefined;
				prop.constraints.decimalPlaces = undefined;
			}
		},

		isNumericType(jcrType: string): boolean {
			return isNumericJcrType(jcrType);
		},

		addChoice() {
			if (!this.selectedProperty) return;
			this.selectedProperty.constraints.choices.push({ value: '', label: '' });
			this.onPropertyFieldChange();
		},

		visibleProperties(schema: SchemaDefinition): PropertyDefinition[] {
			const q = this.schemaFilterQuery;
			if (!q) return schema.properties;
			// If the schema itself matches, show all of its properties
			if ((schema.key || '').toLowerCase().includes(q) || (schema.label || '').toLowerCase().includes(q)) {
				return schema.properties;
			}
			return schema.properties.filter((p: PropertyDefinition) =>
				(p.key || '').toLowerCase().includes(q) ||
				(p.label || '').toLowerCase().includes(q)
			);
		},
		removeChoice(index: number) {
			if (!this.selectedProperty) return;
			this.selectedProperty.constraints.choices.splice(index, 1);
			this.onPropertyFieldChange();
		},

		moveChoice(index: number, dir: number) {
			if (!this.selectedProperty) return;
			const choices = this.selectedProperty.constraints.choices;
			const target = index + dir;
			if (target < 0 || target >= choices.length) return;
			const tmp = choices[index];
			choices.splice(index, 1);
			choices.splice(target, 0, tmp);
			this.onPropertyFieldChange();
		},

		// Drag reorder for properties
		onPropertyDragStart(event: DragEvent, id: string) {
			this.dragState.draggingID = id;
			if (event.dataTransfer) {
				event.dataTransfer.effectAllowed = 'move';
			}
		},

		onPropertyDragOver(_event: DragEvent, id: string) {
			if (this.dragState.draggingID && this.dragState.draggingID !== id) {
				this.dragState.overID = id;
			}
		},

		onPropertyDragLeave() {
			this.dragState.overID = '';
		},

		onPropertyDrop(_event: DragEvent, targetID: string) {
			const fromID = this.dragState.draggingID;
			if (!fromID || fromID === targetID) return;

			const schema = this.selectedSchema;
			if (!schema) return;

			const props = schema.properties;
			const fromIdx = props.findIndex((p: PropertyDefinition) => p._id === fromID);
			const toIdx = props.findIndex((p: PropertyDefinition) => p._id === targetID);

			if (fromIdx < 0 || toIdx < 0) return;

			const [moved] = props.splice(fromIdx, 1);
			props.splice(toIdx, 0, moved);

			this.dragState.draggingID = '';
			this.dragState.overID = '';
		},

		onPropertyDragEnd() {
			this.dragState.draggingID = '';
			this.dragState.overID = '';
		},

		// Validate all schemas before save
		validateAll(): boolean {
			const errors: Record<string, string> = {};

			for (const schema of this.schemas) {
				if (schema._isDeleted) continue;
				if (!this.isSchemaChanged(schema) && !schema._isNew) continue;

				// Schema key
				if (!schema.key.trim()) {
					errors[`schema.${schema._id}.key`] = 'Required';
					this.selectSchema(schema._id);
					break;
				}
				if (!/^[a-zA-Z0-9_-]+$/.test(schema.key)) {
					errors[`schema.${schema._id}.key`] = 'Only alphanumeric, hyphens, underscores';
					this.selectSchema(schema._id);
					break;
				}

				// Check duplicate keys WITHIN THE SAME KIND. Schemas and mixins
				// live in different directories, so a schema and mixin sharing a
				// key is fine. Within a single kind, the file name (= key) must
				// be unique.
				const dupeSchema = this.schemas.find(
					(s: SchemaDefinition) => s._id !== schema._id
						&& !s._isDeleted
						&& s.kind === schema.kind
						&& s.key === schema.key
				);
				if (dupeSchema) {
					errors[`schema.${schema._id}.key`] = `Duplicate key: "${schema.key}"`;
					this.selectSchema(schema._id);
					break;
				}

				// Validate mixin references on schemas: every referenced mixin
				// must exist (and not be a deleted one). Unsaved-but-present
				// mixins are fine — saveAll persists everything in one batch.
				if (schema.kind === 'schema' && schema.mixins.length > 0) {
					const missing = schema.mixins.find((mxKey: string) => {
						const mx = (this.schemas as SchemaDefinition[]).find(
							(s: SchemaDefinition) => s.kind === 'mixin' && !s._isDeleted && s.key === mxKey
						);
						return !mx;
					});
					if (missing) {
						errors[`schema.${schema._id}.mixins`] = `Unknown mixin: "${missing}"`;
						this.selectSchema(schema._id);
						break;
					}
				}

				// Property validation
				const activeProps = schema.properties.filter((p: PropertyDefinition) => !p._isDeleted);
				let propError = false;
				for (const p of activeProps) {
					if (!p.key.trim()) {
						errors['prop.key'] = 'Required';
						this.selectProperty(schema._id, p._id);
						propError = true;
						break;
					}
					if (!p.label.trim()) {
						errors['prop.label'] = 'Required';
						this.selectProperty(schema._id, p._id);
						propError = true;
						break;
					}
					if (p.type === 'STRING' && p.constraints.pattern) {
						try {
							new RegExp(p.constraints.pattern);
						} catch (e: any) {
							errors['prop.pattern'] = 'Invalid regex: ' + (e.message || 'syntax error');
							this.selectProperty(schema._id, p._id);
							propError = true;
							break;
						}
					}
				}
				if (propError) break;

				// Check duplicate property keys within schema
				const propKeys = activeProps.map((p: PropertyDefinition) => p.key.trim()).filter((k: string) => k);
				const dupKeys = propKeys.filter((k: string, i: number) => propKeys.indexOf(k) !== i);
				if (dupKeys.length > 0) {
					const dupeProp = activeProps.find((p: PropertyDefinition) => p.key.trim() === dupKeys[0]);
					if (dupeProp) {
						errors['prop.key'] = `Duplicate key: "${dupKeys[0]}"`;
						this.selectProperty(schema._id, dupeProp._id);
					}
					break;
				}
			}

			this.validationErrors = errors;
			return Object.keys(errors).length === 0;
		},

		// Save All - save all changed schemas
		async saveAll() {
			const vm = this;
			if (!vm.validateAll()) return;

			vm.isSaving = true;
			vm.errorMessage = '';

			try {
				const contentService = vm.instance.api.content;

				// Ensure target directories exist (one of them may be unused on
				// first-time projects, but creating the dir is cheap and the
				// directory's existence is also what the loader uses to detect
				// "no files yet" gracefully).
				await vm.ensureDirectoryExists(contentService, SCHEMAS_PATH);
				await vm.ensureDirectoryExists(contentService, MIXINS_PATH);

				// Phase 2: cascade mixin key renames into dependent schemas
				// BEFORE we compute the changed-set. A rename here might be
				// the ONLY change in a dependent schema, so the cascade has
				// to run before isSchemaChanged() is consulted.
				for (const mixin of vm.schemas as SchemaDefinition[]) {
					if (mixin.kind !== 'mixin') continue;
					if (mixin._isDeleted) continue;
					if (mixin._isNew) continue;
					if (!mixin._originalKey || mixin._originalKey === mixin.key) continue;

					for (const dep of vm.schemas as SchemaDefinition[]) {
						if (dep.kind !== 'schema' || dep._isDeleted) continue;
						const idx = dep.mixins.indexOf(mixin._originalKey);
						if (idx < 0) continue;
						const next = [...dep.mixins];
						next[idx] = mixin.key;
						dep.mixins = next;
					}
				}

				// Process deletions. Order matters: delete schemas FIRST, then
				// mixins. If we deleted a mixin before a schema that still
				// referenced it, an interim re-read of the schema would emit
				// an "unknown mixin" warning. By the time we get to mixin
				// deletion, every dependent schema is gone or has had its
				// reference removed by the inline/detach flow earlier.
				const deletedSchemas = vm.schemas.filter(
					(s: SchemaDefinition) => s._isDeleted && !s._isNew && s.kind === 'schema'
				);
				const deletedMixins = vm.schemas.filter(
					(s: SchemaDefinition) => s._isDeleted && !s._isNew && s.kind === 'mixin'
				);
				for (const schema of [...deletedSchemas, ...deletedMixins]) {
					if (schema._filePath) {
						try {
							await contentService.deleteNode(schema._filePath);
						} catch (e: any) {
							console.warn(`[SchemaEditor] Failed to delete: ${schema._filePath}`, e);
						}
					}
				}

				// Save new and modified schemas
				const changedSchemas = vm.schemas.filter(
					(s: SchemaDefinition) => !s._isDeleted && vm.isSchemaChanged(s)
				);

				for (const schema of changedSchemas) {
					const jsonStr = schemaToJSON(schema);
					const fileName = schema.key + '.json';
					const dirPath = schema.kind === 'mixin' ? MIXINS_PATH : SCHEMAS_PATH;
					const filePath = dirPath + '/' + fileName;

					const encoder = new TextEncoder();
					const bytes = encoder.encode(jsonStr);
					const base64Content = btoa(String.fromCharCode(...bytes));

					if (schema._isNew) {
						console.log('[SchemaEditor] Creating new file:', filePath, 'mimeType:', MIME_TYPE);
						const result = await contentService.createFile(
							dirPath, fileName, MIME_TYPE, base64Content,
						);
						console.log('[SchemaEditor] Create result:', result);
						schema._filePath = filePath;
					} else {
						// If key (or kind) changed, the destination path changes -
						// delete the old file and create at the new location.
						if (schema._filePath !== filePath) {
							try {
								await contentService.deleteNode(schema._filePath);
							} catch { /* old file may not exist */ }
							await contentService.createFile(
								dirPath, fileName, MIME_TYPE, base64Content,
							);
							schema._filePath = filePath;
						} else {
							// Update via multipart upload
							const uploadInfo = await contentService.initiateMultipartUpload();
							const uploadID = uploadInfo.uploadId;
							try {
								await contentService.appendMultipartUploadChunk(uploadID, base64Content);
								await contentService.completeMultipartUpload(
									uploadID, dirPath, fileName, MIME_TYPE, true,
								);
							} catch (uploadError) {
								try { await contentService.abortMultipartUpload(uploadID); } catch { /* ignore */ }
								throw uploadError;
							}
						}
					}

					// Reset flags
					schema.properties = schema.properties.filter((p: PropertyDefinition) => !p._isDeleted);
					for (const p of schema.properties) {
						p._isNew = false;
						p._isModified = false;
					}
					schema._isNew = false;
					schema._isModified = false;
					schema._originalKey = schema.key;
					schema._originalJSON = schemaToJSON(schema);
				}

				// Remove deleted schemas from list
				vm.schemas = vm.schemas.filter((s: SchemaDefinition) => !s._isDeleted);

				// Clear selection if it pointed to a removed schema
				if (vm.selection.schemaID) {
					const stillExists = vm.schemas.find(
						(s: SchemaDefinition) => s._id === vm.selection.schemaID
					);
					if (!stillExists) {
						vm.selection = { schemaID: '', propertyID: '' };
					}
				}

				// Notify parent
				window.parent.postMessage({
					type: 'schema-definitions-updated',
					event: 'CHANGED',
				}, window.location.origin);

			} catch (error: any) {
				vm.errorMessage = error?.message || String(error);
			} finally {
				vm.isSaving = false;
			}
		},

		// Ensure a directory path exists, creating parent directories as needed
		async ensureDirectoryExists(contentService: any, targetPath: string) {
			const segments = targetPath.split('/').filter(Boolean);
			let current = '';
			for (const segment of segments) {
				const parent = current || '/';
				current = current + '/' + segment;
				try {
					const node = await contentService.getNode(current);
					if (node) continue;
				} catch { /* does not exist */ }
				await contentService.createFolder(parent, segment);
			}
		},

		// Cancel / close
		cancel() {
			if (this.instance) {
				this.instance.close();
			}
		},

		// Close confirmation: returns true to allow close, false to keep open
		async confirmClose(): Promise<boolean> {
			const vm = this;
			if (!vm.hasChanges) return true;

			const result = await vm.showCloseConfirmDialog();
			if (result === 'cancel') return false;

			if (result === 'save') {
				await vm.saveAll();
				// If validation/save failed, changes remain - keep window open
				if (vm.hasChanges || vm.errorMessage) return false;
			}
			return true;
		},

		showCloseConfirmDialog(): Promise<'save' | 'discard' | 'cancel'> {
			const vm = this;
			vm.closeConfirmDialog.visible = true;
			return new Promise((resolve) => {
				vm.closeConfirmDialog.resolve = resolve;
			});
		},

		onCloseConfirmDialogAction(action: 'save' | 'discard' | 'cancel') {
			const vm = this;
			if (vm.closeConfirmDialog.resolve) {
				vm.closeConfirmDialog.resolve(action);
			}
			vm.closeConfirmDialog.visible = false;
			vm.closeConfirmDialog.resolve = null;
		},

		// =====================
		// Scan Properties
		// =====================

		// Start scan: show drop zone in right pane
		startScan(schemaID: string) {
			this.scanState.phase = 'drop';
			this.scanState.schemaID = schemaID;
			this.scanState.sourceFileName = '';
			this.scanState.properties = [];
			this.scanState.overwriteExisting = false;
			this.scanState.bulkPrefix = '';
			this.scanState.selectedCase = '';
			this.scanState.isDropHighlighted = false;
			this.scanState.lastAnchorIndex = -1;
			// Select the schema but clear property selection
			this.selection = { schemaID, propertyID: '' };
		},

		// Cancel scan: return to normal editing
		cancelScan() {
			this.scanState.phase = '';
			this.scanState.properties = [];
		},

		// Drag over the scan drop zone
		onScanDragOver(event: DragEvent) {
			event.preventDefault();
			if (event.dataTransfer) {
				event.dataTransfer.dropEffect = 'copy';
			}
			this.scanState.isDropHighlighted = true;
		},

		// Drag leave the scan drop zone
		onScanDragLeave() {
			this.scanState.isDropHighlighted = false;
		},

		// File dropped on scan drop zone
		async onScanDrop(event: DragEvent) {
			event.preventDefault();
			this.scanState.isDropHighlighted = false;

			// Check for Content Browser file
			const webtopFileData = event.dataTransfer?.getData('application/x-webtop-file');
			if (webtopFileData) {
				try {
					const fileInfo = JSON.parse(webtopFileData);
					const fileName = fileInfo.name || 'unknown';
					const mimeType = fileInfo.mimeType || '';
					const ext = fileName.toLowerCase().split('.').pop() || '';
					this.scanState.sourceFileName = fileName;

					// Route 1: JSON files - parse content
					if (ext === 'json' || mimeType === 'application/json') {
						if (fileInfo.downloadURL) {
							await this.fetchAndParseFile(fileInfo.downloadURL, fileName);
							return;
						}
					}

					// Route 2: Non-JSON CMS files - read JCR node properties
					if (fileInfo.path) {
						await this.fetchNodeProperties(fileInfo.path, fileName);
						return;
					}

					// Fallback: try downloading and parsing as JSON
					if (fileInfo.downloadURL) {
						await this.fetchAndParseFile(fileInfo.downloadURL, fileName);
						return;
					}
				} catch (e) {
					console.error('[SchemaEditor] Failed to parse webtop file data:', e);
				}
			}

			// Check for local files
			const files = event.dataTransfer?.files;
			if (files && files.length > 0) {
				const file = files[0];
				this.scanState.sourceFileName = file.name;
				try {
					const text = await file.text();
					this.parseFileContent(text, file.name);
				} catch (e: any) {
					this.errorMessage = 'Failed to read file: ' + (e?.message || String(e));
				}
			}
		},

		// Fetch file from server and parse
		async fetchAndParseFile(downloadURL: string, fileName: string) {
			try {
				const response = await fetch(downloadURL);
				if (!response.ok) {
					this.errorMessage = `Failed to fetch file: ${response.status}`;
					return;
				}
				const text = await response.text();
				this.parseFileContent(text, fileName);
			} catch (e: any) {
				this.errorMessage = 'Failed to fetch file: ' + (e?.message || String(e));
			}
		},

		// Fetch JCR node properties via GraphQL and convert to scanned properties
		async fetchNodeProperties(nodePath: string, _fileName: string) {
			const vm = this;
			try {
				const contentService = vm.instance.api.content;
				const node = await contentService.getNode(nodePath);
				if (!node || !node.properties) {
					vm.errorMessage = 'No properties found for this node.';
					return;
				}

				// System property prefixes to exclude
				const systemPrefixes = ['jcr:', 'rep:', 'oak:'];
				const properties: ScannedProperty[] = [];

				for (const prop of node.properties) {
					// Skip system properties
					if (systemPrefixes.some((p: string) => prop.name.startsWith(p))) continue;

					const pv = prop.propertyValue;
					if (!pv || !pv.__typename) continue;

					const typeName = pv.__typename;
					const isArray = typeName.endsWith('Array');

					// Map __typename to property type
					let type: JcrType = 'STRING';
					if (typeName.startsWith('String')) type = 'STRING';
					else if (typeName.startsWith('Long')) type = 'LONG';
					else if (typeName.startsWith('Double')) type = 'DOUBLE';
					else if (typeName.startsWith('Boolean')) type = 'BOOLEAN';
					else if (typeName.startsWith('Date')) type = 'DATE';
					else if (typeName.startsWith('Binary')) type = 'BINARY';
					else if (typeName.startsWith('Weakreference')) type = 'WEAKREFERENCE';
					else if (typeName.startsWith('Reference')) type = 'REFERENCE';

					// Extract sample value
					let sampleValue = '';
					if (isArray) {
						if ('values' in pv && Array.isArray((pv as any).values)) {
							const vals = (pv as any).values;
							sampleValue = vals.length > 0 ? String(vals[0]) : '';
							if (vals.length > 1) sampleValue += ` (+${vals.length - 1})`;
						} else if ('paths' in pv && Array.isArray((pv as any).paths)) {
							const paths = (pv as any).paths;
							sampleValue = paths.length > 0 ? String(paths[0] || '') : '';
							if (paths.length > 1) sampleValue += ` (+${paths.length - 1})`;
						} else if (type === 'BINARY') {
							// BinaryPropertyValueArray has mimeTypes/sizes
							const mimeTypes = (pv as any).mimeTypes || [];
							sampleValue = mimeTypes.length > 0 ? `[binary: ${mimeTypes[0] || 'unknown'}]` : '[binary]';
						}
					} else {
						if ('path' in pv && (pv as any).path) {
							// Reference types: show resolved path
							sampleValue = String((pv as any).path);
						} else if ('value' in pv) {
							if (type === 'BINARY') {
								const mime = (pv as any).mimeType || 'unknown';
								const size = (pv as any).size;
								sampleValue = `[binary: ${mime}${size ? ', ' + vm.formatFileSize(size) : ''}]`;
							} else {
								sampleValue = String((pv as any).value ?? '');
							}
						}
					}

					properties.push({
						checked: true,
						key: prop.name,
						label: keyToLabel(prop.name),
						type,
						semantic: '',
						multiple: isArray,
						sampleValue: sampleValue.substring(0, 100),
						isDuplicate: false,
						_selected: false,
					});
				}

				if (properties.length === 0) {
					vm.errorMessage = 'No user properties found on this node.';
					return;
				}

				vm.updateDuplicateFlags(properties);
				vm.scanState.properties = properties;
				vm.scanState.phase = 'review';
			} catch (e: any) {
				vm.errorMessage = 'Failed to read node properties: ' + (e?.message || String(e));
			}
		},

		// Format file size for display
		formatFileSize(bytes: number): string {
			if (bytes < 1024) return bytes + ' B';
			if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB';
			return (bytes / (1024 * 1024)).toFixed(1) + ' MB';
		},

		// Parse file content and populate scanned properties
		parseFileContent(text: string, fileName: string) {
			const vm = this;
			const ext = fileName.toLowerCase().split('.').pop() || '';

			let data: any;
			if (ext === 'json' || ext === '') {
				try {
					data = JSON.parse(text);
				} catch {
					vm.errorMessage = 'Failed to parse JSON file';
					return;
				}
			} else {
				vm.errorMessage = `Unsupported file format: .${ext}. Only JSON files are supported.`;
				return;
			}

			// Extract properties from the JSON object
			const properties: ScannedProperty[] = [];

			if (typeof data === 'object' && data !== null && !Array.isArray(data)) {
				vm.extractProperties(data, '', properties);
			} else if (Array.isArray(data) && data.length > 0 && typeof data[0] === 'object') {
				// Array of objects: use first element as schema sample
				vm.extractProperties(data[0], '', properties);
			} else {
				vm.errorMessage = 'File does not contain a parseable object structure.';
				return;
			}

			// Check duplicates against existing schema
			vm.updateDuplicateFlags(properties);

			vm.scanState.properties = properties;
			vm.scanState.phase = 'review';
		},

		// Recursively extract properties from a JSON object
		extractProperties(obj: any, prefix: string, result: ScannedProperty[]) {
			for (const [key, value] of Object.entries(obj)) {
				const fullKey = prefix ? `${prefix}.${key}` : key;
				const { type, multiple } = inferType(value);

				// For nested objects, treat as JSON string type
				if (typeof value === 'object' && value !== null && !Array.isArray(value)) {
					result.push({
						checked: true,
						key: fullKey,
						label: keyToLabel(fullKey),
						type: 'STRING',
						semantic: '',
						multiple: false,
						sampleValue: formatSampleValue(value).substring(0, 100),
						isDuplicate: false,
						_selected: false,
					});
					continue;
				}

				const sampleVal = Array.isArray(value)
					? formatSampleValue(value[0]).substring(0, 100)
					: formatSampleValue(value).substring(0, 100);

				result.push({
					checked: true,
					key: fullKey,
					label: keyToLabel(fullKey),
					type,
					semantic: '',
					multiple,
					sampleValue: sampleVal,
					isDuplicate: false,
					_selected: false,
				});
			}
		},

		// Update duplicate flags against existing schema properties
		updateDuplicateFlags(properties?: ScannedProperty[]) {
			const props = properties || this.scanState.properties;
			const schema = this.schemas.find((s: SchemaDefinition) => s._id === this.scanState.schemaID);
			if (!schema) return;

			const existingKeys = new Set(
				schema.properties.filter((p: PropertyDefinition) => !p._isDeleted).map((p: PropertyDefinition) => p.key)
			);

			// Also check for duplicates within scanned properties
			const seenKeys = new Map<string, number>();
			for (let i = 0; i < props.length; i++) {
				const k = props[i].key;
				props[i].isDuplicate = existingKeys.has(k);
				// Check intra-scan duplicates
				if (seenKeys.has(k)) {
					props[i].isDuplicate = true;
					const prevIdx = seenKeys.get(k)!;
					props[prevIdx].isDuplicate = true;
				}
				seenKeys.set(k, i);
			}
		},

		// Toggle all scanned properties checked state
		toggleAllScanned(checked: boolean) {
			for (const p of this.scanState.properties) {
				p.checked = checked;
			}
		},

		// Row selection for bulk operations (with Ctrl/Shift support)
		selectScanRow(index: number, event: MouseEvent) {
			const props = this.scanState.properties as ScannedProperty[];
			if (event.shiftKey && this.scanState.lastAnchorIndex >= 0) {
				// Range select
				const from = Math.min(this.scanState.lastAnchorIndex, index);
				const to = Math.max(this.scanState.lastAnchorIndex, index);
				if (!event.ctrlKey && !event.metaKey) {
					for (const p of props) p._selected = false;
				}
				for (let i = from; i <= to; i++) {
					props[i]._selected = true;
				}
			} else if (event.ctrlKey || event.metaKey) {
				props[index]._selected = !props[index]._selected;
				this.scanState.lastAnchorIndex = index;
			} else {
				for (const p of props) p._selected = false;
				props[index]._selected = true;
				this.scanState.lastAnchorIndex = index;
			}
			// Trigger reactivity by replacing the array
			this.scanState.properties = [...props];
		},

		// Clear row selection
		clearScanSelection() {
			for (const p of this.scanState.properties) {
				p._selected = false;
			}
			this.scanState.lastAnchorIndex = -1;
		},

		// Bulk set semantic for selected rows (free text, used in scan only)
		bulkSetSemanticType(semantic: string) {
			for (const p of this.scanState.properties as ScannedProperty[]) {
				if (!p._selected) continue;
				p.semantic = semantic;
			}
		},

		// Bulk set multiple for selected rows
		bulkSetMultiple(multiple: boolean) {
			for (const p of this.scanState.properties as ScannedProperty[]) {
				if (!p._selected) continue;
				p.multiple = multiple;
			}
		},

		// Apply naming convention to selected rows
		applyScanNamingConvention(convention: 'camelCase' | 'snake_case' | 'kebab-case' | 'dot.case') {
			const converters: Record<string, (words: string[]) => string> = {
				'camelCase': toCamelCase,
				'snake_case': toSnakeCase,
				'kebab-case': toKebabCase,
				'dot.case': toDotCase,
			};
			const convert = converters[convention];
			if (!convert) return;

			for (const p of this.scanState.properties as ScannedProperty[]) {
				if (!p._selected) continue;
				// Preserve namespace prefix (e.g., "dc:")
				const colonIdx = p.key.indexOf(':');
				const prefix = colonIdx >= 0 ? p.key.substring(0, colonIdx + 1) : '';
				const words = tokenizeKey(p.key);
				p.key = prefix + convert(words);
			}
			this.updateDuplicateFlags();
		},

		// Declaratively set prefix for selected rows.
		// - Non-empty prefix: detect and replace existing prefix, or prepend if none.
		// - Empty prefix: strip existing prefix.
		// Prefix detection: portion before first ':' or '.' delimiter.
		applyDeclaredPrefix() {
			const newPrefix = this.scanState.bulkPrefix.trim();

			for (const p of this.scanState.properties as ScannedProperty[]) {
				if (!p._selected) continue;

				// Strip any existing prefix up to the first colon
				// e.g. "dc:title" -> "title" ; dot delimiters are ignored
				const bareKey = p.key.replace(/^[^:]*:/, '');

				// Ensure provided prefix ends with a colon
				let prefixToApply = '';
				if (newPrefix) {
					prefixToApply = newPrefix.endsWith(':') ? newPrefix : newPrefix + ':';
				}

				p.key = prefixToApply ? prefixToApply + bareKey : bareKey;
				p.label = keyToLabel(p.key);
			}
			this.updateDuplicateFlags();
		},

		// Handle inline edit of scanned property field
		onScanFieldChange(index: number, field: string) {
			const p = this.scanState.properties[index] as ScannedProperty;
			if (field === 'type') {
				p.semantic = JCR_DEFAULT_SEMANTIC[p.type] || '';
			}
			if (field === 'key') {
				this.updateDuplicateFlags();
			}
		},

		// Import selected (checked) properties into the schema
		importScannedProperties() {
			const vm = this;
			const schema = vm.schemas.find((s: SchemaDefinition) => s._id === vm.scanState.schemaID);
			if (!schema) return;

			const checkedProps = vm.scanState.properties.filter((p: ScannedProperty) => p.checked);
			if (checkedProps.length === 0) return;

			const existingKeys = new Set(
				schema.properties.filter((p: PropertyDefinition) => !p._isDeleted).map((p: PropertyDefinition) => p.key)
			);

			let importedCount = 0;
			let skippedCount = 0;

			for (const sp of checkedProps) {
				if (existingKeys.has(sp.key) && !vm.scanState.overwriteExisting) {
					skippedCount++;
					continue;
				}

				// If overwriting, mark the existing property as deleted
				if (existingKeys.has(sp.key) && vm.scanState.overwriteExisting) {
					const existing = schema.properties.find(
						(p: PropertyDefinition) => p.key === sp.key && !p._isDeleted
					);
					if (existing) {
						if (existing._isNew) {
							schema.properties = schema.properties.filter(
								(p: PropertyDefinition) => p._id !== existing._id
							);
						} else {
							existing._isDeleted = true;
						}
					}
				}

				// Create new property definition from scanned data
				const newProp = createEmptyProperty();
				newProp.key = sp.key;
				newProp.label = sp.label;
				newProp.type = sp.type;
				newProp.extensions.semantic = sp.semantic;
				newProp.constraints.multiple = sp.multiple;
				schema.properties.push(newProp);
				importedCount++;
			}

			// Exit scan mode
			vm.scanState.phase = '';
			vm.scanState.properties = [];

			// Expand the schema tree
			schema._expanded = true;

			// Show feedback via a temporary error message (reusing existing mechanism)
			if (skippedCount > 0) {
				vm.errorMessage = `Imported ${importedCount} properties. ${skippedCount} skipped (duplicate keys).`;
			}

			// Select the schema to show summary
			vm.selectSchema(schema._id);
		},
	},
};

// Mount the app
import { VDOM } from '@mintjamsinc/ichigojs';
VDOM.createApp(App).mount('#app');
