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

package org.mintjams.rt.cms.internal.graphql.type;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.mintjams.rt.cms.internal.util.ISO8601;

import graphql.GraphQLContext;
import graphql.execution.CoercedVariables;
import graphql.language.ArrayValue;
import graphql.language.BooleanValue;
import graphql.language.EnumValue;
import graphql.language.FloatValue;
import graphql.language.IntValue;
import graphql.language.NullValue;
import graphql.language.ObjectField;
import graphql.language.ObjectValue;
import graphql.language.StringValue;
import graphql.language.Value;
import graphql.schema.Coercing;
import graphql.schema.CoercingParseLiteralException;
import graphql.schema.CoercingParseValueException;
import graphql.schema.GraphQLScalarType;

/**
 * Custom GraphQL scalar implementations made available to application-defined
 * schemas. A schema only needs to declare the matching {@code scalar Foo} in
 * its SDL; {@link #forName(String)} supplies the coercing.
 *
 * <p>The names mirror the scalars used by the platform's own GraphQL contract
 * ({@code documents/GRAPHQL_SCHEMA.graphql}): {@code DateTime}, {@code Long},
 * {@code JSON}, {@code Base64}. Any other custom scalar a schema declares is
 * wired to a permissive pass-through ({@link #passThrough(String)}) so that a
 * schema author can introduce a scalar without having to register Java code —
 * values flow through unchanged in both directions.
 */
public final class ScalarTypes {

	private ScalarTypes() {}

	/**
	 * Returns the built-in coercing for one of the well-known scalar names, or
	 * {@code null} if the name is not one this class specialises (the caller
	 * then falls back to {@link #passThrough(String)}).
	 */
	public static GraphQLScalarType forName(String name) {
		if ("Long".equals(name)) {
			return longType();
		}
		if ("DateTime".equals(name)) {
			return dateTimeType();
		}
		if ("Base64".equals(name)) {
			// Carried as an opaque string: standard base64 text already.
			return stringType(name);
		}
		if ("JSON".equals(name)) {
			return passThrough(name);
		}
		return null;
	}

	/**
	 * The {@code DateTime} scalar, the type-level counterpart of
	 * {@link ISO8601}: the single point that enforces the ISO-8601 UTC wire
	 * contract for every date-time field, regardless of which resolver
	 * produced or consumed the value.
	 *
	 * <ul>
	 * <li><b>Output</b> ({@code serialize}) canonicalises any temporal value
	 * (Instant / Date / Calendar / OffsetDateTime / ZonedDateTime, an epoch
	 * millisecond {@code Number}, or an already-formatted string) through
	 * {@link ISO8601#format}, so fractional seconds are always three digits and
	 * the offset is always {@code Z} even if a resolver returned
	 * {@code Instant.toString()} or a raw temporal object. It never throws: an
	 * unrecognised value passes through as its string form, so a resolver in an
	 * application-defined schema (this scalar is shared by every compiled
	 * schema) is never turned into a hard query error.</li>
	 * <li><b>Input</b> ({@code parseValue} / {@code parseLiteral}) requires an
	 * ISO-8601 date-time with an explicit offset and rejects anything else as
	 * a client error, so a malformed value fails at the type boundary with a
	 * clear message instead of being passed through and mishandled (or
	 * silently ignored) by an individual resolver.</li>
	 * </ul>
	 */
	private static GraphQLScalarType dateTimeType() {
		return GraphQLScalarType.newScalar().name("DateTime")
				.description("ISO-8601 date-time string in UTC, e.g. 2026-07-06T09:30:00.000Z")
				.coercing(new Coercing<String, String>() {
					@Override
					public String serialize(Object input, GraphQLContext context, Locale locale) {
						if (input == null) {
							return null;
						}
						if (input instanceof Instant) {
							return ISO8601.format((Instant) input);
						}
						if (input instanceof Date) {
							return ISO8601.format((Date) input);
						}
						if (input instanceof Calendar) {
							return ISO8601.format((Calendar) input);
						}
						if (input instanceof OffsetDateTime) {
							return ISO8601.format(((OffsetDateTime) input).toInstant());
						}
						if (input instanceof ZonedDateTime) {
							return ISO8601.format(((ZonedDateTime) input).toInstant());
						}
						if (input instanceof Number) {
							// A bare number is treated as epoch milliseconds (the
							// platform's convention), canonicalised rather than
							// emitted as raw digits.
							return ISO8601.format(Instant.ofEpochMilli(((Number) input).longValue()));
						}
						// Any other value (an already-formatted string, or a
						// temporal type without an unambiguous instant such as
						// LocalDateTime): re-emit in the canonical form when it
						// parses as an offset-bearing ISO-8601 string, otherwise
						// pass its string form through unchanged. Output coercion
						// never throws — it must not turn a resolver's value into a
						// hard query error (the previous pass-through never did),
						// which matters because this scalar is shared by every
						// compiled schema, including application-defined ones.
						String text = input.toString();
						try {
							return ISO8601.format(ISO8601.parseInstant(text));
						} catch (RuntimeException notIso) {
							return text;
						}
					}

					@Override
					public String parseValue(Object input, GraphQLContext context, Locale locale) {
						if (input == null) {
							return null;
						}
						String text = input.toString();
						try {
							OffsetDateTime.parse(text);
						} catch (DateTimeParseException e) {
							throw new CoercingParseValueException(dateTimeInputError(text), e);
						}
						return text;
					}

					@Override
					public String parseLiteral(Value<?> input, CoercedVariables variables, GraphQLContext context, Locale locale) {
						if (input == null || input instanceof NullValue) {
							return null;
						}
						if (input instanceof StringValue) {
							String text = ((StringValue) input).getValue();
							try {
								OffsetDateTime.parse(text);
							} catch (DateTimeParseException e) {
								throw new CoercingParseLiteralException(dateTimeInputError(text), e);
							}
							return text;
						}
						throw new CoercingParseLiteralException(
								"DateTime literal must be a string; got " + input.getClass().getSimpleName());
					}
				}).build();
	}

	private static String dateTimeInputError(String value) {
		return "DateTime must be an ISO-8601 date-time with an explicit offset"
				+ " (e.g. 2026-07-06T09:30:00.000Z); got: " + value;
	}

	private static GraphQLScalarType longType() {
		return GraphQLScalarType.newScalar().name("Long").description("64-bit integer")
				.coercing(new Coercing<Long, Long>() {
					@Override
					public Long serialize(Object input, GraphQLContext context, Locale locale) {
						if (input instanceof Number) {
							return ((Number) input).longValue();
						}
						if (input instanceof String) {
							try {
								return Long.parseLong(((String) input).trim());
							} catch (NumberFormatException ignore) {}
						}
						return null;
					}

					@Override
					public Long parseValue(Object input, GraphQLContext context, Locale locale) {
						return serialize(input, context, locale);
					}

					@Override
					public Long parseLiteral(Value<?> input, CoercedVariables variables, GraphQLContext context, Locale locale) {
						if (input instanceof IntValue) {
							return ((IntValue) input).getValue().longValue();
						}
						if (input instanceof StringValue) {
							try {
								return Long.parseLong(((StringValue) input).getValue().trim());
							} catch (NumberFormatException ignore) {}
						}
						return null;
					}
				}).build();
	}

	private static GraphQLScalarType stringType(String name) {
		return GraphQLScalarType.newScalar().name(name)
				.coercing(new Coercing<String, String>() {
					@Override
					public String serialize(Object input, GraphQLContext context, Locale locale) {
						return (input == null) ? null : input.toString();
					}

					@Override
					public String parseValue(Object input, GraphQLContext context, Locale locale) {
						return (input == null) ? null : input.toString();
					}

					@Override
					public String parseLiteral(Value<?> input, CoercedVariables variables, GraphQLContext context, Locale locale) {
						if (input instanceof StringValue) {
							return ((StringValue) input).getValue();
						}
						return null;
					}
				}).build();
	}

	/**
	 * A scalar that lets arbitrary JSON-shaped values pass through unchanged.
	 * Output values are returned as-is for the JSON serialiser; input values
	 * (variables and inline literals) are converted to plain Java
	 * {@code Map}/{@code List}/scalar structures.
	 */
	public static GraphQLScalarType passThrough(String name) {
		return GraphQLScalarType.newScalar().name(name).description("Arbitrary JSON value")
				.coercing(new Coercing<Object, Object>() {
					@Override
					public Object serialize(Object input, GraphQLContext context, Locale locale) {
						return input;
					}

					@Override
					public Object parseValue(Object input, GraphQLContext context, Locale locale) {
						return input;
					}

					@Override
					public Object parseLiteral(Value<?> input, CoercedVariables variables, GraphQLContext context, Locale locale) {
						return literalToJava(input);
					}
				}).build();
	}

	static Object literalToJava(Value<?> value) {
		if (value == null || value instanceof NullValue) {
			return null;
		}
		if (value instanceof StringValue) {
			return ((StringValue) value).getValue();
		}
		if (value instanceof BooleanValue) {
			return ((BooleanValue) value).isValue();
		}
		if (value instanceof IntValue) {
			BigInteger v = ((IntValue) value).getValue();
			long l = v.longValue();
			if (BigInteger.valueOf(l).equals(v)) {
				return l;
			}
			return v;
		}
		if (value instanceof FloatValue) {
			BigDecimal v = ((FloatValue) value).getValue();
			return v.doubleValue();
		}
		if (value instanceof EnumValue) {
			return ((EnumValue) value).getName();
		}
		if (value instanceof ArrayValue) {
			List<Object> list = new ArrayList<>();
			for (Value<?> item : ((ArrayValue) value).getValues()) {
				list.add(literalToJava(item));
			}
			return list;
		}
		if (value instanceof ObjectValue) {
			Map<String, Object> map = new LinkedHashMap<>();
			for (ObjectField field : ((ObjectValue) value).getObjectFields()) {
				map.put(field.getName(), literalToJava(field.getValue()));
			}
			return map;
		}
		return null;
	}

}
