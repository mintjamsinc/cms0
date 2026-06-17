/**
 * Read a CMS Archive's `.cms-archive/manifest.json` directly from the picked
 * ZIP file, entirely client-side — no upload and no server round-trip — so the
 * import dialog can show reference information (where the content was exported
 * from) the instant a file is chosen.
 *
 * Only the manifest is extracted: the ZIP central directory is parsed to locate
 * the single small entry, its bytes are sliced out and (if DEFLATE-compressed)
 * inflated with the platform's native {@link DecompressionStream}. Nothing else
 * in the archive is read into memory, so this stays cheap even for large
 * archives.
 *
 * The manifest is purely informational here; any failure (an unreadable ZIP, a
 * ZIP64 archive, a browser without `DecompressionStream`, a plain non-archive
 * download) resolves to `null` and the caller simply omits the reference field.
 * The authoritative manifest validation still happens server-side at import.
 */

/** The subset of the CMS Archive manifest the dialog surfaces. */
export interface ArchiveManifestInfo {
	/** Top-level repository paths the archive was exported from. */
	roots: string[];
	/** Workspace the archive was exported from, when recorded. */
	sourceWorkspace?: string;
}

const MANIFEST_ENTRY = '.cms-archive/manifest.json';

const EOCD_SIGNATURE = 0x06054b50; // End of Central Directory
const CEN_SIGNATURE = 0x02014b50;  // Central directory file header
const LOC_SIGNATURE = 0x04034b50;  // Local file header
const ZIP64_MARKER = 0xffffffff;   // Field overflow → ZIP64 (not handled)

/**
 * Extract and parse the archive manifest, or resolve `null` when it cannot be
 * read for any reason (never throws).
 */
export async function readArchiveManifest(file: File): Promise<ArchiveManifestInfo | null> {
	try {
		const entry = await locateManifestEntry(file);
		if (!entry) return null;

		const raw = new Uint8Array(await readLocalEntryBytes(file, entry));
		const bytes = entry.compressionMethod === 0 ? raw : await inflateRaw(raw);
		if (!bytes) return null;

		const manifest = JSON.parse(new TextDecoder('utf-8').decode(bytes));
		if (!manifest || manifest.format !== 'cms-archive') return null;

		const roots = Array.isArray(manifest.roots)
			? manifest.roots.filter((r: unknown): r is string => typeof r === 'string')
			: [];
		const sourceWorkspace = manifest.source && typeof manifest.source.workspace === 'string'
			? manifest.source.workspace
			: undefined;
		return { roots, sourceWorkspace };
	} catch {
		return null;
	}
}

interface ManifestEntry {
	localHeaderOffset: number;
	compressionMethod: number;
	compressedSize: number;
}

/** Parse the central directory and return the manifest entry's coordinates. */
async function locateManifestEntry(file: File): Promise<ManifestEntry | null> {
	// The End of Central Directory record sits at the very end, after an
	// optional comment of up to 65535 bytes.
	const tailLen = Math.min(file.size, 65557);
	const tail = new DataView(await file.slice(file.size - tailLen, file.size).arrayBuffer());

	let eocd = -1;
	for (let i = tail.byteLength - 22; i >= 0; i--) {
		if (tail.getUint32(i, true) === EOCD_SIGNATURE) { eocd = i; break; }
	}
	if (eocd < 0) return null;

	const cdSize = tail.getUint32(eocd + 12, true);
	const cdOffset = tail.getUint32(eocd + 16, true);
	if (cdOffset === ZIP64_MARKER || cdSize === ZIP64_MARKER) return null; // ZIP64

	const cd = new DataView(await file.slice(cdOffset, cdOffset + cdSize).arrayBuffer());
	const decoder = new TextDecoder('utf-8');
	let p = 0;
	while (p + 46 <= cd.byteLength && cd.getUint32(p, true) === CEN_SIGNATURE) {
		const method = cd.getUint16(p + 10, true);
		const compressedSize = cd.getUint32(p + 20, true);
		const nameLen = cd.getUint16(p + 28, true);
		const extraLen = cd.getUint16(p + 30, true);
		const commentLen = cd.getUint16(p + 32, true);
		const localHeaderOffset = cd.getUint32(p + 42, true);
		const name = decoder.decode(new Uint8Array(cd.buffer, p + 46, nameLen));
		if (name === MANIFEST_ENTRY) {
			if (localHeaderOffset === ZIP64_MARKER) return null; // ZIP64
			return { localHeaderOffset, compressionMethod: method, compressedSize };
		}
		p += 46 + nameLen + extraLen + commentLen;
	}
	return null;
}

/** Slice out the manifest entry's (possibly compressed) data bytes. */
async function readLocalEntryBytes(file: File, entry: ManifestEntry): Promise<ArrayBuffer> {
	// The local header repeats the name/extra lengths, which can differ from the
	// central directory's, so the data offset must be computed from it.
	const head = new DataView(await file.slice(entry.localHeaderOffset, entry.localHeaderOffset + 30).arrayBuffer());
	if (head.getUint32(0, true) !== LOC_SIGNATURE) return new ArrayBuffer(0);
	const nameLen = head.getUint16(26, true);
	const extraLen = head.getUint16(28, true);
	const dataStart = entry.localHeaderOffset + 30 + nameLen + extraLen;
	return file.slice(dataStart, dataStart + entry.compressedSize).arrayBuffer();
}

/** Inflate raw DEFLATE bytes via the native DecompressionStream, or null. */
async function inflateRaw(bytes: Uint8Array): Promise<Uint8Array | null> {
	if (typeof DecompressionStream === 'undefined') return null;
	const stream = new Response(
		new Blob([bytes as BlobPart]).stream().pipeThrough(new DecompressionStream('deflate-raw')),
	);
	return new Uint8Array(await stream.arrayBuffer());
}
