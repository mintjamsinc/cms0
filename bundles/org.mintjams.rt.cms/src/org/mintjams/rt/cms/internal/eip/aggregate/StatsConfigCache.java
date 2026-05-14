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

package org.mintjams.rt.cms.internal.eip.aggregate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import javax.jcr.Node;
import javax.jcr.Session;

import org.mintjams.jcr.util.JCRs;
import org.mintjams.rt.cms.internal.CmsService;
import org.mintjams.rt.cms.internal.security.CmsServiceCredentials;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Workspace-scoped cache of {@link StatsConfig} keyed by routeId.
 *
 * <p>Configs are loaded from {@code /etc/eip/stats-config/{routeId}.json} in
 * the underlying JCR workspace and cached in-memory for {@value #TTL_MILLIS}
 * milliseconds. Negative results (no config file) are cached for the same
 * duration to avoid hammering JCR on every aggregated record.
 *
 * <p>The cache is safe for concurrent access. Refreshes are serialized
 * per-routeId via {@link ConcurrentMap#compute(Object, java.util.function.BiFunction)}.
 */
public class StatsConfigCache {

	public static final long TTL_MILLIS = 60_000L;
	public static final String BASE_PATH = "/etc/eip/stats-config";

	private static final TypeReference<LinkedHashMap<String, Object>> MAP_TYPE =
			new TypeReference<LinkedHashMap<String, Object>>() {};

	private final String fWorkspaceName;
	private final ConcurrentMap<String, CacheEntry> fCache = new ConcurrentHashMap<>();
	private final ObjectMapper fMapper = new ObjectMapper();

	public StatsConfigCache(String workspaceName) {
		fWorkspaceName = workspaceName;
	}

	/**
	 * Return the cached config for {@code routeId}, refreshing if expired.
	 * Returns {@link StatsConfig#EMPTY} when no config file exists.
	 */
	public StatsConfig get(String routeId) {
		if (routeId == null || routeId.isEmpty()) {
			return StatsConfig.EMPTY;
		}

		long now = System.currentTimeMillis();
		CacheEntry entry = fCache.get(routeId);
		if (entry != null && now - entry.timestamp < TTL_MILLIS) {
			return entry.config;
		}

		CacheEntry refreshed = fCache.compute(routeId, (key, existing) -> {
			long t = System.currentTimeMillis();
			if (existing != null && t - existing.timestamp < TTL_MILLIS) {
				return existing;
			}
			return new CacheEntry(load(key), t);
		});
		return refreshed.config;
	}

	/**
	 * Drop the cached entry for {@code routeId}, forcing the next {@link #get(String)}
	 * to reload from JCR. Useful when callers know a config change just happened.
	 */
	public void invalidate(String routeId) {
		if (routeId != null) {
			fCache.remove(routeId);
		}
	}

	/**
	 * Drop all cached entries.
	 */
	public void invalidateAll() {
		fCache.clear();
	}

	@SuppressWarnings("unchecked")
	private StatsConfig load(String routeId) {
		String path = BASE_PATH + "/" + routeId + ".json";
		Session session = null;
		try {
			session = CmsService.getRepository().login(new CmsServiceCredentials(), fWorkspaceName);
			if (!session.nodeExists(path)) {
				return StatsConfig.EMPTY;
			}
			Node node = session.getNode(path);
			String json = JCRs.getContentAsString(node);
			if (json == null || json.isEmpty()) {
				return StatsConfig.EMPTY;
			}
			Map<String, Object> map = fMapper.readValue(json, MAP_TYPE);
			String route = stringValue(map.get("route"));
			List<String> dimensions = (List<String>) map.get("dimensions");
			List<String> indexedHeaders = (List<String>) map.get("indexedHeaders");
			return new StatsConfig(route, dimensions, indexedHeaders);
		} catch (Exception ex) {
			CmsService.getLogger(getClass()).warn(
					"Failed to load stats-config for route '{}': {}", routeId, ex.getMessage());
			return StatsConfig.EMPTY;
		} finally {
			if (session != null) {
				try {
					session.logout();
				} catch (Throwable ignore) {}
			}
		}
	}

	private static String stringValue(Object o) {
		return o == null ? null : o.toString();
	}

	private static final class CacheEntry {
		final StatsConfig config;
		final long timestamp;

		CacheEntry(StatsConfig config, long timestamp) {
			this.config = config;
			this.timestamp = timestamp;
		}
	}
}
