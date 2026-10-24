package com.jfbg.scoreboard;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 分块写入：把 JS 传来的 base64 片段累积到内存，最后一次性落盘。
 * 避免单次 Binder 传输超过 1MB 限制（大文件导出会崩）。
 */
final class ExportBuffer {

    private final String fileName;
    private final String mimeType;
    private final List<byte[]> chunks = new ArrayList<>();
    private long total = 0L;

    ExportBuffer(String fileName, String mimeType) {
        this.fileName = fileName;
        this.mimeType = mimeType;
    }

    String getFileName() { return fileName; }
    String getMimeType() { return mimeType; }
    long getTotal() { return total; }

    void add(byte[] data) {
        if (data != null && data.length > 0) {
            chunks.add(data);
            total += data.length;
        }
    }

    byte[] drain() {
        ByteArrayOutputStream bos = new ByteArrayOutputStream((int) Math.min(total, Integer.MAX_VALUE));
        for (byte[] c : chunks) bos.write(c, 0, c.length);
        chunks.clear();
        return bos.toByteArray();
    }
}
