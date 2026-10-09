package com.example.aikb.rag;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class IngestResponse {

    /** 成功索引的文档数 */
    private int documents;

    /** 写入向量库的片段总数 */
    private int chunks;

    /** 本次使用的分块策略 */
    private String strategy;

    /** 参与索引的文件名 */
    private List<String> sources;
}
