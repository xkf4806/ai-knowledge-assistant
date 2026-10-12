package com.example.aikb.agent.plan;

import com.example.aikb.agent.AgentProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AgentPlannerTest {

    private final AgentProperties properties = new AgentProperties();
    private final AgentPlanner planner = new AgentPlanner(properties);

    @Test
    void splitsComplexRefundAndStockTaskIntoOrderedSteps() {
        Plan plan = planner.plan("订单 A1003 能退货吗？如果能退，再看看 SKU-3003 现在还剩多少库存。");

        assertThat(plan.planned()).isTrue();
        assertThat(plan.tools()).containsExactly("lookupOrder", "searchKnowledgeBase", "checkInventory");
        // 3 个工具步骤 + 1 个汇总步骤
        assertThat(plan.steps()).hasSize(4);
        assertThat(plan.steps()).extracting(Plan.Step::order).containsExactly(1, 2, 3, 4);
        assertThat(plan.steps().get(0).tool()).isEqualTo("lookupOrder");
        assertThat(plan.steps().get(1).tool()).isEqualTo("searchKnowledgeBase");
        assertThat(plan.steps().get(2).tool()).isEqualTo("checkInventory");
        assertThat(plan.steps().get(3).tool()).isNull();
    }

    @Test
    void plansOrderAndPolicyForRefundQuestion() {
        Plan plan = planner.plan("订单 A1001 能不能退？");

        assertThat(plan.tools()).containsExactly("lookupOrder", "searchKnowledgeBase");
    }

    @Test
    void plansStockLookupFromSku() {
        Plan plan = planner.plan("SKU-2002 还有多少库存？");

        assertThat(plan.tools()).containsExactly("checkInventory");
        assertThat(plan.steps()).hasSize(1);
    }

    @Test
    void plansPriceQuoteForPurchaseQuestion() {
        Plan plan = planner.plan("买 2 件 SKU-3003 一共多少钱？");

        assertThat(plan.tools()).containsExactly("quotePrice");
    }

    @Test
    void plansKnowledgeLookupForPolicyQuestion() {
        Plan plan = planner.plan("入职满一年有几天年假？");

        assertThat(plan.tools()).containsExactly("searchKnowledgeBase");
    }

    @Test
    void returnsEmptyPlanWhenNothingIsRecognised() {
        Plan plan = planner.plan("你好呀");

        assertThat(plan.planned()).isFalse();
        assertThat(plan.isEmpty()).isTrue();
        assertThat(plan.tools()).isEmpty();
        assertThat(planner.promptHint(plan)).isEmpty();
    }

    @Test
    void returnsEmptyPlanWhenPlannerIsDisabled() {
        AgentProperties disabled = new AgentProperties();
        disabled.getPlanner().setEnabled(false);

        Plan plan = new AgentPlanner(disabled).plan("订单 A1001 能不能退？");

        assertThat(plan.planned()).isFalse();
        assertThat(plan.rationale()).contains("已关闭");
    }

    @Test
    void rendersPromptHintInStepOrder() {
        Plan plan = planner.plan("订单 A1003 能退货吗？再看看 SKU-3003 的库存。");

        String hint = planner.promptHint(plan);

        assertThat(hint).contains("本轮任务规划");
        assertThat(hint.indexOf("lookupOrder")).isLessThan(hint.indexOf("searchKnowledgeBase"));
        assertThat(hint.indexOf("searchKnowledgeBase")).isLessThan(hint.indexOf("checkInventory"));
    }
}
