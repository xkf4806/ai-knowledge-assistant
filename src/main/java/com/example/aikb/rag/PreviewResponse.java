package com.example.aikb.rag;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/** 文档解析 + 分块预览结果（不调用 embedding，方便零成本对比参数）。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PreviewResponse {

    private String source;
    private String contentType;
    private int contentLength;
    private List<StrategyPreview> strategies;
}
