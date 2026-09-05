package com.example.export.support;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * 导出文件存储 —— 受控根目录(root=export.file-dir,配置管理)下的按任务隔离 + 越界守卫。
 *
 * <p>文件布局：root/&lt;jobId&gt;/export.xlsx.tmp →(原子改名)→ root/&lt;jobId&gt;/export.xlsx。
 * jobId 为 DB 自增 Long，天然安全；动态文件名绝不用用户输入(export_jobs.filename 仅供展示)。
 * 写盘/清理/(将来)下载一律经本类构造与解析路径；{@link #resolve} 供消费 DB 存回相对路径时做越界校验。
 */
public final class ExportFileStore {

    private static final Logger log = LoggerFactory.getLogger(ExportFileStore.class);

    private static final String FINAL_NAME = "export.xlsx";
    private static final String TMP_NAME = FINAL_NAME + ".tmp";

    /** 受控根目录（绝对 + 归一化一次，resolve 判定基准） */
    private final Path root;

    public ExportFileStore(String rootDir) {
        this.root = Paths.get(rootDir).toAbsolutePath().normalize();
    }

    /** 某任务独立目录 root/&lt;jobId&gt;/ */
    public Path taskDir(long jobId) {
        return root.resolve(String.valueOf(jobId));
    }

    /** 写盘中间态 root/&lt;jobId&gt;/export.xlsx.tmp（与 finalFile 同目录，保证原子改名前提） */
    public Path tmpFile(long jobId) {
        return taskDir(jobId).resolve(TMP_NAME);
    }

    /** 对外完整产物 root/&lt;jobId&gt;/export.xlsx */
    public Path finalFile(long jobId) {
        return taskDir(jobId).resolve(FINAL_NAME);
    }

    /** 入库/回写的相对路径(相对 root)：恒 "jobId/export.xlsx" */
    public String relativePath(long jobId) {
        return jobId + "/" + FINAL_NAME;
    }

    /**
     * 把相对路径在受控 root 下解析并校验：越出 root 抛 IllegalArgumentException。
     * 写入/清理/(将来)下载统一走这里。核心就是 resolve → normalize → startsWith 判定。
     * 将来真做下载、sending 前若担心符号链接，可再对已存在文件 toRealPath() 后二次 startsWith（本期不实现）。
     */
    public Path resolve(String relative) {
        Path p = root.resolve(relative).normalize();
        if (!p.startsWith(root)) {
            throw new IllegalArgumentException("导出文件路径越界, 拒绝: " + relative);
        }
        return p;
    }

    /** best-effort 删除某任务整个目录(失败清理/将来清扫用)；删不掉只记日志不抛。 */
    public void deleteTaskDir(long jobId) {
        Path dir = taskDir(jobId);
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    log.warn("清理导出任务目录失败: file={}, reason={}", p, e.getMessage());
                }
            });
        } catch (IOException e) {
            log.warn("清理导出任务目录失败: dir={}, reason={}", dir, e.getMessage());
        }
    }
}
