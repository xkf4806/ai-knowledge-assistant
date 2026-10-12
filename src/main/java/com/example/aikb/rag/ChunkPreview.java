package com.example.aikb.rag;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 分块预览：只带片段摘要，方便对比不同策略的切分效果。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChunkPreview {

    private int index;
    private String heading;
    private int length;
    private String preview;
}
