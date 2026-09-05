package com.example.export.support;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 受控路径封装的最小单测：归一化 + startsWith 越界校验 + 每任务目录布局（纯路径断言，不落盘）。
 */
class ExportFileStoreTest {

    private final ExportFileStore store = new ExportFileStore("./data/export");
    private final Path root = Paths.get("./data/export").toAbsolutePath().normalize();

    @Test
    void resolve_keepsRelativePathInsideRoot() {
        assertEquals(root.resolve("7/export.xlsx"), store.resolve("7/export.xlsx"));
        // .. 没逃出 root 则 normalize 归位、放行
        assertEquals(root.resolve("6/export.xlsx"), store.resolve("7/../6/export.xlsx"));
        assertEquals(root.resolve("a/export.xlsx"), store.resolve("a/b/../export.xlsx"));
    }

    @Test
    void resolve_rejectsPathsEscapingRoot() {
        assertThrows(IllegalArgumentException.class, () -> store.resolve("../x"));
        assertThrows(IllegalArgumentException.class, () -> store.resolve("/etc/passwd"));
        assertThrows(IllegalArgumentException.class, () -> store.resolve("a/../../x"));
    }

    @Test
    void layout_perTaskSubdirAndSameParentForTmpFinal() {
        assertEquals(root.resolve("7/export.xlsx"), store.finalFile(7));
        assertEquals(root.resolve("7/export.xlsx.tmp"), store.tmpFile(7));
        // tmp 与 final 同目录（原子改名前提）
        assertEquals(store.tmpFile(7).getParent(), store.finalFile(7).getParent());
        // 每任务独立子目录：不同 jobId 的目录互不相同
        assertEquals(root.resolve("7"), store.taskDir(7));
        assertEquals(root.resolve("8"), store.taskDir(8));
        assertTrue(!store.taskDir(7).equals(store.taskDir(8)));
    }

    @Test
    void relativePath_isJobIdScoped() {
        assertEquals("7/export.xlsx", store.relativePath(7));
        assertEquals("123/export.xlsx", store.relativePath(123));
    }
}
