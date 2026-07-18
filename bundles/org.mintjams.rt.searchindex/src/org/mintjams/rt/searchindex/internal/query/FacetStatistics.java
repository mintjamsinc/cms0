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

package org.mintjams.rt.searchindex.internal.query;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.lucene.facet.FacetResult;
import org.apache.lucene.facet.FacetsCollector;
import org.apache.lucene.facet.FacetsConfig;
import org.apache.lucene.facet.LabelAndValue;
import org.apache.lucene.facet.taxonomy.FacetLabel;
import org.apache.lucene.facet.taxonomy.ParallelTaxonomyArrays;
import org.apache.lucene.facet.taxonomy.TaxonomyReader;
import org.apache.lucene.index.DocValuesType;
import org.apache.lucene.index.FieldInfo;
import org.apache.lucene.index.LeafReader;
import org.apache.lucene.index.SortedDocValues;
import org.apache.lucene.index.SortedNumericDocValues;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.util.NumericUtils;
import org.mintjams.rt.searchindex.internal.Activator;
import org.mintjams.searchindex.SearchIndex;
import org.mintjams.searchindex.query.InvalidQuerySyntaxException;

/**
 * Computes statistical aggregations (sum, avg, min, max, count, missing,
 * variance, stddev, percentiles) over the documents matched by a query, for
 * the {@code facet accumulate} clause of the jcr:xpath query language.
 * <p>
 * All statistics are computed in a single pass over the
 * {@link FacetsCollector}'s matching documents, reading the per-field
 * {@code SortedNumericDocValues} that the index writer already stores for
 * every numeric, date and boolean property (see IndexableDocument). No
 * additional index structures and no re-execution of the search are required.
 * <p>
 * A statistic is either computed over the whole result set, or grouped by a
 * string property: string properties are indexed as taxonomy facet dimensions,
 * so grouping reads the per-document taxonomy ordinals (the same data that
 * drives the facet counts) and keeps one accumulator per child ordinal of the
 * grouping dimension.
 */
class FacetStatistics {

	/**
	 * The aggregate functions accepted in a {@code facet accumulate} clause.
	 * {@code MEDIAN} is {@code PERCENTILE} at p=50 but keeps its own identity so
	 * the result labels stay {@code value} rather than {@code 50}.
	 */
	enum Function {
		COUNT, MISSING, SUM, MIN, MAX, AVG, VARIANCE, STDDEV, STATS, PERCENTILE, MEDIAN
	}

	/**
	 * One parsed aggregate expression. Instances are produced by the query
	 * parser (FacetAccumulateClause) and consumed by
	 * {@link #compute(List, FacetsCollector, TaxonomyReader, FacetsConfig, SearchIndex.FieldTypeProvider)}.
	 */
	static class Params {
		private final String fDimension;
		private final Function fFunction;
		private final String fFieldName;
		private final Class<?> fFieldType;
		private final String fGroupFieldName;
		private final Class<?> fGroupFieldType;
		private final double[] fPercentiles;
		private final String[] fPercentileLabels;
		private int fLimit = Integer.MAX_VALUE;

		Params(String dimension, Function function, String fieldName, Class<?> fieldType, String groupFieldName,
				Class<?> groupFieldType, double[] percentiles, String[] percentileLabels) {
			fDimension = dimension;
			fFunction = function;
			fFieldName = fieldName;
			fFieldType = fieldType;
			fGroupFieldName = groupFieldName;
			fGroupFieldType = groupFieldType;
			fPercentiles = percentiles;
			fPercentileLabels = percentileLabels;
		}

		String getDimension() {
			return fDimension;
		}

		Function getFunction() {
			return fFunction;
		}

		String getFieldName() {
			return fFieldName;
		}

		/**
		 * The value type named by an {@code xs:} cast in the aggregate
		 * expression, or {@code null} when the expression carried no cast (the
		 * decoding then falls back to the {@code FieldTypeProvider} and finally
		 * to the numeric default).
		 */
		Class<?> getFieldType() {
			return fFieldType;
		}

		String getGroupFieldName() {
			return fGroupFieldName;
		}

		/**
		 * The value type named by an {@code xs:} cast on the GROUPING argument, or
		 * {@code null} when it carried no cast. A date or boolean grouping property
		 * needs this cast to decode its raw doc values (epoch milliseconds, or 0/1)
		 * into the domain the {@code range()} bucket bounds live in; a numeric
		 * grouping property is self-describing (double-encoded) and needs none.
		 */
		Class<?> getGroupFieldType() {
			return fGroupFieldType;
		}

		boolean isGrouped() {
			return fGroupFieldName != null;
		}

		double[] getPercentiles() {
			return fPercentiles;
		}

		String[] getPercentileLabels() {
			return fPercentileLabels;
		}

		void setLimit(int limit) {
			fLimit = limit;
		}

		int getLimit() {
			return fLimit;
		}

		private boolean needsValues() {
			return fFunction != Function.COUNT && fFunction != Function.MISSING;
		}

		private boolean needsPercentiles() {
			return fFunction == Function.PERCENTILE || fFunction == Function.MEDIAN;
		}
	}

	private FacetStatistics() {}

	/**
	 * One bucket of a {@code range()} facet declared in the same clause, in
	 * the double domain the group property's values decode to (numbers as
	 * doubles, dates as epoch milliseconds).
	 */
	static class RangeBucket {
		private final String fLabel;
		private final double fMin;
		private final boolean fMinInclusive;
		private final double fMax;
		private final boolean fMaxInclusive;

		RangeBucket(String label, double min, boolean minInclusive, double max, boolean maxInclusive) {
			fLabel = label;
			fMin = min;
			fMinInclusive = minInclusive;
			fMax = max;
			fMaxInclusive = maxInclusive;
		}

		boolean contains(double value) {
			if (fMinInclusive ? value < fMin : value <= fMin) {
				return false;
			}
			if (fMaxInclusive ? value > fMax : value >= fMax) {
				return false;
			}
			return true;
		}
	}

	/**
	 * Computes every requested statistic in one pass over the matching
	 * documents and returns one Lucene {@link FacetResult} per expression, with
	 * {@code dim} set to the expression's canonical text so the results live in
	 * the same per-dimension map as the ordinary facet counts without
	 * colliding with them.
	 * <p>
	 * A grouped statistic buckets by the {@code range()} facets declared on
	 * the grouping property in the same clause when there are any
	 * ({@code rangeBuckets}), and by the property's taxonomy dimension (its
	 * string values) otherwise.
	 */
	static List<FacetResult> compute(List<Params> paramsList, FacetsCollector collector,
			TaxonomyReader taxonomyReader, FacetsConfig facetsConfig,
			SearchIndex.FieldTypeProvider fieldTypeProvider,
			Map<String, List<RangeBucket>> rangeBuckets) throws IOException {
		int percentileExactLimit = Activator.getPercentileExactLimit();
		List<State> states = new ArrayList<>();
		Map<String, ValueSource> valueSources = new LinkedHashMap<>();
		String ordinalsFieldName = null;
		for (Params params : paramsList) {
			// Key by field name AND cast type: the same field may be aggregated
			// with and without an xs: cast in one clause, and the two decode
			// differently.
			String valueSourceKey = params.getFieldName()
					+ ((params.getFieldType() == null) ? "" : "#" + params.getFieldType().getSimpleName());
			ValueSource valueSource = valueSources.get(valueSourceKey);
			if (valueSource == null) {
				valueSource = new ValueSource(params.getFieldName(), fieldTypeProvider, params.getFieldType());
				valueSources.put(valueSourceKey, valueSource);
			}

			if (!params.isGrouped()) {
				states.add(new UngroupedState(params, valueSource, percentileExactLimit));
				continue;
			}

			List<RangeBucket> groupRanges = rangeBuckets.get(params.getGroupFieldName());
			if (groupRanges != null && !groupRanges.isEmpty()) {
				// The grouping property's decode follows its xs: cast when the
				// expression carried one — xs:dateTime(@occurred_at) reads the raw
				// epoch-millisecond doc values that the range-bucket bounds live in
				// — then the FieldTypeProvider, then the numeric default. Key by
				// field name AND cast type so one field grouped under two casts in a
				// single clause keeps two decoders.
				String groupSourceKey = params.getGroupFieldName()
						+ ((params.getGroupFieldType() == null) ? "" : "#" + params.getGroupFieldType().getSimpleName());
				ValueSource groupValueSource = valueSources.get(groupSourceKey);
				if (groupValueSource == null) {
					groupValueSource = new ValueSource(params.getGroupFieldName(), fieldTypeProvider,
							params.getGroupFieldType());
					valueSources.put(groupSourceKey, groupValueSource);
				}
				states.add(new RangeGroupedState(params, valueSource, groupValueSource, groupRanges, percentileExactLimit));
				continue;
			}

			// Taxonomy ordinals for every dimension live in the shared default
			// index field: this codebase never configures per-dimension index
			// field names, so a single ordinals reader serves all groupings.
			ordinalsFieldName = facetsConfig.getDimConfig(params.getGroupFieldName()).indexFieldName;
			int groupOrdinal = taxonomyReader.getOrdinal(new FacetLabel(params.getGroupFieldName()));
			states.add(new TaxonomyGroupedState(params, valueSource, groupOrdinal, percentileExactLimit));
		}

		boolean needsOrdinals = (ordinalsFieldName != null);
		ParallelTaxonomyArrays.IntArray parents = needsOrdinals
				? taxonomyReader.getParallelTaxonomyArrays().parents()
				: null;

		OrdinalsScratch ordinals = new OrdinalsScratch();
		for (FacetsCollector.MatchingDocs matchingDocs : collector.getMatchingDocs()) {
			LeafReader reader = matchingDocs.context.reader();
			for (ValueSource valueSource : valueSources.values()) {
				valueSource.reset(reader);
			}
			SortedNumericDocValues ordinalValues = needsOrdinals
					? reader.getSortedNumericDocValues(ordinalsFieldName)
					: null;

			DocIdSetIterator docs = matchingDocs.bits.iterator();
			for (int doc = docs.nextDoc(); doc != DocIdSetIterator.NO_MORE_DOCS; doc = docs.nextDoc()) {
				for (ValueSource valueSource : valueSources.values()) {
					valueSource.advance(doc);
				}
				ordinals.advance(ordinalValues, doc);
				for (State state : states) {
					state.collect(ordinals, parents);
				}
			}
		}

		List<FacetResult> results = new ArrayList<>();
		for (State state : states) {
			results.add(state.toFacetResult(taxonomyReader));
		}
		return results;
	}

	/**
	 * The built-in document fields whose {@code SortedNumericDocValues} hold the
	 * raw long (size, depth, or epoch milliseconds) rather than the
	 * sortable-double encoding used for numeric user properties.
	 */
	private static boolean isRawLongField(String fieldName) {
		return fieldName.equals("_size") || fieldName.equals("_depth")
				|| fieldName.equals("_created") || fieldName.equals("_lastModified");
	}

	/**
	 * Reads the per-document values of one field, decoding them to doubles.
	 * Numeric user properties are double-encoded via
	 * {@code NumericUtils.doubleToSortableLong} (see IndexableDocument), dates
	 * and booleans are stored raw, and the decision between the two decodings
	 * follows the field type: an explicit {@code xs:} cast in the aggregate
	 * expression wins, then the {@link SearchIndex.FieldTypeProvider} when
	 * available, defaulting to numeric. String properties have no numeric doc
	 * values; they support only count/missing, via their
	 * {@code SortedDocValues} presence.
	 */
	private static class ValueSource {
		private final String fFieldName;
		private final boolean fRawLong;
		private SortedNumericDocValues fNumericValues;
		private SortedDocValues fStringValues;
		private double[] fValues = new double[4];
		private int fValueCount;

		ValueSource(String fieldName, SearchIndex.FieldTypeProvider fieldTypeProvider) {
			this(fieldName, fieldTypeProvider, null);
		}

		ValueSource(String fieldName, SearchIndex.FieldTypeProvider fieldTypeProvider, Class<?> explicitType) {
			fFieldName = fieldName;

			boolean rawLong = isRawLongField(fieldName);
			if (!rawLong) {
				Class<?> fieldType = explicitType;
				if (fieldType == null && fieldTypeProvider != null) {
					fieldType = fieldTypeProvider.getFieldType(fieldName);
				}
				if (fieldType != null
						&& (fieldType.equals(java.util.Date.class) || fieldType.equals(Boolean.class))) {
					rawLong = true;
				}
			}
			fRawLong = rawLong;
		}

		void reset(LeafReader reader) throws IOException {
			fNumericValues = null;
			fStringValues = null;
			FieldInfo fieldInfo = reader.getFieldInfos().fieldInfo(fFieldName);
			if (fieldInfo == null) {
				return;
			}
			if (fieldInfo.getDocValuesType() == DocValuesType.SORTED_NUMERIC) {
				fNumericValues = reader.getSortedNumericDocValues(fFieldName);
			} else if (fieldInfo.getDocValuesType() == DocValuesType.SORTED) {
				fStringValues = reader.getSortedDocValues(fFieldName);
			}
		}

		void advance(int doc) throws IOException {
			fValueCount = 0;
			if (fNumericValues != null) {
				if (fNumericValues.advanceExact(doc)) {
					int count = fNumericValues.docValueCount();
					if (fValues.length < count) {
						fValues = new double[count];
					}
					for (int i = 0; i < count; i++) {
						long value = fNumericValues.nextValue();
						fValues[i] = fRawLong ? value : NumericUtils.sortableLongToDouble(value);
					}
					fValueCount = count;
				}
				return;
			}
			if (fStringValues != null) {
				if (fStringValues.advanceExact(doc)) {
					// String properties carry no numeric value; they contribute
					// presence only (count/missing). Aggregating their values is
					// rejected at collect time.
					fValues[0] = Double.NaN;
					fValueCount = 1;
				}
			}
		}

		boolean isNumeric() {
			return fStringValues == null;
		}

		int getValueCount() {
			return fValueCount;
		}

		double getValue(int index) {
			return fValues[index];
		}
	}

	/**
	 * The taxonomy ordinals of the current document, read once per document and
	 * shared by every grouped statistic.
	 */
	private static class OrdinalsScratch {
		private long[] fOrdinals = new long[8];
		private int fCount;

		void advance(SortedNumericDocValues ordinalValues, int doc) throws IOException {
			fCount = 0;
			if (ordinalValues == null) {
				return;
			}
			if (!ordinalValues.advanceExact(doc)) {
				return;
			}
			int count = ordinalValues.docValueCount();
			if (fOrdinals.length < count) {
				fOrdinals = new long[count];
			}
			for (int i = 0; i < count; i++) {
				fOrdinals[i] = ordinalValues.nextValue();
			}
			fCount = count;
		}

		int getCount() {
			return fCount;
		}

		int getOrdinal(int index) {
			return (int) fOrdinals[index];
		}
	}

	/**
	 * Streaming accumulator for one statistic target (the whole result set, or
	 * one group bucket). Mean and variance use Welford's algorithm for numeric
	 * stability; percentile requests additionally buffer the raw values for
	 * exact computation, migrating to a t-digest sketch (bounded memory,
	 * small quantile-dependent rank error) once the configured exact limit is
	 * exceeded.
	 */
	private static class Accumulator {
		private final boolean fKeepValues;
		private final int fExactLimit;
		private long fCount;
		private long fMissing;
		private double fSum;
		private double fMin = Double.POSITIVE_INFINITY;
		private double fMax = Double.NEGATIVE_INFINITY;
		private double fMean;
		private double fM2;
		private double[] fBuffer;
		private int fBufferSize;
		private TDigest fDigest;

		Accumulator(boolean keepValues, int exactLimit) {
			fKeepValues = keepValues;
			fExactLimit = exactLimit;
		}

		void addValue(double value) {
			fCount++;
			fSum += value;
			if (value < fMin) {
				fMin = value;
			}
			if (value > fMax) {
				fMax = value;
			}
			double delta = value - fMean;
			fMean += delta / fCount;
			fM2 += delta * (value - fMean);

			if (fKeepValues) {
				if (fDigest != null) {
					fDigest.add(value);
					return;
				}
				if (fBufferSize >= fExactLimit) {
					fDigest = new TDigest();
					for (int i = 0; i < fBufferSize; i++) {
						fDigest.add(fBuffer[i]);
					}
					fBuffer = null;
					fBufferSize = 0;
					fDigest.add(value);
					return;
				}
				if (fBuffer == null) {
					fBuffer = new double[64];
				} else if (fBufferSize == fBuffer.length) {
					double[] grown = new double[fBuffer.length + (fBuffer.length >> 1)];
					System.arraycopy(fBuffer, 0, grown, 0, fBufferSize);
					fBuffer = grown;
				}
				fBuffer[fBufferSize++] = value;
			}
		}

		void addMissing() {
			fMissing++;
		}

		long getCount() {
			return fCount;
		}

		long getMissing() {
			return fMissing;
		}

		double getSum() {
			return fSum;
		}

		double getMin() {
			return (fCount == 0) ? Double.NaN : fMin;
		}

		double getMax() {
			return (fCount == 0) ? Double.NaN : fMax;
		}

		double getAvg() {
			return (fCount == 0) ? Double.NaN : fMean;
		}

		/**
		 * Sample variance (n-1 denominator, as Solr's stats component reports).
		 * NaN for an empty set, 0 for a single value.
		 */
		double getVariance() {
			if (fCount == 0) {
				return Double.NaN;
			}
			if (fCount == 1) {
				return 0;
			}
			return fM2 / (fCount - 1);
		}

		double getStddev() {
			double variance = getVariance();
			return Double.isNaN(variance) ? Double.NaN : Math.sqrt(variance);
		}

		/**
		 * Percentiles with linear interpolation between closest ranks (the same
		 * convention as numpy's default and Excel's PERCENTILE.INC). Exact when
		 * the values fit the configured exact limit (the buffer is sorted once
		 * for all requested percentiles); estimated from the t-digest sketch
		 * beyond it.
		 */
		double[] getPercentiles(double[] percentiles) {
			double[] results = new double[percentiles.length];
			if (fDigest != null) {
				for (int i = 0; i < percentiles.length; i++) {
					results[i] = fDigest.quantile(percentiles[i] / 100d);
				}
				return results;
			}
			if (fBufferSize == 0) {
				java.util.Arrays.fill(results, Double.NaN);
				return results;
			}
			double[] sorted = java.util.Arrays.copyOf(fBuffer, fBufferSize);
			java.util.Arrays.sort(sorted);
			for (int i = 0; i < percentiles.length; i++) {
				double rank = percentiles[i] / 100d * (sorted.length - 1);
				int lower = (int) Math.floor(rank);
				if (lower >= sorted.length - 1) {
					results[i] = sorted[sorted.length - 1];
					continue;
				}
				double fraction = rank - lower;
				results[i] = sorted[lower] + (sorted[lower + 1] - sorted[lower]) * fraction;
			}
			return results;
		}

		/** The single reported number for every function except STATS/PERCENTILE. */
		Number getSingleValue(Function function) {
			switch (function) {
			case COUNT:
				return getCount();
			case MISSING:
				return getMissing();
			case SUM:
				return getSum();
			case MIN:
				return getMin();
			case MAX:
				return getMax();
			case AVG:
				return getAvg();
			case VARIANCE:
				return getVariance();
			case STDDEV:
				return getStddev();
			case MEDIAN:
				return getPercentiles(new double[] { 50d })[0];
			default:
				throw new IllegalStateException(function.name());
			}
		}
	}

	private static abstract class State {
		protected final Params fParams;
		protected final ValueSource fValueSource;

		protected State(Params params, ValueSource valueSource) {
			fParams = params;
			fValueSource = valueSource;
		}

		protected void accumulate(Accumulator accumulator) {
			if (!fValueSource.isNumeric() && fParams.needsValues()) {
				throw new InvalidQuerySyntaxException(
						"Property '" + fParams.getFieldName() + "' has no numeric values: " + fParams.getDimension());
			}
			int count = fValueSource.getValueCount();
			if (count == 0) {
				accumulator.addMissing();
				return;
			}
			if (!fParams.needsValues()) {
				// count/missing work on any property type, including strings,
				// where each document contributes presence only.
				if (!fValueSource.isNumeric()) {
					accumulator.addValue(0);
					return;
				}
			}
			for (int i = 0; i < count; i++) {
				accumulator.addValue(fValueSource.getValue(i));
			}
		}

		abstract void collect(OrdinalsScratch ordinals, ParallelTaxonomyArrays.IntArray parents);

		abstract FacetResult toFacetResult(TaxonomyReader taxonomyReader) throws IOException;
	}

	private static class UngroupedState extends State {
		private final Accumulator fAccumulator;

		UngroupedState(Params params, ValueSource valueSource, int percentileExactLimit) {
			super(params, valueSource);
			fAccumulator = new Accumulator(params.needsPercentiles(), percentileExactLimit);
		}

		@Override
		void collect(OrdinalsScratch ordinals, ParallelTaxonomyArrays.IntArray parents) {
			accumulate(fAccumulator);
		}

		@Override
		FacetResult toFacetResult(TaxonomyReader taxonomyReader) {
			List<LabelAndValue> labelValues = new ArrayList<>();
			switch (fParams.getFunction()) {
			case STATS:
				labelValues.add(new LabelAndValue("count", fAccumulator.getCount()));
				labelValues.add(new LabelAndValue("missing", fAccumulator.getMissing()));
				labelValues.add(new LabelAndValue("sum", fAccumulator.getSum()));
				labelValues.add(new LabelAndValue("min", fAccumulator.getMin()));
				labelValues.add(new LabelAndValue("max", fAccumulator.getMax()));
				labelValues.add(new LabelAndValue("avg", fAccumulator.getAvg()));
				labelValues.add(new LabelAndValue("variance", fAccumulator.getVariance()));
				labelValues.add(new LabelAndValue("stddev", fAccumulator.getStddev()));
				break;
			case PERCENTILE: {
				double[] results = fAccumulator.getPercentiles(fParams.getPercentiles());
				for (int i = 0; i < results.length; i++) {
					labelValues.add(new LabelAndValue(fParams.getPercentileLabels()[i], results[i]));
				}
				break;
			}
			default:
				labelValues.add(new LabelAndValue("value", fAccumulator.getSingleValue(fParams.getFunction())));
				break;
			}
			return new FacetResult(fParams.getDimension(), new String[0],
					fAccumulator.getCount() + fAccumulator.getMissing(),
					labelValues.toArray(LabelAndValue[]::new), labelValues.size());
		}
	}

	/**
	 * One accumulator per child ordinal of the grouping dimension. A document
	 * contributes its values to every bucket it is labelled with (multi-valued
	 * dimensions), exactly as the facet counts do. Buckets are reported in
	 * descending order of the aggregated value ({@code NaN} last, ties by
	 * label) and truncated to the expression's limit, so
	 * {@code top(sum(...), n)} is a pure presentation step.
	 */
	private static class TaxonomyGroupedState extends State {
		private final int fGroupOrdinal;
		private final int fPercentileExactLimit;
		private final Map<Integer, Accumulator> fAccumulators = new HashMap<>();

		TaxonomyGroupedState(Params params, ValueSource valueSource, int groupOrdinal, int percentileExactLimit) {
			super(params, valueSource);
			fGroupOrdinal = groupOrdinal;
			fPercentileExactLimit = percentileExactLimit;
		}

		@Override
		void collect(OrdinalsScratch ordinals, ParallelTaxonomyArrays.IntArray parents) {
			if (fGroupOrdinal == TaxonomyReader.INVALID_ORDINAL) {
				return;
			}
			for (int i = 0; i < ordinals.getCount(); i++) {
				int ordinal = ordinals.getOrdinal(i);
				if (parents.get(ordinal) != fGroupOrdinal) {
					continue;
				}
				Accumulator accumulator = fAccumulators.get(ordinal);
				if (accumulator == null) {
					accumulator = new Accumulator(fParams.needsPercentiles(), fPercentileExactLimit);
					fAccumulators.put(ordinal, accumulator);
				}
				accumulate(accumulator);
			}
		}

		@Override
		FacetResult toFacetResult(TaxonomyReader taxonomyReader) throws IOException {
			List<LabelAndValue> labelValues = new ArrayList<>();
			long totalCount = 0;
			for (Map.Entry<Integer, Accumulator> e : fAccumulators.entrySet()) {
				Accumulator accumulator = e.getValue();
				totalCount += accumulator.getCount() + accumulator.getMissing();
				Number value;
				if (fParams.getFunction() == Function.PERCENTILE) {
					value = accumulator.getPercentiles(fParams.getPercentiles())[0];
				} else {
					value = accumulator.getSingleValue(fParams.getFunction());
				}
				String label = taxonomyReader.getPath(e.getKey()).components[1];
				labelValues.add(new LabelAndValue(label, value));
			}

			labelValues.sort(Comparator
					.<LabelAndValue>comparingDouble(e -> {
						double value = e.value.doubleValue();
						return Double.isNaN(value) ? Double.NEGATIVE_INFINITY : value;
					})
					.reversed()
					.thenComparing(e -> e.label));
			if (labelValues.size() > fParams.getLimit()) {
				labelValues = labelValues.subList(0, fParams.getLimit());
			}

			return new FacetResult(fParams.getDimension(), new String[0], totalCount,
					labelValues.toArray(LabelAndValue[]::new), labelValues.size());
		}
	}

	/**
	 * One accumulator per {@code range()} bucket declared on the grouping
	 * property. A document contributes its values once to every bucket that
	 * any of its group-property values falls into, mirroring how the range
	 * facet counts treat multi-valued properties. Buckets are reported in
	 * declaration order (like the range counts); a {@code top(aggregate, n)}
	 * wrapper switches to descending aggregated value and truncates.
	 */
	private static class RangeGroupedState extends State {
		private final ValueSource fGroupValueSource;
		private final List<RangeBucket> fBuckets;
		private final Accumulator[] fAccumulators;

		RangeGroupedState(Params params, ValueSource valueSource, ValueSource groupValueSource,
				List<RangeBucket> buckets, int percentileExactLimit) {
			super(params, valueSource);
			fGroupValueSource = groupValueSource;
			fBuckets = buckets;
			fAccumulators = new Accumulator[buckets.size()];
			for (int i = 0; i < fAccumulators.length; i++) {
				fAccumulators[i] = new Accumulator(params.needsPercentiles(), percentileExactLimit);
			}
		}

		@Override
		void collect(OrdinalsScratch ordinals, ParallelTaxonomyArrays.IntArray parents) {
			int groupValueCount = fGroupValueSource.getValueCount();
			if (groupValueCount == 0) {
				return;
			}
			if (!fGroupValueSource.isNumeric()) {
				throw new InvalidQuerySyntaxException(
						"Property '" + fParams.getGroupFieldName() + "' has no numeric values: " + fParams.getDimension());
			}
			for (int b = 0; b < fBuckets.size(); b++) {
				RangeBucket bucket = fBuckets.get(b);
				for (int i = 0; i < groupValueCount; i++) {
					if (bucket.contains(fGroupValueSource.getValue(i))) {
						accumulate(fAccumulators[b]);
						break;
					}
				}
			}
		}

		@Override
		FacetResult toFacetResult(TaxonomyReader taxonomyReader) {
			List<LabelAndValue> labelValues = new ArrayList<>();
			long totalCount = 0;
			for (int b = 0; b < fBuckets.size(); b++) {
				Accumulator accumulator = fAccumulators[b];
				totalCount += accumulator.getCount() + accumulator.getMissing();
				Number value;
				if (fParams.getFunction() == Function.PERCENTILE) {
					value = accumulator.getPercentiles(fParams.getPercentiles())[0];
				} else {
					value = accumulator.getSingleValue(fParams.getFunction());
				}
				labelValues.add(new LabelAndValue(fBuckets.get(b).fLabel, value));
			}

			if (fParams.getLimit() < labelValues.size()) {
				labelValues.sort(Comparator
						.<LabelAndValue>comparingDouble(e -> {
							double value = e.value.doubleValue();
							return Double.isNaN(value) ? Double.NEGATIVE_INFINITY : value;
						})
						.reversed()
						.thenComparing(e -> e.label));
				labelValues = labelValues.subList(0, fParams.getLimit());
			}

			return new FacetResult(fParams.getDimension(), new String[0], totalCount,
					labelValues.toArray(LabelAndValue[]::new), labelValues.size());
		}
	}

	/** Formats a percentile argument for use in labels and dimension names. */
	static String formatPercentile(BigDecimal value) {
		return value.stripTrailingZeros().toPlainString();
	}

	/**
	 * A merging t-digest (Dunning's algorithm, k1 scale function) used for
	 * percentile estimation once an accumulator outgrows the exact limit.
	 * Memory is bounded by the compression constant; the rank error is a
	 * small fraction of q(1-q), i.e. tightest at the distribution tails,
	 * which is where percentile queries usually point. The exact minimum and
	 * maximum are tracked separately so q=0 and q=1 stay exact.
	 */
	private static class TDigest {
		private static final double COMPRESSION = 200;
		private static final int BUFFER_SIZE = 4096;

		private final double[] fBuffer = new double[BUFFER_SIZE];
		private int fBufferSize;
		private double[] fMeans = new double[0];
		private double[] fWeights = new double[0];
		private int fCentroidCount;
		private double fCentroidWeight;
		private double fMin = Double.POSITIVE_INFINITY;
		private double fMax = Double.NEGATIVE_INFINITY;

		void add(double value) {
			if (fBufferSize == BUFFER_SIZE) {
				merge();
			}
			fBuffer[fBufferSize++] = value;
			if (value < fMin) {
				fMin = value;
			}
			if (value > fMax) {
				fMax = value;
			}
		}

		/** The k1 scale function; adjacent centroids merge while their k-span is at most 1. */
		private static double scale(double q) {
			return COMPRESSION / (2 * Math.PI) * Math.asin(2 * Math.min(Math.max(q, 0), 1) - 1);
		}

		/** Merges the buffered values into the centroid list and re-compresses it. */
		private void merge() {
			if (fBufferSize == 0) {
				return;
			}
			java.util.Arrays.sort(fBuffer, 0, fBufferSize);

			double total = fCentroidWeight + fBufferSize;
			double[] means = new double[(int) (2 * COMPRESSION) + 16];
			double[] weights = new double[means.length];
			int count = 0;

			int i = 0;
			int j = 0;
			double currentMean = 0;
			double currentWeight = 0;
			double weightSoFar = 0;
			double qLeft = 0;
			boolean first = true;
			while (i < fCentroidCount || j < fBufferSize) {
				double mean;
				double weight;
				if (i < fCentroidCount && (j >= fBufferSize || fMeans[i] <= fBuffer[j])) {
					mean = fMeans[i];
					weight = fWeights[i];
					i++;
				} else {
					mean = fBuffer[j];
					weight = 1;
					j++;
				}

				if (first) {
					currentMean = mean;
					currentWeight = weight;
					first = false;
					continue;
				}

				double qRight = (weightSoFar + currentWeight + weight) / total;
				if (scale(qRight) - scale(qLeft) <= 1 && count < means.length - 1) {
					currentMean += (mean - currentMean) * weight / (currentWeight + weight);
					currentWeight += weight;
				} else {
					means[count] = currentMean;
					weights[count] = currentWeight;
					count++;
					weightSoFar += currentWeight;
					qLeft = weightSoFar / total;
					currentMean = mean;
					currentWeight = weight;
				}
			}
			if (!first) {
				means[count] = currentMean;
				weights[count] = currentWeight;
				count++;
			}

			fMeans = means;
			fWeights = weights;
			fCentroidCount = count;
			fCentroidWeight = total;
			fBufferSize = 0;
		}

		/**
		 * Estimates the value at quantile {@code q} (0..1) by linear
		 * interpolation between the centroid midpoints, anchored at the exact
		 * minimum and maximum.
		 */
		double quantile(double q) {
			merge();
			if (fCentroidCount == 0) {
				return Double.NaN;
			}
			if (fCentroidCount == 1) {
				return fMeans[0];
			}

			double index = q * fCentroidWeight;
			double cumulative = 0;
			double previousPosition = 0;
			double previousMean = fMin;
			for (int i = 0; i < fCentroidCount; i++) {
				double position = cumulative + fWeights[i] / 2;
				if (index <= position) {
					double t = (position == previousPosition) ? 0
							: (index - previousPosition) / (position - previousPosition);
					return previousMean + t * (fMeans[i] - previousMean);
				}
				previousPosition = position;
				previousMean = fMeans[i];
				cumulative += fWeights[i];
			}
			double t = (fCentroidWeight == previousPosition) ? 1
					: (index - previousPosition) / (fCentroidWeight - previousPosition);
			return previousMean + Math.min(t, 1) * (fMax - previousMean);
		}
	}

}
