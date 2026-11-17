package com.lizhi.agv;

import com.lizhi.astart.map.GridMap;
import com.lizhi.utils.PageBounds;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GridMapAndPageBoundsTest {

    @Test
    void copyIsIsolatedFromTemplate() {
        GridMap template = new GridMap(false, 4, 5);
        template.setWeight(1, 1, -1);

        GridMap copy = template.copy();
        copy.setWeight(1, 1, 1);
        copy.setWeight(2, 3, -1);

        assertEquals(-1, template.getWeight(1, 1), "修改副本不能影响缓存模板");
        assertEquals(1, template.getWeight(2, 3));
        assertEquals(1, copy.getWeight(1, 1));
        assertEquals(-1, copy.getWeight(2, 3));
        assertEquals(5, copy.getWidth());
        assertEquals(4, copy.getHeight());
    }

    @Test
    void pageBounds() {
        assertEquals(1, PageBounds.page(0));
        assertEquals(1, PageBounds.page(-3));
        assertEquals(7, PageBounds.page(7));
        assertEquals(PageBounds.DEFAULT_PAGE_SIZE, PageBounds.pageSize(0));
        assertEquals(PageBounds.DEFAULT_PAGE_SIZE, PageBounds.pageSize(-1));
        assertEquals(50, PageBounds.pageSize(50));
        assertEquals(PageBounds.MAX_PAGE_SIZE, PageBounds.pageSize(1_000_000));
        PageBounds.requireKeywordLength(null, "taskCode");
        PageBounds.requireKeywordLength("x".repeat(64), "taskCode");
        assertThrows(IllegalArgumentException.class, () -> PageBounds.requireKeywordLength("x".repeat(65), "taskCode"));
    }
}
