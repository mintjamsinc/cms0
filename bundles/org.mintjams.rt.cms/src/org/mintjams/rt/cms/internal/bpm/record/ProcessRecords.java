/*
 * Copyright (c) 2026 MintJams Inc.
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

package org.mintjams.rt.cms.internal.bpm.record;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.camunda.bpm.engine.ProcessEngine;
import org.camunda.bpm.engine.ProcessEngineConfiguration;
import org.camunda.bpm.engine.history.HistoricVariableInstance;
import org.camunda.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.camunda.bpm.engine.impl.context.Context;
import org.camunda.bpm.engine.impl.history.HistoryLevel;
import org.camunda.bpm.engine.impl.interceptor.CommandContext;
import org.camunda.bpm.engine.impl.persistence.entity.CommentEntity;
import org.camunda.bpm.engine.impl.persistence.entity.HistoricProcessInstanceEntity;
import org.camunda.bpm.engine.impl.util.ClockUtil;
import org.camunda.bpm.engine.task.Comment;
import org.camunda.bpm.model.bpmn.BpmnModelInstance;
import org.camunda.bpm.model.bpmn.instance.BaseElement;
import org.camunda.bpm.model.bpmn.instance.ExtensionElements;
import org.camunda.bpm.model.bpmn.instance.camunda.CamundaProperties;
import org.camunda.bpm.model.bpmn.instance.camunda.CamundaProperty;
import org.mintjams.rt.cms.internal.util.ISO8601;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;

/**
 * What a process says about itself to the people in it: the app it belongs to,
 * its subject, and the record of who did what in it, with the body written at
 * each step.
 *
 * <p>A BPMN declares them as Camunda extension properties:
 *
 * <pre>
 *   &lt;bpmn:process id="..."&gt;
 *     &lt;bpmn:extensionElements&gt;&lt;camunda:properties&gt;
 *       &lt;camunda:property name="app" value="site"/&gt;
 *       &lt;camunda:property name="subject" value="Publication request: {{title}}"/&gt;
 *     &lt;/camunda:properties&gt;&lt;/bpmn:extensionElements&gt;
 *   &lt;bpmn:startEvent id="..."&gt;     ... &lt;camunda:property name="record" value="true"/&gt;
 *   &lt;bpmn:boundaryEvent id="..."&gt;  ... &lt;camunda:property name="body" value="{{shipErrorMessage}}"/&gt;
 * </pre>
 *
 * <p>What people write - a note, the comment given with a decision - is not a
 * body: it is kept as a comment ({@code TaskService.createComment}), which
 * {@link #trail} reads beside the records.
 *
 * <p>A subject or body is a template: text, with {@code {{name}}} where the
 * value of the process variable {@code name} goes - the double braces the
 * formKey tokens use, which the engine's own {@code ${...}} expressions leave
 * alone. The template written in the BPMN is the text in the language it is
 * written in; the reader's Webtop shows the message of the convention key
 * ({@code bpm.process.<key>.subject}, {@code ...task.<id>.body},
 * {@code ...event.<id>.body}) in the reader's language instead, when a bundle
 * has one, and puts the values in, dates in the reader's time zone. So the
 * server hands out the template and the values, never a finished text.
 *
 * <p>A record is kept when the engine passes a step that declares a body: a
 * start event (the process starts), a user task (it is completed), a throw or
 * end event (it is reached: how a BPMN names an outcome) or a catch event; a
 * message event or a receive task is always recorded, since that is where
 * somebody acted on the process from outside. A step that declares
 * {@code record} = {@code true} is recorded the same way without words (an
 * outcome whose comment was written at the task before it). A record holds who did it
 * (the authenticated user, else the task's assignee), when, the step, the body
 * template as written and the values of the variables it names at that moment.
 * Records are kept as Camunda comments of the process instance (action
 * {@value #ACTION}), so they live and are cleaned up with the rest of its
 * history; at history level {@code none} nothing is recorded.
 */
public final class ProcessRecords {

	/** The comment action that marks a record among the comments of a process instance. */
	public static final String ACTION = "WebtopRecord";

	public static final String PROPERTY_APP = "app";
	public static final String PROPERTY_SUBJECT = "subject";
	public static final String PROPERTY_BODY = "body";
	public static final String PROPERTY_RECORD = "record";

	/** {@code {{name}}}: a letter or underscore, then letters, digits or underscores; spaces inside the braces allowed. */
	private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*\\}\\}");

	/** The longest text a value keeps in a record or a subject. */
	private static final int MAX_VALUE_LENGTH = 4000;

	/** A comment's MESSAGE_ column: what other readers of the comments see. */
	private static final int MAX_SHORT_MESSAGE = 160;

	private static final Gson GSON = new Gson();

	/** The process-level properties of a definition, by its id (definitions never change). */
	private static final Map<String, Map<String, String>> PROCESS_PROPERTIES = new ConcurrentHashMap<>();

	private ProcessRecords() {}

	// ---- templates ---------------------------------------------------------

	/** The names of the variables a template puts in, in order of appearance. */
	public static Set<String> names(String template) {
		Set<String> names = new LinkedHashSet<>();
		if (template == null || template.indexOf("{{") < 0) {
			return names;
		}
		Matcher m = PLACEHOLDER.matcher(template);
		while (m.find()) {
			names.add(m.group(1));
		}
		return names;
	}

	/**
	 * The values a template shows, as {@code name -> {type, value}}; type is
	 * {@code string}, {@code number}, {@code boolean} or {@code date} (an ISO
	 * 8601 instant). A variable that is not set is left out.
	 */
	public static Map<String, Object> params(String template, Function<String, Object> lookup) {
		Map<String, Object> params = new LinkedHashMap<>();
		for (String name : names(template)) {
			Object value;
			try {
				value = lookup.apply(name);
			} catch (Exception ex) {
				value = null;
			}
			Map<String, Object> typed = typed(value);
			if (typed != null) {
				params.put(name, typed);
			}
		}
		return params;
	}

	private static Map<String, Object> typed(Object value) {
		if (value == null) {
			return null;
		}
		Map<String, Object> m = new LinkedHashMap<>();
		if (value instanceof Date) {
			m.put("type", "date");
			m.put("value", ISO8601.format((Date) value));
		} else if (value instanceof Boolean) {
			m.put("type", "boolean");
			m.put("value", value);
		} else if (value instanceof BigDecimal) {
			m.put("type", "number");
			m.put("value", ((BigDecimal) value).toPlainString());
		} else if (value instanceof BigInteger) {
			m.put("type", "number");
			m.put("value", value.toString());
		} else if (value instanceof Number) {
			m.put("type", "number");
			m.put("value", value);
		} else if (value instanceof byte[]) {
			// A bytes variable is how a script passes a text longer than a string variable holds.
			m.put("type", "string");
			m.put("value", clip(new String((byte[]) value, StandardCharsets.UTF_8)));
		} else if (value instanceof Collection) {
			List<String> parts = new ArrayList<>();
			for (Object o : (Collection<?>) value) {
				if (o != null) {
					parts.add(String.valueOf(o));
				}
			}
			m.put("type", "string");
			m.put("value", clip(String.join(", ", parts)));
		} else {
			m.put("type", "string");
			m.put("value", clip(String.valueOf(value)));
		}
		return m;
	}

	private static String clip(String s) {
		return (s.length() > MAX_VALUE_LENGTH) ? s.substring(0, MAX_VALUE_LENGTH - 1) + "…" : s;
	}

	/** A text as the Webtop renders it: the convention key, the template as written, and the values. */
	public static Map<String, Object> text(String key, String template, Map<String, Object> params) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("key", key);
		m.put("template", template);
		m.put("params", (params != null) ? params : new LinkedHashMap<>());
		return m;
	}

	public static String subjectKey(String processKey) {
		return "bpm.process." + processKey + ".subject";
	}

	/** The key of a step's body: a user task under {@code task.<id>}, every other step under {@code event.<id>}. */
	public static String bodyKey(String processKey, String activityType, String activityId) {
		return "bpm.process." + processKey + ("userTask".equals(activityType) ? ".task." : ".event.") + activityId + ".body";
	}

	// ---- declarations ------------------------------------------------------

	/** The extension properties of the process of a definition: app, subject. Empty when it has none. */
	public static Map<String, String> processProperties(ProcessEngine engine, String definitionId) {
		if (definitionId == null || definitionId.isEmpty()) {
			return Collections.emptyMap();
		}
		Map<String, String> cached = PROCESS_PROPERTIES.get(definitionId);
		if (cached != null) {
			return cached;
		}
		Map<String, String> props = new LinkedHashMap<>();
		try {
			String key = engine.getRepositoryService().getProcessDefinition(definitionId).getKey();
			BpmnModelInstance model = engine.getRepositoryService().getBpmnModelInstance(definitionId);
			BaseElement process = (model != null) ? model.getModelElementById(key) : null;
			if (process != null) {
				props.putAll(extensionProperties(process));
			}
		} catch (Exception ex) {
			// no such definition, or no model: nothing declared
		}
		Map<String, String> result = Collections.unmodifiableMap(props);
		PROCESS_PROPERTIES.put(definitionId, result);
		return result;
	}

	private static Map<String, String> extensionProperties(BaseElement element) {
		Map<String, String> props = new LinkedHashMap<>();
		ExtensionElements ext = element.getExtensionElements();
		if (ext == null) {
			return props;
		}
		for (CamundaProperties group : ext.getElementsQuery().filterByType(CamundaProperties.class).list()) {
			for (CamundaProperty p : group.getCamundaProperties()) {
				if (p.getCamundaName() != null && p.getCamundaValue() != null) {
					props.put(p.getCamundaName(), p.getCamundaValue());
				}
			}
		}
		return props;
	}

	/** The app a process belongs to (the relPath of a Webtop app), or null. */
	public static String app(ProcessEngine engine, String definitionId) {
		String app = processProperties(engine, definitionId).get(PROPERTY_APP);
		return (app == null || app.trim().isEmpty()) ? null : app.trim();
	}

	/**
	 * The subject of a process instance, with the values of the variables it
	 * names as they are now (as they were at the end, for one that ended).
	 * Null when the process declares no subject.
	 */
	public static Map<String, Object> subject(ProcessEngine engine, String definitionId, String definitionKey,
			String processInstanceId, boolean ended) {
		String template = processProperties(engine, definitionId).get(PROPERTY_SUBJECT);
		if (template == null || template.isEmpty()) {
			return null;
		}
		Set<String> names = names(template);
		Map<String, Object> values = names.isEmpty() ? Collections.emptyMap()
				: variables(engine, processInstanceId, names, ended);
		return text(subjectKey(definitionKey), template, params(template, values::get));
	}

	/** The values of the named process-level variables of an instance; history when it ended or has no runtime. */
	private static Map<String, Object> variables(ProcessEngine engine, String processInstanceId, Set<String> names,
			boolean ended) {
		if (processInstanceId == null) {
			return Collections.emptyMap();
		}
		if (!ended) {
			try {
				return engine.getRuntimeService().getVariables(processInstanceId, names);
			} catch (Exception ex) {
				// ended meanwhile: read the history
			}
		}
		Map<String, Object> values = new LinkedHashMap<>();
		try {
			for (HistoricVariableInstance v : engine.getHistoryService().createHistoricVariableInstanceQuery()
					.processInstanceId(processInstanceId).variableNameIn(names.toArray(new String[0])).list()) {
				// The process-level variable wins over one of the same name in a scope inside it.
				boolean processLevel = processInstanceId.equals(v.getActivityInstanceId());
				if (processLevel || !values.containsKey(v.getName())) {
					values.put(v.getName(), v.getValue());
				}
			}
		} catch (Exception ex) {
			// no variable history at this level
		}
		return values;
	}

	// ---- records -----------------------------------------------------------

	/**
	 * Keeps a record of a step, in the running command. {@code body} is the
	 * template the step declares (null when it declares none) and
	 * {@code lookup} finds the values of its variables in the step's scope.
	 */
	static void write(String processInstanceId, String rootProcessInstanceId, String taskId, String userId,
			String activityId, String activityType, String activityName, String body, Function<String, Object> lookup) {
		CommandContext commandContext = Context.getCommandContext();
		ProcessEngineConfigurationImpl configuration = Context.getProcessEngineConfiguration();
		if (commandContext == null || configuration == null) {
			return;
		}
		HistoryLevel level = configuration.getHistoryLevel();
		if (level == null || ProcessEngineConfiguration.HISTORY_NONE.equals(level.getName())) {
			return;
		}

		Map<String, Object> record = new LinkedHashMap<>();
		record.put("v", 1);
		record.put("activityId", activityId);
		record.put("activityType", activityType);
		record.put("activityName", activityName);
		record.put("body", body);
		record.put("params", (body != null) ? params(body, lookup) : new LinkedHashMap<>());

		CommentEntity comment = new CommentEntity();
		comment.setUserId(userId);
		comment.setType(CommentEntity.TYPE_COMMENT);
		comment.setTime(ClockUtil.getCurrentTime());
		comment.setTaskId(taskId);
		comment.setProcessInstanceId(processInstanceId);
		comment.setRootProcessInstanceId(rootProcessInstanceId);
		comment.setAction(ACTION);
		String shortMessage = (activityName != null && !activityName.isEmpty()) ? activityName : activityId;
		if (shortMessage == null) {
			shortMessage = "";
		}
		if (shortMessage.length() > MAX_SHORT_MESSAGE) {
			shortMessage = shortMessage.substring(0, MAX_SHORT_MESSAGE - 3) + "...";
		}
		comment.setMessage(shortMessage);
		comment.setFullMessage(GSON.toJson(record));
		if (ProcessEngineConfiguration.HISTORY_REMOVAL_TIME_STRATEGY_START
				.equals(configuration.getHistoryRemovalTimeStrategy()) && rootProcessInstanceId != null) {
			// Under the start strategy the removal time is fixed when the process
			// starts; under the end strategy the engine sets it on every entity of
			// the root instance when it ends, this one included.
			try {
				HistoricProcessInstanceEntity root = commandContext.getHistoricProcessInstanceManager()
						.findHistoricProcessInstance(rootProcessInstanceId);
				if (root != null) {
					comment.setRemovalTime(root.getRemovalTime());
				}
			} catch (Exception ignore) {
				// kept without a removal time
			}
		}
		commandContext.getCommentManager().insert(comment);
	}

	/**
	 * Whether the engine keeps comments - the comments people write
	 * ({@code TaskService.createComment}) and the records. Every history level
	 * but {@code none}: there the engine refuses to write or read them.
	 */
	public static boolean commentsKept(ProcessEngine engine) {
		try {
			HistoryLevel level = ((ProcessEngineConfigurationImpl) engine.getProcessEngineConfiguration()).getHistoryLevel();
			return level != null && !ProcessEngineConfiguration.HISTORY_NONE.equals(level.getName());
		} catch (Exception ex) {
			return false;
		}
	}

	/**
	 * What a process instance keeps in its comments, read with one query: the
	 * records of its steps and the comments people wrote, both oldest first.
	 */
	public static final class Trail {
		/** The records, as {@link #read} describes them. */
		public final List<Map<String, Object>> records;
		/** The comments people wrote: id, time, timeMillis, userId, taskId (null: on the process), message. */
		public final List<Map<String, Object>> comments;

		private Trail(List<Map<String, Object>> records, List<Map<String, Object>> comments) {
			this.records = records;
			this.comments = comments;
		}
	}

	/** The records of a process instance, oldest first, as maps (userDisplayName left to the caller). */
	public static List<Map<String, Object>> read(ProcessEngine engine, String processInstanceId, String definitionKey) {
		return trail(engine, processInstanceId, definitionKey).records;
	}

	/**
	 * The records and the comments of a process instance (userDisplayName left
	 * to the caller). A comment is what {@code TaskService.createComment} keeps:
	 * at a task (its taskId), or on the process itself (no taskId) - written
	 * when it started, when it was told something, after it ended.
	 */
	public static Trail trail(ProcessEngine engine, String processInstanceId, String definitionKey) {
		List<Map<String, Object>> records = new ArrayList<>();
		List<Map<String, Object>> written = new ArrayList<>();
		if (processInstanceId == null) {
			return new Trail(records, written);
		}
		List<Comment> comments;
		try {
			comments = engine.getTaskService().getProcessInstanceComments(processInstanceId);
		} catch (Exception ex) {
			// history level none: no comments
			return new Trail(records, written);
		}
		for (Comment c : comments) {
			if (!(c instanceof CommentEntity)) {
				continue;
			}
			CommentEntity entity = (CommentEntity) c;
			if (!ACTION.equals(entity.getAction())) {
				// What a person wrote; not an event the engine logs (type "event").
				if (!CommentEntity.TYPE_EVENT.equals(entity.getType()) && c.getFullMessage() != null) {
					Map<String, Object> m = new LinkedHashMap<>();
					m.put("id", c.getId());
					m.put("time", (c.getTime() != null) ? ISO8601.format(c.getTime()) : null);
					m.put("timeMillis", (c.getTime() != null) ? c.getTime().getTime() : 0L);
					m.put("userId", c.getUserId());
					m.put("taskId", c.getTaskId());
					m.put("message", c.getFullMessage());
					written.add(m);
				}
				continue;
			}
			Map<?, ?> stored;
			try {
				stored = GSON.fromJson(c.getFullMessage(), Map.class);
			} catch (JsonSyntaxException ex) {
				continue;
			}
			if (stored == null) {
				continue;
			}
			String activityId = asString(stored.get("activityId"));
			String activityType = asString(stored.get("activityType"));
			String body = asString(stored.get("body"));
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("id", c.getId());
			m.put("time", (c.getTime() != null) ? ISO8601.format(c.getTime()) : null);
			m.put("timeMillis", (c.getTime() != null) ? c.getTime().getTime() : 0L);
			m.put("userId", c.getUserId());
			m.put("taskId", c.getTaskId());
			m.put("activityId", activityId);
			m.put("activityType", activityType);
			m.put("activityName", asString(stored.get("activityName")));
			Object params = stored.get("params");
			m.put("body", (body != null)
					? text(bodyKey(definitionKey, activityType, activityId), body, (params instanceof Map) ? normalize((Map<?, ?>) params) : null)
					: null);
			records.add(m);
		}
		// Oldest first; the comments come newest first, and two in the same
		// millisecond keep the order they were written in.
		Collections.reverse(records);
		records.sort((a, b) -> Long.compare((Long) a.get("timeMillis"), (Long) b.get("timeMillis")));
		Collections.reverse(written);
		written.sort((a, b) -> Long.compare((Long) a.get("timeMillis"), (Long) b.get("timeMillis")));
		return new Trail(records, written);
	}

	/**
	 * True when a record, as {@link #read} returns it, has a body that says
	 * something: text of its own, or a variable that was set to something. A
	 * step recorded without words (an outcome named by an event, a comment left
	 * empty) has none.
	 */
	public static boolean hasBodyText(Map<String, Object> record) {
		Object body = (record != null) ? record.get("body") : null;
		if (!(body instanceof Map)) {
			return false;
		}
		String template = asString(((Map<?, ?>) body).get("template"));
		if (template == null || template.trim().isEmpty()) {
			return false;
		}
		Object params = ((Map<?, ?>) body).get("params");
		Matcher m = PLACEHOLDER.matcher(template);
		StringBuilder text = new StringBuilder();
		while (m.find()) {
			Object typed = (params instanceof Map) ? ((Map<?, ?>) params).get(m.group(1)) : null;
			Object value = (typed instanceof Map) ? ((Map<?, ?>) typed).get("value") : null;
			m.appendReplacement(text, Matcher.quoteReplacement((value != null) ? String.valueOf(value) : ""));
		}
		m.appendTail(text);
		return !text.toString().trim().isEmpty();
	}

	/** Gson reads numbers as doubles; a whole number goes back to being one. */
	private static Map<String, Object> normalize(Map<?, ?> params) {
		Map<String, Object> out = new LinkedHashMap<>();
		for (Map.Entry<?, ?> e : params.entrySet()) {
			Object v = e.getValue();
			if (v instanceof Map) {
				Map<String, Object> typed = new LinkedHashMap<>();
				for (Map.Entry<?, ?> f : ((Map<?, ?>) v).entrySet()) {
					Object x = f.getValue();
					if (x instanceof Double && ((Double) x) == Math.rint((Double) x) && !Double.isInfinite((Double) x)
							&& Math.abs((Double) x) < 9.007199254740992E15) {
						x = ((Double) x).longValue();
					}
					typed.put(String.valueOf(f.getKey()), x);
				}
				out.put(String.valueOf(e.getKey()), typed);
			}
		}
		return out;
	}

	private static String asString(Object o) {
		return (o == null) ? null : String.valueOf(o);
	}

}
