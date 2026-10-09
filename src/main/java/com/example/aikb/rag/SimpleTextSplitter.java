package com.example.aikb.rag;

import java.util.ArrayList;
import java.util.List;

/**
 * 最朴素的分块器：按固定字符数切分，相邻片段保留一段重叠。
 * 第 2 周会在此基础上加入按标题层级切分等更聪明的策略。
 */
public final class SimpleTextSplitter {

    private SimpleTextSplitter() {
    }

    public static List<String> split(String text, int chunkSize, int overlap) {
        if (chunkSize <= 0) {
            throw new IllegalArgumentException("chunkSize 必须大于 0");
        }
        if (overlap < 0 || overlap >= chunkSize) {
            throw new IllegalArgumentException("overlap 必须落在 [0, chunkSize) 区间");
        }

        String normalized = text.replace("\r\n", "\n").trim();
        List<String> chunks = new ArrayList<>();
        int start = 0;
        while (start < normalized.length()) {
            int end = Math.min(start + chunkSize, normalized.length());
            chunks.add(normalized.substring(start, end).trim());
            if (end == normalized.length()) {
                break;
            }
            start = end - overlap;
        }
        return chunks;
    }
}
