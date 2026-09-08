package com.tiku.util;

/**
 * 分页参数钳制（本地单用户应用：越界参数直接钳制而非报错）：
 * - page ≥ 1（page=0/负数曾导致错题本内存分页 subList 负数 → 500）
 * - size ∈ [1, 100]（size=0/超大防止 DB 分页异常与超大响应）
 */
public final class Paging {

    private Paging() {
    }

    public static int page(int page) {
        return Math.max(1, page);
    }

    public static int size(int size) {
        return Math.max(1, Math.min(size, 100));
    }
}
