import { cp, mkdir } from 'node:fs/promises';
import { extname } from 'node:path';

const SRC_DIR = new URL('../src', import.meta.url);
const DEST_DIR = new URL('../dist', import.meta.url);

await mkdir(DEST_DIR, { recursive: true });
	await cp(SRC_DIR, DEST_DIR, {
	recursive: true,
	filter: src => extname(src) !== '.ts'
	});
