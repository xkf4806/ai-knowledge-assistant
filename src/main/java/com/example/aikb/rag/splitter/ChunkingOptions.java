package com.example.aikb.rag.splitter;

/**
 * 一次分块使用的参数：策略 + 目标字符数 + 重叠字符数。
 * 在构造时校验，避免非法参数悄悄流到下游。
 */
public record ChunkingOptions(ChunkingStrategy strategy, int chunkSize, int overlap) {

    public ChunkingOptions {
        if (strategy == null) {
            throw new IllegalArgumentException("strategy 不能为 null");
        }
        if (chunkSize <= 0) {
            throw new IllegalArgumentException("chunkSize 必须大于 0");
        }
        if (overlap < 0 || overlap >= chunkSize) {
            throw new IllegalArgumentException("overlap 必须落在 [0, chunkSize) 区间");
        }
    }

    public ChunkingOptions withStrategy(ChunkingStrategy newStrategy) {
        return new ChunkingOptions(newStrategy, chunkSize, overlap);
    }
}
