package webtop.mail;

/**
 * Cleans the HTML body of a message before it is shown.
 *
 * This is the first of two layers. The Mail app shows the result in a sandboxed
 * iframe without scripts and with a Content-Security-Policy that blocks remote
 * content until the reader allows it, so what matters here is to drop active
 * content and to turn cid: references into the embedded images.
 */
class MailHtml {

	private MailHtml() {}

	static String sanitize(String html, Map<String, String> inlineImages) {
		String s = html;
		// Elements that run code, load other documents or change the page.
		s = s.replaceAll(/(?is)<(script|iframe|frame|frameset|object|embed|applet|form|noscript)\b[^>]*>.*?<\/\1\s*>/, '');
		s = s.replaceAll(/(?is)<\/?(script|iframe|frame|frameset|object|embed|applet|form|base|link|meta)\b[^>]*>/, '');
		// Event handler attributes.
		s = s.replaceAll(/(?is)\s+on[a-z]+\s*=\s*("[^"]*"|'[^']*'|[^\s>]+)/, '');
		// Script URLs.
		s = s.replaceAll(/(?is)(href|src|action|formaction|xlink:href)\s*=\s*(["']?)\s*(javascript|vbscript|data:text\/html)[^"'>\s]*\2/, '$1=$2#$2');

		if (inlineImages) {
			s = s.replaceAll(/(?i)cid:([^"'\s>)]+)/) { all, cid ->
				String key = cid as String;
				try {
					key = java.net.URLDecoder.decode(key.replace('+', '%2B'), 'UTF-8');
				} catch (Throwable ignore) {}
				return inlineImages[key] ?: inlineImages[cid as String] ?: all;
			};
		}
		return s;
	}

}
