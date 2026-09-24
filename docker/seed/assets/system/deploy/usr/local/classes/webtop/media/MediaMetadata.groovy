package webtop.media;

import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.xml.sax.helpers.DefaultHandler;

/**
 * The orientation of an image or video file, kept in properties of the file so
 * the Content Browser can show it and the search index can count it:
 *
 *   mi:orientation   portrait / landscape / square / panorama
 *   mi:width         pixels, after the EXIF rotation is applied
 *   mi:height        pixels, after the EXIF rotation is applied
 *
 * Set by the media-metadata route when a file is added or its content changes
 * (etc/eip/routes/webtop/media-metadata.xml), in a context of the user who made
 * the change, so the route never writes what that user could not. The
 * dimensions come from Tika: the EXIF / TIFF tags of a JPEG, the header of a
 * PNG or GIF, the track header of an MP4. A file the parser cannot read keeps
 * whatever it had.
 */
class MediaMetadata {

	static final String ORIENTATION = 'mi:orientation';
	static final String WIDTH = 'mi:width';
	static final String HEIGHT = 'mi:height';
	static final List<String> PROPERTIES = [ORIENTATION, WIDTH, HEIGHT];

	/** Sides within this fraction of each other count as square. */
	static final double SQUARE_TOLERANCE = 0.05;
	/** A long side at least this many times the short side counts as panorama. */
	static final double PANORAMA_RATIO = 2.0;
	/** Larger files are left alone: the whole file may have to be read to find its header. */
	static final long MAX_BYTES = 512L * 1024 * 1024;

	/** The metadata keys the dimensions are read from, in order of preference. */
	static final List<String> WIDTH_KEYS = ['tiff:ImageWidth', 'Image Width', 'width'];
	static final List<String> HEIGHT_KEYS = ['tiff:ImageLength', 'Image Height', 'height'];
	static final List<String> ORIENTATION_KEYS = ['tiff:Orientation', 'Orientation'];

	def context;
	def session;
	def log;

	protected MediaMetadata(context) {
		this.context = context;
		this.session = context.session;
		this.log = context.getAttribute('log');
	}

	static MediaMetadata create(context) {
		return new MediaMetadata(context);
	}

	/**
	 * Whether a node event is one to act on: a file that was added, or whose
	 * content or MIME type changed. A change to other properties, including the
	 * ones this class writes, is not, which is what keeps the route from
	 * answering its own update with another.
	 */
	static boolean isContentChange(String topic, String type, String[] properties) {
		if (type != 'nt:file') {
			return false;
		}
		if (topic.endsWith('/ADDED')) {
			return true;
		}
		if (!topic.endsWith('/CHANGED')) {
			return false;
		}
		return properties != null && properties.any { it == 'jcr:data' || it == 'jcr:mimeType' };
	}

	static boolean isMediaType(String mimeType) {
		String type = (mimeType ?: '').toLowerCase();
		return type.startsWith('image/') || type.startsWith('video/');
	}

	/** Classifies a size: the long side against the short side, then which is longer. */
	static String orientationOf(long width, long height) {
		if (width <= 0 || height <= 0) {
			return null;
		}
		double longSide = Math.max(width, height);
		double shortSide = Math.min(width, height);
		if (longSide / shortSide >= PANORAMA_RATIO) {
			return 'panorama';
		}
		if ((longSide - shortSide) / shortSide <= SQUARE_TOLERANCE) {
			return 'square';
		}
		return width > height ? 'landscape' : 'portrait';
	}

	/**
	 * Reads the file at {@code path} and stores its orientation and size. A
	 * file that is no longer a media file loses the three properties. Returns
	 * whether anything was written.
	 */
	boolean update(String path) {
		def file = session.getResource(path);
		if (!file.exists() || file.isCollection()) {
			return false;
		}

		if (!isMediaType(file.getContentType())) {
			return clear(file);
		}
		if (file.getContentLength() > MAX_BYTES) {
			log?.debug("Media metadata skipped, file too large: ${path}".toString());
			return false;
		}

		Map dimensions = readDimensions(file);
		if (dimensions == null) {
			return false;
		}
		String orientation = orientationOf(dimensions.width as long, dimensions.height as long);
		if (orientation == null) {
			return false;
		}

		if (current(file, ORIENTATION) == orientation &&
				current(file, WIDTH) == (dimensions.width as String) &&
				current(file, HEIGHT) == (dimensions.height as String)) {
			return false;
		}
		file.setProperty(ORIENTATION, orientation);
		file.setProperty(WIDTH, dimensions.width as long);
		file.setProperty(HEIGHT, dimensions.height as long);
		session.commit();
		return true;
	}

	/** Removes the properties of a file that is not (or no longer) a media file. */
	private boolean clear(file) {
		boolean changed = false;
		PROPERTIES.each { name ->
			if (file.hasProperty(name)) {
				file.removeProperty(name);
				changed = true;
			}
		};
		if (changed) {
			session.commit();
		}
		return changed;
	}

	private static String current(file, String name) {
		return file.hasProperty(name) ? file.getProperty(name).getString() : null;
	}

	/**
	 * The pixel size of the file's content, with the EXIF rotation applied
	 * (orientations 5 to 8 are rotated a quarter turn, so their sides swap).
	 * Null when the parser finds no size, cannot read the file, or the file is
	 * empty.
	 */
	private Map readDimensions(file) {
		Metadata metadata = new Metadata();
		metadata.set(Metadata.CONTENT_TYPE, file.getContentType());
		try {
			file.getContentAsStream().withCloseable { input ->
				new AutoDetectParser().parse(input, new DefaultHandler(), metadata, new ParseContext());
			};
		} catch (Throwable ex) {
			log?.debug("Media metadata not read: ${file.getPath()}: ${ex.message}".toString());
			return null;
		}

		Long width = firstNumber(metadata, WIDTH_KEYS);
		Long height = firstNumber(metadata, HEIGHT_KEYS);
		if (width == null || height == null || width <= 0 || height <= 0) {
			return null;
		}
		Long exif = firstNumber(metadata, ORIENTATION_KEYS);
		if (exif != null && exif >= 5 && exif <= 8) {
			return [width: height, height: width];
		}
		return [width: width, height: height];
	}

	/** The first key with a value, as its leading integer ("1200 pixels" is 1200). */
	private static Long firstNumber(Metadata metadata, List<String> keys) {
		for (String key : keys) {
			String value = metadata.get(key);
			if (!value) {
				continue;
			}
			def m = (value.trim() =~ /^(\d+)/);
			if (m.find()) {
				return Long.parseLong(m.group(1));
			}
		}
		return null;
	}
}
