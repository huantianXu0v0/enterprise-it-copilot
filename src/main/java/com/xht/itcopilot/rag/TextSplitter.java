package com.xht.itcopilot.rag;

import java.util.ArrayList;
import java.util.List;

/*
    对 文档 进行分块
 */
public class TextSplitter {

    public List<String> split(String text, int chunkSize, int overlap) {
        if (chunkSize <= 0 || overlap < 0 || overlap >= chunkSize) {
            throw new IllegalArgumentException(
                    "chunkSize < 0 or chunkSize < overlap");
        }
        if (text == null || text.isBlank()) {
            return List.of();
        }

        List<String> chunks = new ArrayList<>();
        int start = 0;

        while (start < text.length()) {
            int end = Math.min(start + chunkSize, text.length());
            chunks.add(text.substring(start, end));

            if (end == text.length()) {
                break;
            }
            start = end - overlap;
        }
        return chunks;
    }
}