package com.xht.itcopilot.rag;

import com.xht.itcopilot.entity.ChunkPreview;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class StructuredTextSplitter {

    private final TextSplitter textSplitter = new TextSplitter();

    // 遇到 表格 或 代码块, 会单独 置为一个 chunk
    public List<ChunkPreview> split(String xhtml, int chunkSize, int overlap) {

        List<ChunkPreview> chunks = new ArrayList<>();
        StringBuilder text = new StringBuilder();

        // 从正文开始，按原文顺序遍历。
        walk(Jsoup.parse(xhtml).body(), text, chunks, chunkSize, overlap);

        // 处理文档末尾剩余的普通文本。
        flushText(text, chunks, chunkSize, overlap);

        return chunks;
    }

    private void walk(Node node, StringBuilder text, List<ChunkPreview> chunks, int chunkSize, int overlap) {

        if (node instanceof TextNode textNode) {
            text.append(textNode.getWholeText());
            return;
        }

        if (!(node instanceof Element element)) {
            return;
        }

        String tag = element.normalName();

        if ("script".equals(tag) || "style".equals(tag)) {
            return;
        }

        // 表格、代码块：先切前面的文本，再整体保留当前节点。
        if ("table".equals(tag) || "pre".equals(tag)) {
            flushText(text, chunks, chunkSize, overlap);

            boolean table = "table".equals(tag);
            String content = table
                    ? element.outerHtml()   // 保留表格标签和行列关系。
                    : element.wholeText();  // 保留代码缩进、换行。

            if (!content.isBlank()) {
                chunks.add(new ChunkPreview(
                        chunks.size(),
                        table ? "TABLE" : "CODE",
                        content));
            }

            // 不再遍历内部，避免重复提取表格单元格、代码内容。
            return;
        }

        if ("br".equals(tag)) {
            text.append('\n');
            return;
        }

        // 保留段落边界，防止前后文字粘连。
        if (element.isBlock()) {
            text.append('\n');
        }

        for (Node child : element.childNodes()) {
            walk(child, text, chunks, chunkSize, overlap);
        }

        if (element.isBlock()) {
            text.append('\n');
        }
    }

    private void flushText(StringBuilder text, List<ChunkPreview> chunks, int chunkSize, int overlap) {

        String content = text.toString().strip(); // 取出 缓存区的 文字
        text.setLength(0);

        // 调用你已有的固定大小 + overlap 分块实现。
        for (String piece : textSplitter.split(content, chunkSize, overlap)) {
            chunks.add(new ChunkPreview(chunks.size(), "TEXT", piece));
        }
    }
}