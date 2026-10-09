/**
 * Pomodoro: the pixel-art houseplant that grows with the minutes focused.
 *
 * Eight stages, from a pot of soil to a plant in full bloom, each reached
 * after so many focus minutes in all. The plant only grows: missing a day
 * takes nothing away. Each stage is drawn from text sprites (one character
 * per pixel, see PALETTE) onto a canvas scaled with crisp pixels.
 */

export const SPRITE_WIDTH = 20;
export const SPRITE_HEIGHT = 24;

export interface PlantStage {
	key: string;            // name under app.pomodoro.plant.<key>
	label: string;          // English fallback
	minutes: number;        // focus minutes in all to reach it
}

export const STAGES: PlantStage[] = [
	{ key: 'seed', label: 'Seed', minutes: 0 },
	{ key: 'sprout', label: 'Sprout', minutes: 25 },
	{ key: 'leaves', label: 'First leaves', minutes: 100 },
	{ key: 'young', label: 'Young plant', minutes: 200 },
	{ key: 'grown', label: 'Grown plant', minutes: 350 },
	{ key: 'bud', label: 'Bud', minutes: 550 },
	{ key: 'flower', label: 'Flower', minutes: 800 },
	{ key: 'bloom', label: 'Full bloom', minutes: 1200 },
];

export function stageIndex(focusMinutes: number): number {
	let i = 0;
	while (i + 1 < STAGES.length && focusMinutes >= STAGES[i + 1].minutes) i++;
	return i;
}

/** Minutes still to focus before the next stage, or null at the last. */
export function minutesToNext(focusMinutes: number): number | null {
	const i = stageIndex(focusMinutes);
	return i + 1 < STAGES.length ? STAGES[i + 1].minutes - focusMinutes : null;
}

/** 0..1 of the way from this stage to the next (1 at the last). */
export function stageProgress(focusMinutes: number): number {
	const i = stageIndex(focusMinutes);
	if (i + 1 >= STAGES.length) return 1;
	const from = STAGES[i].minutes;
	return (focusMinutes - from) / (STAGES[i + 1].minutes - from);
}

const PALETTE: Record<string, string> = {
	R: '#d9804f', // pot rim
	P: '#c8693a', // pot
	p: '#9c4f2a', // pot shade
	S: '#5b3a29', // soil
	s: '#d9b47c', // seed (light on the soil)
	T: '#3f8f43', // stem
	G: '#4caf50', // leaf
	g: '#2e7d32', // leaf shade
	L: '#8bd38f', // leaf light
	B: '#f48fb1', // bud
	F: '#f06292', // petal
	f: '#c2185b', // petal shade
	Y: '#ffd54f', // flower centre
};

const POT = [
	'....SSSSSSSSSSSS....',
	'..RRRRRRRRRRRRRRRR..',
	'..pRRRRRRRRRRRRRRp..',
	'...PPPPPPPPPPPPPPp..',
	'...PPPPPPPPPPPPPPp..',
	'....PPPPPPPPPPPPp...',
	'....PPPPPPPPPPPPp...',
	'.....pppppppppp.....',
];

// Rows above the soil (0..15), top first; rows not given are empty.
const GROWN = [
	'',
	'',
	'........GGGG........',
	'......GGLLLLGG......',
	'.......gGGGGg.......',
	'...GGG...TT...GGG...',
	'..GLLGG..TT..GGLLG..',
	'...gGGGG.TT.GGGGg...',
	'.........TT.........',
	'.GGG.....TT.....GGG.',
	'GLLGGG...TT...GGGLLG',
	'.gGGGGG..TT..GGGGGg.',
	'...gGGGGGTTGGGGGg...',
	'.........TT.........',
	'....LGG..TT..GGL....',
	'.....GGGGTTGGGG.....',
];

const withTop = (rows: string[], top: string[]) => [...top, ...rows.slice(top.length)];

const PLANTS: string[][] = [
	// seed
	['', '', '', '', '', '', '', '', '', '', '', '', '', '', '', '.........ss.........'],
	// sprout
	['', '', '', '', '', '', '', '', '', '', '', '', '',
		'......GG....GG......',
		'.......GGTTGG.......',
		'.........TT.........'],
	// first leaves
	['', '', '', '', '', '', '', '', '',
		'.......GG..GG.......',
		'........GTTG........',
		'.........TT.........',
		'....LGG..TT..GGL....',
		'.....GGGGTTGGGG.....',
		'.........TT.........',
		'.........TT.........'],
	// young plant
	['', '', '', '', '', '',
		'........GGGG........',
		'.......GGLLGG.......',
		'....GG..GTTG..GG....',
		'...GGLG..TT..GLGG...',
		'....GGGG.TT.GGGG....',
		'.........TT.........',
		'..LGGG...TT...GGGL..',
		'...gGGGG.TT.GGGGg...',
		'.....gGGGTTGGGg.....',
		'.........TT.........'],
	// grown plant
	GROWN,
	// bud
	withTop(GROWN, [
		'.........BB.........',
		'........BffB........',
		'........gTTg........',
	]),
	// flower
	withTop(GROWN, [
		'.......F.FF.F.......',
		'......FFFYYFFF......',
		'.......FfYYfF.......',
		'......GGfFFfGG......',
	]),
	// full bloom: flowers on the side branches too
	withTop(GROWN, [
		'.......F.FF.F.......',
		'......FFFYYFFF......',
		'.......FfYYfF.......',
		'......GGfFFfGG......',
		'.......gGGGGg.......',
		'...FYF...TT...FYF...',
		'..GLLGG..TT..GGLLG..',
		'...gGGGG.TT.GGGGg...',
		'.........TT.........',
		'.FYF.....TT.....FYF.',
	]),
];

/** The rows of a stage's picture, plant above pot, SPRITE_HEIGHT rows of SPRITE_WIDTH. */
export function spriteRows(stage: number): string[] {
	const plant = PLANTS[Math.max(0, Math.min(PLANTS.length - 1, stage))];
	const above: string[] = [];
	for (let y = 0; y < SPRITE_HEIGHT - POT.length; y++) above.push((plant[y] || '').padEnd(SPRITE_WIDTH, '.'));
	return [...above, ...POT];
}

/** Draw a stage onto the canvas, `scale` device-independent pixels per sprite pixel. */
export function drawPlant(canvas: HTMLCanvasElement, stage: number, scale: number): void {
	const dpr = window.devicePixelRatio || 1;
	const px = Math.max(1, Math.round(scale * dpr));
	canvas.width = SPRITE_WIDTH * px;
	canvas.height = SPRITE_HEIGHT * px;
	canvas.style.width = `${SPRITE_WIDTH * scale}px`;
	canvas.style.height = `${SPRITE_HEIGHT * scale}px`;
	const g = canvas.getContext('2d');
	if (!g) return;
	g.clearRect(0, 0, canvas.width, canvas.height);
	spriteRows(stage).forEach((row, y) => {
		for (let x = 0; x < SPRITE_WIDTH; x++) {
			const color = PALETTE[row[x]];
			if (!color) continue;
			g.fillStyle = color;
			g.fillRect(x * px, y * px, px, px);
		}
	});
}
