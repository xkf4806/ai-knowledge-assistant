package com.example.aikb.agent;

/**
 * 工具的对外描述：名字、说明、入参 JSON Schema。
 *
 * <p>把 Spring AI 内部生成的 Schema 直接暴露出来，一眼能看出「模型到底看到了什么工具」，
 * 演示「工具描述如何影响模型选择」非常好用，而且不需要调用模型、不花钱。
 */
public record ToolDescriptor(String name, String description, String inputSchema) {
}
