package com.example.aikb.rag;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/** 某一种分块策略在给定参数下的切分结果。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class StrategyPreview {

    private String strategy;
    private int chunkSize;
    private int overlap;
    private int chunkCount;
    private List<ChunkPreview> chunks;
}
