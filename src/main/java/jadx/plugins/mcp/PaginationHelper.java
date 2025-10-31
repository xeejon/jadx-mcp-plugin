package jadx.plugins.mcp;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 分页工具 - 统一的分页处理，支持完整的分页元数据和数据转换
 */
public final class PaginationHelper {

	private final int threshold;
	private final int defaultPageSize;

	public PaginationHelper(int threshold, int defaultPageSize) {
		this.threshold = threshold > 0 ? threshold : 500;
		this.defaultPageSize = defaultPageSize > 0 ? defaultPageSize : 100;
	}

	public PaginationHelper() {
		this(500, 100);
	}

	/**
	 * 完整分页处理 - 主要API方法
	 */
	public <T> Map<String, Object> handlePagination(List<T> data, String dataType,
													String itemsKey, Function<T, Object> itemTransformer) {
		if (data == null) {
			data = new ArrayList<>();
		}

		// 自动分页逻辑
		if (data.size() <= threshold) {
			// 不超过阈值，返回全部数据（应用转换）
			List<Object> transformedData = data.stream()
					.map(itemTransformer != null ? itemTransformer : Object::toString)
					.collect(Collectors.toList());

			Map<String, Object> result = new HashMap<>();
			result.put("type", dataType);
			result.put(itemsKey, transformedData);
			result.put("total", data.size());
			result.put("auto_paged", false);
			result.put("threshold", threshold);
			result.put("message", String.format("数据量(%d)未超过阈值(%d)，返回全部数据",
					data.size(), threshold));
			return result;
		} else {
			// 超过阈值，返回分页数据（第一页）
			Map<String, Object> pagedResult = paginateList(data, 1, defaultPageSize, itemTransformer);
			pagedResult.put("type", dataType);
			pagedResult.put("auto_paged", true);
			pagedResult.put("threshold", threshold);
			pagedResult.put("message", String.format("数据量(%d)超过阈值(%d)，已自动分页",
					data.size(), threshold));

			// 重命名data字段为itemsKey
			if (!itemsKey.equals("data") && pagedResult.containsKey("data")) {
				Object dataValue = pagedResult.get("data");
				pagedResult.remove("data");
				pagedResult.put(itemsKey, dataValue);
			}

			return pagedResult;
		}
	}

	/**
	 * 简化版分页处理（使用toString转换）
	 */
	public <T> Map<String, Object> handlePagination(List<T> data, String dataType, String itemsKey) {
		return handlePagination(data, dataType, itemsKey, Object::toString);
	}

	/**
	 * 手动分页方法（支持自定义页码和页数）
	 */
	public <T> Map<String, Object> paginateList(List<T> data, int page, int pageSize,
												Function<T, Object> itemTransformer) {
		if (data == null) {
			data = new ArrayList<>();
		}

		page = Math.max(1, page);
		pageSize = pageSize > 0 ? pageSize : defaultPageSize;

		int total = data.size();
		if (total == 0) {
			return buildEmptyResult();
		}

		int totalPages = (int) Math.ceil((double) total / pageSize);
		page = Math.min(page, totalPages);

		int start = (page - 1) * pageSize;
		int end = Math.min(start + pageSize, total);
		int offset = start;

		// 应用数据转换
		List<Object> transformedData = data.subList(start, end)
				.stream()
				.map(itemTransformer != null ? itemTransformer : Object::toString)
				.collect(Collectors.toList());

		return buildPaginationResponse(transformedData, total, offset, pageSize, page, totalPages);
	}

	/**
	 * 基于偏移量的分页
	 */
	public <T> Map<String, Object> paginateByOffset(List<T> data, int offset, int limit,
													Function<T, Object> itemTransformer) {
		if (data == null) {
			data = new ArrayList<>();
		}

		offset = Math.max(0, offset);
		limit = limit > 0 ? limit : defaultPageSize;

		int total = data.size();
		int startIndex = Math.min(offset, total);
		int endIndex = Math.min(startIndex + limit, total);
		boolean hasMore = endIndex < total;
		int nextOffset = hasMore ? endIndex : -1;

		// 应用数据转换
		List<Object> transformedData = data.subList(startIndex, endIndex)
				.stream()
				.map(itemTransformer != null ? itemTransformer : Object::toString)
				.collect(Collectors.toList());

		// 计算页面信息
		int currentPage = limit > 0 ? (offset / limit) + 1 : 1;
		int totalPages = limit > 0 ? (int) Math.ceil((double) total / limit) : 1;

		return buildPaginationResponse(transformedData, total, offset, limit, currentPage, totalPages);
	}

	// ======================== 响应构建方法 ========================

	private Map<String, Object> buildPaginationResponse(List<Object> data, int total, int offset,
														int limit, int currentPage, int totalPages) {
		boolean hasMore = currentPage < totalPages;
		int nextOffset = hasMore ? offset + limit : -1;

		Map<String, Object> result = new HashMap<>();
		result.put("data", data);

		Map<String, Object> pagination = new HashMap<>();
		pagination.put("total", total);
		pagination.put("offset", offset);
		pagination.put("has_more", hasMore);

		if (hasMore) {
			pagination.put("next_offset", nextOffset);
		}

		if (offset > 0) {
			int prevOffset = Math.max(0, offset - limit);
			pagination.put("prev_offset", prevOffset);
		}

		if (limit > 0) {
			pagination.put("current_page", currentPage);
			pagination.put("total_pages", totalPages);
			pagination.put("page_size", limit);
		}

		pagination.put("default_page_size", defaultPageSize);
		result.put("pagination", pagination);

		return result;
	}

	private Map<String, Object> buildEmptyResult() {
		Map<String, Object> result = new HashMap<>();
		result.put("data", new ArrayList<>());
		result.put("total", 0);
		result.put("message", "无数据");
		return result;
	}

	public Map<String, Object> getConfig() {
		Map<String, Object> config = new HashMap<>();
		config.put("threshold", threshold);
		config.put("default_page_size", defaultPageSize);
		return config;
	}


	/**
	 * 长字符串分页 - 按字符数分页
	 */
	public Map<String, Object> paginateLongString(String content, int page, int pageSize) {
		if (content == null) {
			content = "";
		}

		page = Math.max(1, page);
		pageSize = pageSize > 0 ? pageSize : defaultPageSize;

		int totalChars = content.length();
		if (totalChars == 0) {
			return buildEmptyStringResult();
		}

		int totalPages = (int) Math.ceil((double) totalChars / pageSize);
		page = Math.min(page, totalPages);

		int start = (page - 1) * pageSize;
		int end = Math.min(start + pageSize, totalChars);
		int offset = start;

		String pageContent = content.substring(start, end);

		return buildPaginationResponse(
				List.of(pageContent), // 包装成列表以复用现有方法
				totalChars,
				offset,
				pageSize,
				page,
				totalPages
		);
	}

	/**
	 * 长字符串自动分页 - 超过阈值自动分页
	 */
	public Map<String, Object> autoPaginateLongString(String content) {
		if (content == null) {
			content = "";
		}

		if (content.length() <= threshold) {
			Map<String, Object> result = new HashMap<>();
			result.put("content", content);
			result.put("total_chars", content.length());
			result.put("auto_paged", false);
			result.put("threshold", threshold);
			result.put("message", String.format("内容长度(%d)未超过阈值(%d)，返回全部内容",
					content.length(), threshold));
			return result;
		} else {
			Map<String, Object> pagedResult = paginateLongString(content, 1, defaultPageSize);
			pagedResult.put("auto_paged", true);
			pagedResult.put("threshold", threshold);
			pagedResult.put("message", String.format("内容长度(%d)超过阈值(%d)，已自动分页",
					content.length(), threshold));
			return pagedResult;
		}
	}

	/**
	 * 基于偏移量的字符串分页
	 */
	public Map<String, Object> paginateStringByOffset(String content, int offset, int pageSize) {
		if (content == null) {
			content = "";
		}

		offset = Math.max(0, offset);
		pageSize = pageSize > 0 ? pageSize : defaultPageSize;

		int totalChars = content.length();
		int startIndex = Math.min(offset, totalChars);
		int endIndex = Math.min(startIndex + pageSize, totalChars);
		boolean hasMore = endIndex < totalChars;
		int nextOffset = hasMore ? endIndex : -1;

		String pageContent = content.substring(startIndex, endIndex);

		int currentPage = pageSize > 0 ? (offset / pageSize) + 1 : 1;
		int totalPages = pageSize > 0 ? (int) Math.ceil((double) totalChars / pageSize) : 1;

		return buildPaginationResponse(
				List.of(pageContent),
				totalChars,
				offset,
				pageSize,
				currentPage,
				totalPages
		);
	}

	private Map<String, Object> buildEmptyStringResult() {
		Map<String, Object> result = new HashMap<>();
		result.put("content", "");
		result.put("total_chars", 0);
		result.put("message", "无内容");
		return result;
	}
}
