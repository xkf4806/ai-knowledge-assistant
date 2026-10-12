package com.example.aikb.agent.plan;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.List;

/**
 * 一次提问的任务规划。
 *
 * <p>里「这一轮要调哪些工具、按什么顺序调」完全交给模型临场决定，过程不可控也不好解释。
 * 加了一层轻量规划器：先用规则把问题拆成有序步骤，再把步骤写进提示词、把可调用工具
 * 收敛到「这一轮真正可能用到的几个」。这样既让「多步任务」有据可依，也顺手收紧了工具调用权限。
 *
 * @param question  产生该计划的原始问题
 * @param planned   是否真的识别出了任务（false 表示退回「全部工具交给模型」）
 * @param steps     有序的执行步骤
 * @param tools     计划内用到的工具名（去重，供上层裁剪可调用工具）
 * @param rationale 为什么这样规划，给人和模型看的说明
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Plan(
        String question,
        boolean planned,
        List<Step> steps,
        List<String> tools,
        String rationale) {

    /**
     * 计划中的一步。
     *
     * @param order  执行顺序，从 1 开始
     * @param action 这一步要做什么
     * @param tool   这一步要调用的工具名；纯汇总步骤为 null
     * @param detail 该步骤的补充说明
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Step(int order, String action, String tool, String detail) {
    }

    /** 没识别出任务（或规划器被关闭）时的空计划：上层会退回「把全部工具交给模型」。 */
    public static Plan none(String question, String rationale) {
        return new Plan(question, false, List.of(), List.of(), rationale);
    }

    @JsonIgnore
    public boolean isEmpty() {
        return steps.isEmpty();
    }
}
