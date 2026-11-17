package com.lizhi.utils;

/**
 * 分页参数边界：page 至少为 1；pageSize 未传/非法时取默认值，超过上限按上限截断。
 * 防止 pageSize 传百万级把数据库和内存拖垮，也统一 page&lt;1 的行为（按第 1 页处理）。
 */
public final class PageBounds {

    public static final int DEFAULT_PAGE_SIZE = 10;
    public static final int MAX_PAGE_SIZE = 100;
    /** 模糊查询关键字长度上限（与 task_code 列宽一致） */
    public static final int MAX_KEYWORD_LENGTH = 64;

    private PageBounds() {
    }

    public static int page(int page) {
        return Math.max(1, page);
    }

    public static int pageSize(int pageSize) {
        if (pageSize < 1) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(pageSize, MAX_PAGE_SIZE);
    }

    /** 关键字超长时抛业务异常（统一返回 code=204 + msg） */
    public static void requireKeywordLength(String keyword, String name) {
        if (keyword != null && keyword.length() > MAX_KEYWORD_LENGTH) {
            throw new IllegalArgumentException(name + " 长度不能超过 " + MAX_KEYWORD_LENGTH);
        }
    }
}
