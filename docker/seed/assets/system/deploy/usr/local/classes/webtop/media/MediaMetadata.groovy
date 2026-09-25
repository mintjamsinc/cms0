package webtop.media;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.xml.sax.helpers.DefaultHandler;

/**
 * What the Content Browser shows of an image, video or audio file without
 * opening it, kept in properties of the file so the list can read them and
 * the search index can count them:
 *
 *   mi:orientation        portrait / landscape / square / panorama (image, video)
 *   mi:width              pixels, after the EXIF rotation is applied (image, video)
 *   mi:height             pixels, after the EXIF rotation is applied (image, video)
 *   mi:thumbnail          a JPEG of at most THUMBNAIL_SIZE pixels on its long side:
 *                         the image itself, or the cover art embedded in an audio
 *                         file (ID3v2 APIC, MP4 covr, FLAC PICTURE)
 *   mi:thumbnailVersion   the file's jcr:lastModified, in epoch milliseconds, at
 *                         the time the thumbnail was made; the list puts it in the
 *                         thumbnail's URL so a remade thumbnail is never served from
 *                         the browser's cache
 *
 * Set by the media-metadata route when a file is added or its content changes
 * (etc/eip/routes/webtop/media-metadata.xml), in a context of the user who made
 * the change, so the route never writes what that user could not. The
 * dimensions come from Tika: the EXIF / TIFF tags of a JPEG, the header of a
 * PNG or GIF, the track header of an MP4. The thumbnail is decoded with ImageIO
 * (JPEG, PNG, GIF, BMP; WebP and HEIC are not decoded, and the list shows such
 * an image itself). A video gets no thumbnail: the list shows its first frame
 * through the browser. A file the parser cannot read keeps whatever it had.
 */
class MediaMetadata {

	static final String ORIENTATION = 'mi:orientation';
	static final String WIDTH = 'mi:width';
	static final String HEIGHT = 'mi:height';
	static final String THUMBNAIL = 'mi:thumbnail';
	static final String THUMBNAIL_VERSION = 'mi:thumbnailVersion';
	static final List<String> ORIENTATION_PROPERTIES = [ORIENTATION, WIDTH, HEIGHT];
	static final List<String> THUMBNAIL_PROPERTIES = [THUMBNAIL, THUMBNAIL_VERSION];
	static final List<String> PROPERTIES = ORIENTATION_PROPERTIES + THUMBNAIL_PROPERTIES;

	/** Sides within this fraction of each other count as square. */
	static final double SQUARE_TOLERANCE = 0.05;
	/** A long side at least this many times the short side counts as panorama. */
	static final double PANORAMA_RATIO = 2.0;
	/** Larger files are left alone: the whole file may have to be read to find its header. */
	static final long MAX_BYTES = 512L * 1024 * 1024;

	/** The long side of a thumbnail, in pixels. */
	static final int THUMBNAIL_SIZE = 320;
	/** JPEG quality of a thumbnail. */
	static final float THUMBNAIL_QUALITY = 0.82f;
	/** An embedded picture, or a tag block that may hold one, larger than this is not read. */
	static final int MAX_PICTURE_BYTES = 32 * 1024 * 1024;

	/** The metadata keys the dimensions are read from, in order of preference. */
	static final List<String> WIDTH_KEYS = ['tiff:ImageWidth', 'Image Width', 'width'];
	static final List<String> HEIGHT_KEYS = ['tiff:ImageLength', 'Image Height', 'height'];
	static final List<String> ORIENTATION_KEYS = ['tiff:Orientation', 'Orientation'];

	/** ID3v2 picture type of the front cover, preferred over any other picture. */
	static final int PICTURE_FRONT_COVER = 3;

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
		return isImageType(mimeType) || isVideoType(mimeType) || isAudioType(mimeType);
	}

	static boolean isImageType(String mimeType) {
		return (mimeType ?: '').toLowerCase().startsWith('image/');
	}

	static boolean isVideoType(String mimeType) {
		return (mimeType ?: '').toLowerCase().startsWith('video/');
	}

	static boolean isAudioType(String mimeType) {
		return (mimeType ?: '').toLowerCase().startsWith('audio/');
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
	 * Reads the file at {@code path} and stores its orientation, size and
	 * thumbnail. A file that is no longer a media file loses every property;
	 * an audio file has no orientation, a video no thumbnail. Returns whether
	 * anything was written.
	 */
	boolean update(String path) {
		def file = session.getResource(path);
		if (!file.exists() || file.isCollection()) {
			return false;
		}

		String mimeType = file.getContentType();
		if (!isMediaType(mimeType)) {
			return commitIfChanged(clear(file, PROPERTIES));
		}
		if (file.getContentLength() > MAX_BYTES) {
			log?.debug("Media metadata skipped, file too large: ${path}".toString());
			return false;
		}

		boolean changed = false;
		if (isAudioType(mimeType)) {
			changed |= clear(file, ORIENTATION_PROPERTIES);
			changed |= writeThumbnail(file, coverThumbnail(file));
		} else {
			Map dimensions = readDimensions(file);
			changed |= writeDimensions(file, dimensions);
			if (isImageType(mimeType)) {
				Long exif = dimensions?.exif as Long;
				changed |= writeThumbnail(file, imageThumbnail(file, exif));
			} else {
				changed |= clear(file, THUMBNAIL_PROPERTIES);
			}
		}
		return commitIfChanged(changed);
	}

	private boolean commitIfChanged(boolean changed) {
		if (changed) {
			session.commit();
		}
		return changed;
	}

	/** Stores the orientation and size; null dimensions leave whatever the file had. */
	private boolean writeDimensions(file, Map dimensions) {
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
		return true;
	}

	/**
	 * Stores a thumbnail with the file's modification time as its version; no
	 * thumbnail removes the one the file had, so a replaced image that could
	 * not be decoded does not keep the picture of the old content.
	 */
	private boolean writeThumbnail(file, byte[] thumbnail) {
		if (thumbnail == null) {
			return clear(file, THUMBNAIL_PROPERTIES);
		}
		file.setProperty(THUMBNAIL, thumbnail);
		file.setProperty(THUMBNAIL_VERSION, file.getLastModified().getTime() as long);
		return true;
	}

	/** Removes the named properties the file has; returns whether any was there. */
	private static boolean clear(file, List<String> names) {
		boolean changed = false;
		names.each { name ->
			if (file.hasProperty(name)) {
				file.removeProperty(name);
				changed = true;
			}
		};
		return changed;
	}

	private static String current(file, String name) {
		return file.hasProperty(name) ? file.getProperty(name).getString() : null;
	}

	/**
	 * The pixel size of the file's content, with the EXIF rotation applied
	 * (orientations 5 to 8 are rotated a quarter turn, so their sides swap),
	 * and the EXIF orientation itself under {@code exif} (null when there is
	 * none). Null when the parser finds no size, cannot read the file, or the
	 * file is empty.
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
			return [width: height, height: width, exif: exif];
		}
		return [width: width, height: height, exif: exif];
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

	// -----------------------------------------------------------------------
	// Thumbnails
	// -----------------------------------------------------------------------

	/** A JPEG thumbnail of an image file, turned the way its EXIF orientation says; null when it cannot be decoded. */
	private byte[] imageThumbnail(file, Long exif) {
		BufferedImage image = null;
		try {
			file.getContentAsStream().withCloseable { input ->
				image = decodeScaled(input);
			};
		} catch (Throwable ex) {
			log?.debug("Thumbnail not made: ${file.getPath()}: ${ex.message}".toString());
			return null;
		}
		if (image == null) {
			return null;
		}
		return encodeJpeg(rotate(fit(image), exif));
	}

	/** A JPEG thumbnail of the cover art embedded in an audio file; null when it has none or it cannot be decoded. */
	private byte[] coverThumbnail(file) {
		byte[] picture = null;
		try {
			file.getContentAsStream().withCloseable { input ->
				picture = readEmbeddedPicture(input);
			};
		} catch (Throwable ex) {
			log?.debug("Cover art not read: ${file.getPath()}: ${ex.message}".toString());
			return null;
		}
		if (picture == null) {
			return null;
		}
		BufferedImage image = null;
		try {
			new ByteArrayInputStream(picture).withCloseable { input ->
				image = decodeScaled(input);
			};
		} catch (Throwable ex) {
			log?.debug("Cover art not decoded: ${file.getPath()}: ${ex.message}".toString());
			return null;
		}
		if (image == null) {
			return null;
		}
		return encodeJpeg(fit(image));
	}

	/**
	 * Decodes the first image of a stream, subsampled so that no more than
	 * about twice the thumbnail size is materialised: a large photo is read at
	 * a fraction of its pixels rather than in full. Null when no reader takes
	 * the stream.
	 */
	static BufferedImage decodeScaled(InputStream input) {
		ImageInputStream stream = ImageIO.createImageInputStream(input);
		if (stream == null) {
			return null;
		}
		try {
			Iterator<ImageReader> readers = ImageIO.getImageReaders(stream);
			if (!readers.hasNext()) {
				return null;
			}
			ImageReader reader = readers.next();
			try {
				reader.setInput(stream, true, true);
				int width = reader.getWidth(0);
				int height = reader.getHeight(0);
				ImageReadParam param = reader.getDefaultReadParam();
				int step = (int) Math.floor(Math.max(width, height) / (double) (THUMBNAIL_SIZE * 2));
				if (step > 1) {
					param.setSourceSubsampling(step, step, 0, 0);
				}
				return reader.read(0, param);
			} finally {
				reader.dispose();
			}
		} finally {
			stream.close();
		}
	}

	/**
	 * The image scaled to fit THUMBNAIL_SIZE on its long side (never enlarged),
	 * drawn on white so transparency does not turn black in the JPEG. Halved
	 * step by step while it is more than twice too large, which keeps the
	 * bilinear filter within the range where it looks smooth.
	 */
	static BufferedImage fit(BufferedImage source) {
		BufferedImage image = source;
		int width = image.getWidth();
		int height = image.getHeight();
		double ratio = THUMBNAIL_SIZE / (double) Math.max(width, height);
		int targetWidth = ratio >= 1 ? width : Math.max(1, (int) Math.round(width * ratio));
		int targetHeight = ratio >= 1 ? height : Math.max(1, (int) Math.round(height * ratio));
		while (width / 2 >= targetWidth * 2 && height / 2 >= targetHeight * 2) {
			width = width.intdiv(2);
			height = height.intdiv(2);
			image = draw(image, width, height, null);
		}
		return draw(image, targetWidth, targetHeight, null);
	}

	/** The image turned the way an EXIF orientation says (1 and null leave it as it is). */
	static BufferedImage rotate(BufferedImage image, Long exif) {
		int orientation = exif == null ? 1 : exif.intValue();
		if (orientation < 2 || orientation > 8) {
			return image;
		}
		int width = image.getWidth();
		int height = image.getHeight();
		AffineTransform transform = new AffineTransform();
		switch (orientation) {
			case 2:
				transform.scale(-1.0, 1.0);
				transform.translate(-width, 0);
				break;
			case 3:
				transform.translate(width, height);
				transform.rotate(Math.PI);
				break;
			case 4:
				transform.scale(1.0, -1.0);
				transform.translate(0, -height);
				break;
			case 5:
				transform.rotate(-Math.PI / 2);
				transform.scale(-1.0, 1.0);
				break;
			case 6:
				transform.translate(height, 0);
				transform.rotate(Math.PI / 2);
				break;
			case 7:
				transform.scale(-1.0, 1.0);
				transform.translate(-height, 0);
				transform.translate(0, width);
				transform.rotate(3 * Math.PI / 2);
				break;
			case 8:
				transform.translate(0, width);
				transform.rotate(3 * Math.PI / 2);
				break;
		}
		boolean swapped = orientation >= 5;
		return draw(image, swapped ? height : width, swapped ? width : height, transform);
	}

	/** Draws the image into a new RGB image of the given size, through a transform when one is given. */
	private static BufferedImage draw(BufferedImage source, int width, int height, AffineTransform transform) {
		BufferedImage target = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = target.createGraphics();
		try {
			g.setColor(Color.WHITE);
			g.fillRect(0, 0, width, height);
			g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
			g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
			if (transform == null) {
				g.drawImage(source, 0, 0, width, height, null);
			} else {
				g.drawImage(source, transform, null);
			}
		} finally {
			g.dispose();
		}
		return target;
	}

	static byte[] encodeJpeg(BufferedImage image) {
		ImageWriter writer = ImageIO.getImageWritersByFormatName('jpeg').next();
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try {
			ImageWriteParam param = writer.getDefaultWriteParam();
			param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
			param.setCompressionQuality(THUMBNAIL_QUALITY);
			ImageOutputStream output = ImageIO.createImageOutputStream(bytes);
			try {
				writer.setOutput(output);
				writer.write(null, new IIOImage(image, null, null), param);
			} finally {
				output.close();
			}
		} finally {
			writer.dispose();
		}
		return bytes.toByteArray();
	}

	// -----------------------------------------------------------------------
	// Embedded pictures of audio files
	// -----------------------------------------------------------------------

	/**
	 * The picture embedded in an audio stream, by its container: an ID3v2 tag
	 * (MP3), an MP4 / M4A "covr" item, or a FLAC PICTURE block. The front cover
	 * is preferred when the file carries several. Null when there is none, or
	 * the container is not one of these (an Ogg or WAV file, for one).
	 */
	static byte[] readEmbeddedPicture(InputStream source) {
		DataInputStream input = new DataInputStream(new BufferedInputStream(source));
		input.mark(12);
		byte[] head = new byte[12];
		int read = 0;
		while (read < head.length) {
			int n = input.read(head, read, head.length - read);
			if (n < 0) {
				break;
			}
			read += n;
		}
		input.reset();
		if (read < 12) {
			return null;
		}
		if (head[0] == (byte) 'I' && head[1] == (byte) 'D' && head[2] == (byte) '3') {
			return readId3Picture(input);
		}
		if (head[0] == (byte) 'f' && head[1] == (byte) 'L' && head[2] == (byte) 'a' && head[3] == (byte) 'C') {
			return readFlacPicture(input);
		}
		if (head[4] == (byte) 'f' && head[5] == (byte) 't' && head[6] == (byte) 'y' && head[7] == (byte) 'p') {
			return readMp4Picture(input);
		}
		return null;
	}

	/** The APIC (v2.3, v2.4) or PIC (v2.2) frame of the ID3v2 tag at the start of the stream. */
	static byte[] readId3Picture(DataInputStream input) {
		byte[] header = new byte[10];
		input.readFully(header);
		int version = header[3] & 0xff;
		int flags = header[5] & 0xff;
		int tagSize = syncsafe(header, 6);
		if (version < 2 || version > 4 || tagSize <= 0 || tagSize > MAX_PICTURE_BYTES) {
			return null;
		}
		byte[] tag = new byte[tagSize];
		input.readFully(tag);
		if (version < 4 && (flags & 0x80) != 0) {
			// v2.2 / v2.3: unsynchronisation applies to the whole tag.
			tag = resynchronise(tag);
		}

		int pos = 0;
		if ((flags & 0x40) != 0) {
			// Extended header: v2.3 stores its size without the 4 size bytes,
			// v2.4 as a syncsafe size that includes them.
			if (version == 4) {
				pos += syncsafe(tag, 0);
			} else if (version == 3) {
				pos += 4 + int32(tag, 0);
			}
		}

		int headerLength = version == 2 ? 6 : 10;
		byte[] fallback = null;
		while (pos + headerLength <= tag.length) {
			if (tag[pos] == 0) {
				break; // padding
			}
			String id;
			int size;
			int frameFlags = 0;
			if (version == 2) {
				id = new String(tag, pos, 3, StandardCharsets.ISO_8859_1);
				size = ((tag[pos + 3] & 0xff) << 16) | ((tag[pos + 4] & 0xff) << 8) | (tag[pos + 5] & 0xff);
			} else {
				id = new String(tag, pos, 4, StandardCharsets.ISO_8859_1);
				size = version == 4 ? syncsafe(tag, pos + 4) : int32(tag, pos + 4);
				frameFlags = tag[pos + 9] & 0xff;
			}
			int bodyStart = pos + headerLength;
			if (size < 0 || bodyStart + size > tag.length) {
				break;
			}
			pos = bodyStart + size;

			if (id != 'APIC' && id != 'PIC') {
				continue;
			}
			byte[] body = Arrays.copyOfRange(tag, bodyStart, bodyStart + size);
			if (version == 3) {
				if ((frameFlags & 0xc0) != 0) {
					continue; // compressed or encrypted
				}
				if ((frameFlags & 0x20) != 0) {
					body = Arrays.copyOfRange(body, 1, body.length); // grouping identity
				}
			} else if (version == 4) {
				if ((frameFlags & 0x0c) != 0) {
					continue; // compressed or encrypted
				}
				int skip = 0;
				if ((frameFlags & 0x40) != 0) {
					skip += 1; // grouping identity
				}
				if ((frameFlags & 0x01) != 0) {
					skip += 4; // data length indicator
				}
				if (skip > 0) {
					body = Arrays.copyOfRange(body, skip, body.length);
				}
				if ((frameFlags & 0x02) != 0 || (flags & 0x80) != 0) {
					body = resynchronise(body);
				}
			}

			Map picture = parseId3PictureFrame(body, version);
			if (picture == null) {
				continue;
			}
			if (picture.type == PICTURE_FRONT_COVER) {
				return picture.data as byte[];
			}
			if (fallback == null) {
				fallback = picture.data as byte[];
			}
		}
		return fallback;
	}

	/**
	 * The picture type and data of an APIC / PIC frame body:
	 * encoding, MIME type (three letters in v2.2, a NUL-terminated string
	 * otherwise), picture type, description in the encoding, data.
	 */
	private static Map parseId3PictureFrame(byte[] body, int version) {
		if (body.length < 4) {
			return null;
		}
		int encoding = body[0] & 0xff;
		int pos = 1;
		if (version == 2) {
			pos += 3;
		} else {
			while (pos < body.length && body[pos] != 0) {
				pos++;
			}
			pos++;
		}
		if (pos >= body.length) {
			return null;
		}
		int type = body[pos] & 0xff;
		pos++;
		boolean wide = encoding == 1 || encoding == 2; // UTF-16 with BOM, UTF-16BE
		if (wide) {
			while (pos + 1 < body.length && !(body[pos] == 0 && body[pos + 1] == 0)) {
				pos += 2;
			}
			pos += 2;
		} else {
			while (pos < body.length && body[pos] != 0) {
				pos++;
			}
			pos++;
		}
		if (pos >= body.length) {
			return null;
		}
		return [type: type, data: Arrays.copyOfRange(body, pos, body.length)];
	}

	/** The PICTURE metadata block of a FLAC stream, preferring the front cover. */
	static byte[] readFlacPicture(DataInputStream input) {
		input.skipBytes(4); // fLaC
		byte[] fallback = null;
		while (true) {
			int first = input.read();
			if (first < 0) {
				break;
			}
			boolean last = (first & 0x80) != 0;
			int type = first & 0x7f;
			int length = (input.read() << 16) | (input.read() << 8) | input.read();
			if (length < 0) {
				break;
			}
			if (type == 6 && length <= MAX_PICTURE_BYTES) {
				byte[] block = new byte[length];
				input.readFully(block);
				ByteBuffer buffer = ByteBuffer.wrap(block);
				int pictureType = buffer.getInt();
				int mimeLength = buffer.getInt();
				buffer.position(buffer.position() + mimeLength);
				int descriptionLength = buffer.getInt();
				buffer.position(buffer.position() + descriptionLength);
				buffer.position(buffer.position() + 16); // width, height, depth, colors
				int dataLength = buffer.getInt();
				if (dataLength < 0 || dataLength > buffer.remaining()) {
					break;
				}
				byte[] data = new byte[dataLength];
				buffer.get(data);
				if (pictureType == PICTURE_FRONT_COVER) {
					return data;
				}
				if (fallback == null) {
					fallback = data;
				}
			} else {
				skipFully(input, length);
			}
			if (last) {
				break;
			}
		}
		return fallback;
	}

	/**
	 * The first "covr" item of an MP4 / M4A file: moov / udta / meta / ilst /
	 * covr / data. The boxes before udta are skipped without being read; udta,
	 * which holds the picture, is read whole.
	 */
	static byte[] readMp4Picture(DataInputStream input) {
		byte[] udta = readMp4Box(input, Long.MAX_VALUE, ['moov', 'udta']);
		if (udta == null) {
			return null;
		}
		ByteBuffer buffer = ByteBuffer.wrap(udta);
		ByteBuffer meta = findMp4Box(buffer, 'meta');
		if (meta == null) {
			return null;
		}
		// meta is a full box (4 bytes of version and flags) in an MP4, a plain
		// box in a QuickTime file; a child box size is never zero.
		if (meta.remaining() >= 4 && meta.getInt(meta.position()) == 0) {
			meta.position(meta.position() + 4);
		}
		ByteBuffer ilst = findMp4Box(meta, 'ilst');
		if (ilst == null) {
			return null;
		}
		ByteBuffer covr = findMp4Box(ilst, 'covr');
		if (covr == null) {
			return null;
		}
		ByteBuffer data = findMp4Box(covr, 'data');
		if (data == null || data.remaining() <= 8) {
			return null;
		}
		data.position(data.position() + 8); // type indicator, locale
		byte[] picture = new byte[data.remaining()];
		data.get(picture);
		return picture;
	}

	/**
	 * Walks the boxes of the stream along {@code path}, skipping the others,
	 * and returns the payload of the last one; null when the path is not there.
	 */
	private static byte[] readMp4Box(DataInputStream input, long remaining, List<String> path) {
		while (remaining >= 8) {
			long size = int32(input) & 0xffffffffL;
			String type = readType(input);
			int headerLength = 8;
			if (size == 1) {
				size = input.readLong();
				headerLength = 16;
			} else if (size == 0) {
				size = remaining; // to the end of the enclosing box
			}
			if (size < headerLength) {
				return null;
			}
			long payload = size - headerLength;
			if (type == path[0]) {
				if (path.size() == 1) {
					if (payload > MAX_PICTURE_BYTES) {
						return null;
					}
					byte[] bytes = new byte[(int) payload];
					input.readFully(bytes);
					return bytes;
				}
				return readMp4Box(input, payload, path.tail());
			}
			skipFully(input, payload);
			remaining -= size;
		}
		return null;
	}

	/** The payload of the first child box of the given type in the buffer, as a slice; null when there is none. */
	private static ByteBuffer findMp4Box(ByteBuffer buffer, String type) {
		ByteBuffer scan = buffer.slice();
		while (scan.remaining() >= 8) {
			int start = scan.position();
			long size = scan.getInt() & 0xffffffffL;
			byte[] typeBytes = new byte[4];
			scan.get(typeBytes);
			int headerLength = 8;
			if (size == 1) {
				if (scan.remaining() < 8) {
					return null;
				}
				size = scan.getLong();
				headerLength = 16;
			} else if (size == 0) {
				size = scan.limit() - start;
			}
			if (size < headerLength || start + size > scan.limit()) {
				return null;
			}
			if (new String(typeBytes, StandardCharsets.ISO_8859_1) == type) {
				ByteBuffer payload = scan.duplicate();
				payload.position(start + headerLength);
				payload.limit((int) (start + size));
				return payload.slice();
			}
			scan.position((int) (start + size));
		}
		return null;
	}

	private static String readType(DataInputStream input) {
		byte[] type = new byte[4];
		input.readFully(type);
		return new String(type, StandardCharsets.ISO_8859_1);
	}

	private static int int32(DataInputStream input) {
		return input.readInt();
	}

	private static int int32(byte[] bytes, int offset) {
		return ((bytes[offset] & 0xff) << 24) | ((bytes[offset + 1] & 0xff) << 16) |
				((bytes[offset + 2] & 0xff) << 8) | (bytes[offset + 3] & 0xff);
	}

	/** A 28-bit ID3 "syncsafe" integer: four bytes of seven bits each. */
	private static int syncsafe(byte[] bytes, int offset) {
		return ((bytes[offset] & 0x7f) << 21) | ((bytes[offset + 1] & 0x7f) << 14) |
				((bytes[offset + 2] & 0x7f) << 7) | (bytes[offset + 3] & 0x7f);
	}

	/** Undoes ID3 unsynchronisation: every FF 00 becomes FF. */
	private static byte[] resynchronise(byte[] bytes) {
		ByteArrayOutputStream out = new ByteArrayOutputStream(bytes.length);
		for (int i = 0; i < bytes.length; i++) {
			out.write(bytes[i]);
			if (bytes[i] == (byte) 0xff && i + 1 < bytes.length && bytes[i + 1] == 0) {
				i++;
			}
		}
		return out.toByteArray();
	}

	private static void skipFully(InputStream input, long count) {
		long left = count;
		while (left > 0) {
			long skipped = input.skip(left);
			if (skipped <= 0) {
				if (input.read() < 0) {
					throw new EOFException();
				}
				skipped = 1;
			}
			left -= skipped;
		}
	}
}
