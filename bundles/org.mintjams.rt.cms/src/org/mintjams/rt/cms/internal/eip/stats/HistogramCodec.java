/*
 * Copyright (c) 2022 MintJams Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package org.mintjams.rt.cms.internal.eip.stats;

import java.nio.ByteBuffer;
import java.util.Base64;
import java.util.zip.DataFormatException;

import org.HdrHistogram.Histogram;

/**
 * Encodes / decodes HdrHistogram instances to a compact Base64 representation
 * suitable for embedding in bucket JSON files.
 *
 * <p>Histograms produced by this codec are configured to track values from
 * 1 to {@link #MAX_TRACKABLE_VALUE} (1 hour expressed in milliseconds) with
 * three significant digits of precision. Buckets stored under this scheme are
 * binary-compatible with each other so {@link Histogram#add(Histogram)} works
 * across mergers.
 */
public final class HistogramCodec {

	/** Highest trackable value in milliseconds (1 hour). */
	public static final long MAX_TRACKABLE_VALUE = 3_600_000L;
	public static final int SIGNIFICANT_DIGITS = 3;

	private HistogramCodec() {}

	public static Histogram newHistogram() {
		return new Histogram(MAX_TRACKABLE_VALUE, SIGNIFICANT_DIGITS);
	}

	public static String encode(Histogram h) {
		if (h == null || h.getTotalCount() == 0) {
			return null;
		}
		int capacity = h.getNeededByteBufferCapacity();
		ByteBuffer buf = ByteBuffer.allocate(capacity);
		int written = h.encodeIntoCompressedByteBuffer(buf);
		byte[] bytes = new byte[written];
		buf.flip();
		buf.get(bytes, 0, written);
		return Base64.getEncoder().encodeToString(bytes);
	}

	public static Histogram decode(String encoded) {
		if (encoded == null || encoded.isEmpty()) {
			return newHistogram();
		}
		try {
			byte[] bytes = Base64.getDecoder().decode(encoded);
			ByteBuffer buf = ByteBuffer.wrap(bytes);
			return Histogram.decodeFromCompressedByteBuffer(buf, MAX_TRACKABLE_VALUE);
		} catch (DataFormatException ex) {
			throw new IllegalArgumentException("Failed to decode histogram: " + ex.getMessage(), ex);
		}
	}
}
