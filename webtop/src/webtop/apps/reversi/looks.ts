/**
 * Board themes and disc faces.
 *
 * A board theme is a set of CSS custom properties applied to the board
 * (style.css reads them), so a new theme is one more entry here. A disc
 * face is either drawn in CSS (a gradient plus the colour of its rim) or an
 * image file under assets/discs/, e.g. an SVG exported from Illustrator.
 * Images are shown as a CSS background, so an SVG's own styles and ids
 * cannot clash with the page or with the other face.
 *
 * The computer game always uses the black and white pair; in a game between
 * two people each player picks a face, and the two must differ.
 */

export interface BoardTheme {
	id: string;
	/** Fallback label; the i18n key is app.reversi.board.<id>. */
	label: string;
	vars: Record<string, string>;
}

export interface DiscFace {
	id: string;
	/** Fallback label; the i18n key is app.reversi.disc.<id>. */
	label: string;
	/** CSS background of the disc's face. */
	background: string;
	/** Rim and drop shadow colour under the disc. */
	rim: string;
	/** Colours for the effects when this face is played. */
	sparks: string[];
}

export const BOARD_THEMES: BoardTheme[] = [
	{
		id: 'ichigo',
		label: 'Strawberry mint',
		vars: {
			'--rv-backdrop': 'linear-gradient(180deg, #ffe1ec 0%, #fff0ea 55%, #fff8f3 100%)',
			'--rv-frame': '#dff3e8',
			'--rv-frame-edge': '#c4e6d4',
			'--rv-cell': '#eaf8f0',
			'--rv-cell-hover': '#f7fdfa',
			'--rv-mark': '#ed5a77',
		},
	},
	{
		id: 'sakura',
		label: 'Sakura',
		vars: {
			'--rv-backdrop': 'linear-gradient(180deg, #fde7f0 0%, #fff4f7 60%, #fffafc 100%)',
			'--rv-frame': '#f9d7e3',
			'--rv-frame-edge': '#f0bdd0',
			'--rv-cell': '#fdebf2',
			'--rv-cell-hover': '#fff7fa',
			'--rv-mark': '#c2577c',
		},
	},
	{
		id: 'sky',
		label: 'Sky',
		vars: {
			'--rv-backdrop': 'linear-gradient(180deg, #dceaff 0%, #ecf3ff 60%, #f6faff 100%)',
			'--rv-frame': '#d3e4fb',
			'--rv-frame-edge': '#b9d0f2',
			'--rv-cell': '#e6f0fe',
			'--rv-cell-hover': '#f5f9ff',
			'--rv-mark': '#4f6fe0',
		},
	},
	{
		id: 'forest',
		label: 'Forest',
		vars: {
			'--rv-backdrop': 'linear-gradient(180deg, #e5f1e0 0%, #f3f8ee 60%, #fbfaf2 100%)',
			'--rv-frame': '#4f9a6b',
			'--rv-frame-edge': '#3f8259',
			'--rv-cell': '#63ad7d',
			'--rv-cell-hover': '#72ba8b',
			'--rv-mark': '#fff4d6',
		},
	},
];

function cssDisc(id: string, label: string, light: string, base: string, rim: string, sparks: string[]): DiscFace {
	return { id, label, background: `radial-gradient(circle at 35% 30%, ${light}, ${base} 62%)`, rim, sparks };
}

function imageDisc(id: string, label: string, file: string, rim: string, sparks: string[]): DiscFace {
	return { id, label, background: `url("assets/discs/${file}") center / 100% 100% no-repeat`, rim, sparks };
}

export const DISC_FACES: DiscFace[] = [
	cssDisc('black', 'Black', '#8a6168', '#4d2c31', '#3a2025', ['#ED5A77', '#FF9EBB', '#FFC857']),
	cssDisc('white', 'White', '#ffffff', '#fff1e8', '#f0d9cf', ['#FFC857', '#7FB8F0', '#57B894']),
	imageDisc('strawberry', 'Strawberry', 'strawberry.svg', '#b8304f', ['#ED5A77', '#57B894', '#FFC857']),
	imageDisc('chick', 'Chick', 'chick.svg', '#d9a530', ['#FFC857', '#FF9EBB', '#7FB8F0']),
	cssDisc('mint', 'Mint', '#c9f2df', '#57b894', '#3f9677', ['#57B894', '#7FB8F0', '#FFF4D6']),
	cssDisc('sky', 'Sky', '#d6e6ff', '#6b8bf5', '#4f6fe0', ['#7FB8F0', '#B79CF2', '#FFF4D6']),
	cssDisc('peach', 'Peach', '#ffe0cc', '#f7a072', '#d97f52', ['#F7A072', '#FFC857', '#FF9EBB']),
	cssDisc('lilac', 'Lilac', '#efe6ff', '#b79cf2', '#9277d6', ['#B79CF2', '#FF9EBB', '#7FB8F0']),
];

export const DEFAULT_THEME = 'ichigo';
/** The pair for the computer game, and the default pair between two people. */
export const DEFAULT_FACES: [string, string] = ['black', 'white'];

export function findTheme(id: string): BoardTheme {
	return BOARD_THEMES.find(t => t.id === id) || BOARD_THEMES[0];
}

export function findFace(id: string): DiscFace {
	return DISC_FACES.find(f => f.id === id) || DISC_FACES[0];
}
