/**
 * Board themes and player colours.
 *
 * A board theme is a set of CSS custom properties applied to the board
 * (style.css reads them), so a new theme is one more entry here; the ids
 * are those of the Reversi boards, in the same tones. A player colour is
 * the ink of the numbers the player writes, the tint of the cells the
 * player takes in a game of territory, and the colours of the effects. In a
 * game between two players each picks a colour, and the two must differ.
 */

export interface BoardTheme {
	id: string;
	/** Fallback label; the i18n key is app.numberplace.board.<id>. */
	label: string;
	vars: Record<string, string>;
}

export interface PlayerColor {
	id: string;
	/** Fallback label; the i18n key is app.numberplace.color.<id>. */
	label: string;
	/** The numbers the player writes. */
	ink: string;
	/** A cell the player took. */
	tint: string;
	/** The swatch: a soft ball in the colour. */
	swatch: string;
	/** Colours for the effects when this player fills a cell. */
	sparks: string[];
}

export const BOARD_THEMES: BoardTheme[] = [
	{
		id: 'ichigo',
		label: 'Strawberry mint',
		vars: {
			'--np-backdrop': 'linear-gradient(180deg, #ffe1ec 0%, #fff0ea 55%, #fff8f3 100%)',
			'--np-frame': '#c4e6d4',
			'--np-frame-edge': '#a9d9bf',
			'--np-cell': '#fbfffd',
			'--np-cell-alt': '#f1faf5',
			'--np-cell-hover': '#ffffff',
			'--np-given': '#4d2c31',
			'--np-mark': '#ed5a77',
			'--np-peer': 'rgb(237 90 119 / 0.07)',
			'--np-same': 'rgb(237 90 119 / 0.18)',
		},
	},
	{
		id: 'sakura',
		label: 'Sakura',
		vars: {
			'--np-backdrop': 'linear-gradient(180deg, #fde7f0 0%, #fff4f7 60%, #fffafc 100%)',
			'--np-frame': '#f0bdd0',
			'--np-frame-edge': '#e6a5bd',
			'--np-cell': '#fffbfd',
			'--np-cell-alt': '#fdf0f5',
			'--np-cell-hover': '#ffffff',
			'--np-given': '#5a2f3f',
			'--np-mark': '#c2577c',
			'--np-peer': 'rgb(194 87 124 / 0.07)',
			'--np-same': 'rgb(194 87 124 / 0.18)',
		},
	},
	{
		id: 'sky',
		label: 'Sky',
		vars: {
			'--np-backdrop': 'linear-gradient(180deg, #dceaff 0%, #ecf3ff 60%, #f6faff 100%)',
			'--np-frame': '#b9d0f2',
			'--np-frame-edge': '#a1bde8',
			'--np-cell': '#fbfdff',
			'--np-cell-alt': '#eef4fe',
			'--np-cell-hover': '#ffffff',
			'--np-given': '#2e3a5c',
			'--np-mark': '#4f6fe0',
			'--np-peer': 'rgb(79 111 224 / 0.07)',
			'--np-same': 'rgb(79 111 224 / 0.18)',
		},
	},
	{
		id: 'forest',
		label: 'Forest',
		vars: {
			'--np-backdrop': 'linear-gradient(180deg, #e5f1e0 0%, #f3f8ee 60%, #fbfaf2 100%)',
			'--np-frame': '#4f9a6b',
			'--np-frame-edge': '#3f8259',
			'--np-cell': '#fbfdf6',
			'--np-cell-alt': '#eef6e6',
			'--np-cell-hover': '#ffffff',
			'--np-given': '#2f4a37',
			'--np-mark': '#d9822b',
			'--np-peer': 'rgb(79 154 107 / 0.09)',
			'--np-same': 'rgb(217 130 43 / 0.2)',
		},
	},
];

function color(id: string, label: string, ink: string, tint: string, light: string, sparks: string[]): PlayerColor {
	return { id, label, ink, tint, swatch: `radial-gradient(circle at 35% 30%, ${light}, ${ink} 62%)`, sparks };
}

export const PLAYER_COLORS: PlayerColor[] = [
	color('strawberry', 'Strawberry', '#e0456a', 'rgb(237 90 119 / 0.2)', '#ff9db2', ['#ED5A77', '#FF9EBB', '#FFC857']),
	color('mint', 'Mint', '#2f9a74', 'rgb(87 184 148 / 0.24)', '#c9f2df', ['#57B894', '#7FB8F0', '#FFF4D6']),
	color('sky', 'Sky', '#4f6fe0', 'rgb(107 139 245 / 0.2)', '#d6e6ff', ['#7FB8F0', '#B79CF2', '#FFF4D6']),
	color('chick', 'Chick', '#c98a0b', 'rgb(255 200 87 / 0.32)', '#fff3b8', ['#FFC857', '#FF9EBB', '#7FB8F0']),
	color('peach', 'Peach', '#d9703f', 'rgb(247 160 114 / 0.26)', '#ffe0cc', ['#F7A072', '#FFC857', '#FF9EBB']),
	color('lilac', 'Lilac', '#7c5cc9', 'rgb(183 156 242 / 0.26)', '#efe6ff', ['#B79CF2', '#FF9EBB', '#7FB8F0']),
];

export const DEFAULT_THEME = 'ichigo';
/** The user's colour and the other player's, unless chosen. */
export const DEFAULT_COLORS: [string, string] = ['strawberry', 'mint'];

export function findTheme(id: string): BoardTheme {
	return BOARD_THEMES.find(t => t.id === id) || BOARD_THEMES[0];
}

export function findColor(id: string): PlayerColor {
	return PLAYER_COLORS.find(c => c.id === id) || PLAYER_COLORS[0];
}

/** A colour that differs from the given one, for the other player. */
export function otherColor(id: string): string {
	return id === DEFAULT_COLORS[1] ? DEFAULT_COLORS[0] : DEFAULT_COLORS[1];
}
