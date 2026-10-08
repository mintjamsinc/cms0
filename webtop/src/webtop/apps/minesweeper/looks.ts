/**
 * Board themes and player colours.
 *
 * A board theme is a set of CSS custom properties applied to the board
 * (style.css reads them), so a new theme is one more entry here; the ids
 * are those of the Reversi and Number Place boards, in the same tones. A
 * covered cell is a soft tile, an open one a flat cell of the board. A
 * player colour is the flag the player puts down, the tint of the mines
 * the player takes in a game of territory, and the colours of the effects.
 * In a game between two players each picks a colour, and the two must
 * differ.
 */

export interface BoardTheme {
	id: string;
	/** Fallback label; the i18n key is app.minesweeper.board.<id>. */
	label: string;
	vars: Record<string, string>;
}

export interface PlayerColor {
	id: string;
	/** Fallback label; the i18n key is app.minesweeper.color.<id>. */
	label: string;
	/** The player's flags. */
	ink: string;
	/** A mine the player took. */
	tint: string;
	/** The swatch: a soft ball in the colour. */
	swatch: string;
	/** Colours for the effects when this player opens or takes a cell. */
	sparks: string[];
}

export const BOARD_THEMES: BoardTheme[] = [
	{
		id: 'ichigo',
		label: 'Strawberry mint',
		vars: {
			'--ms-backdrop': 'linear-gradient(180deg, #ffe1ec 0%, #fff0ea 55%, #fff8f3 100%)',
			'--ms-frame': '#c4e6d4',
			'--ms-frame-edge': '#a9d9bf',
			'--ms-tile': '#9fdbbd',
			'--ms-tile-hi': '#c6eed9',
			'--ms-tile-edge': '#78c19c',
			'--ms-tile-hover': '#b1e4c9',
			'--ms-open': '#fbfffd',
			'--ms-open-alt': '#f3faf6',
			'--ms-ink': '#4d2c31',
			'--ms-mark': '#ed5a77',
		},
	},
	{
		id: 'sakura',
		label: 'Sakura',
		vars: {
			'--ms-backdrop': 'linear-gradient(180deg, #fde7f0 0%, #fff4f7 60%, #fffafc 100%)',
			'--ms-frame': '#f0bdd0',
			'--ms-frame-edge': '#e6a5bd',
			'--ms-tile': '#f5bcd0',
			'--ms-tile-hi': '#fcdbe6',
			'--ms-tile-edge': '#e298b2',
			'--ms-tile-hover': '#f8cada',
			'--ms-open': '#fffbfd',
			'--ms-open-alt': '#fdf2f6',
			'--ms-ink': '#5a2f3f',
			'--ms-mark': '#c2577c',
		},
	},
	{
		id: 'sky',
		label: 'Sky',
		vars: {
			'--ms-backdrop': 'linear-gradient(180deg, #dceaff 0%, #ecf3ff 60%, #f6faff 100%)',
			'--ms-frame': '#b9d0f2',
			'--ms-frame-edge': '#a1bde8',
			'--ms-tile': '#adc7f2',
			'--ms-tile-hi': '#d3e2fb',
			'--ms-tile-edge': '#8aa8e2',
			'--ms-tile-hover': '#bfd3f6',
			'--ms-open': '#fbfdff',
			'--ms-open-alt': '#f0f5fe',
			'--ms-ink': '#2e3a5c',
			'--ms-mark': '#4f6fe0',
		},
	},
	{
		id: 'forest',
		label: 'Forest',
		vars: {
			'--ms-backdrop': 'linear-gradient(180deg, #e5f1e0 0%, #f3f8ee 60%, #fbfaf2 100%)',
			'--ms-frame': '#4f9a6b',
			'--ms-frame-edge': '#3f8259',
			'--ms-tile': '#79b98a',
			'--ms-tile-hi': '#a2d2ae',
			'--ms-tile-edge': '#5a9a6c',
			'--ms-tile-hover': '#8bc69a',
			'--ms-open': '#fbfdf6',
			'--ms-open-alt': '#f0f7e9',
			'--ms-ink': '#2f4a37',
			'--ms-mark': '#d9822b',
		},
	},
];

function color(id: string, label: string, ink: string, tint: string, light: string, sparks: string[]): PlayerColor {
	return { id, label, ink, tint, swatch: `radial-gradient(circle at 35% 30%, ${light}, ${ink} 62%)`, sparks };
}

export const PLAYER_COLORS: PlayerColor[] = [
	color('strawberry', 'Strawberry', '#e0456a', 'rgb(237 90 119 / 0.28)', '#ff9db2', ['#ED5A77', '#FF9EBB', '#FFC857']),
	color('mint', 'Mint', '#2f9a74', 'rgb(87 184 148 / 0.32)', '#c9f2df', ['#57B894', '#7FB8F0', '#FFF4D6']),
	color('sky', 'Sky', '#4f6fe0', 'rgb(107 139 245 / 0.28)', '#d6e6ff', ['#7FB8F0', '#B79CF2', '#FFF4D6']),
	color('chick', 'Chick', '#c98a0b', 'rgb(255 200 87 / 0.4)', '#fff3b8', ['#FFC857', '#FF9EBB', '#7FB8F0']),
	color('peach', 'Peach', '#d9703f', 'rgb(247 160 114 / 0.34)', '#ffe0cc', ['#F7A072', '#FFC857', '#FF9EBB']),
	color('lilac', 'Lilac', '#7c5cc9', 'rgb(183 156 242 / 0.34)', '#efe6ff', ['#B79CF2', '#FF9EBB', '#7FB8F0']),
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
