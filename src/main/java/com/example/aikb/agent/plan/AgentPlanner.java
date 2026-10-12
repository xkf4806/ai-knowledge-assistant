package com.example.aikb.agent.plan;

import com.example.aikb.agent.AgentProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 规则式任务规划器：把自然语言问题拆成「有序的几步」。
 *
 * <p>它刻意不调用模型，只用关键词与订单号模式做判断。好处很直接：
 * <ul>
 *   <li>不花钱、毫秒级、完全可测——规划逻辑可以用单元测试钉死；</li>
 *   <li>可解释——响应里的 {@code plan} 直接告诉人「系统打算怎么查」；</li>
 *   <li>收敛权限——只把计划内的工具交给模型，从源头减少「调错工具」。</li>
 * </ul>
 *
 * <p>它只做「粗粒度分步」这一件事：真正每一步怎么填参数、先查谁后查谁，仍然由模型在编排循环里决定。
 * 这正好对应 ReAct 里的「思考 → 行动 → 观察 → 再思考」——规划器给出「思考」的骨架，
 * 模型负责在骨架里跑完「行动 / 观察」。
 */
@Slf4j
@Component
public class AgentPlanner {

    /** 订单号形如 A1001，出现它通常意味着要先拿订单事实。 */
    private static final Pattern ORDER_ID = Pattern.compile("A\\d{4}", Pattern.CASE_INSENSITIVE);

    private static final List<String> ORDER_HINTS = List.of("订单", "单号", "物流", "签收");
    private static final List<String> POLICY_HINTS =
            List.of("退", "政策", "规定", "制度", "流程", "条款", "手册");
    private static final List<String> INVENTORY_HINTS =
            List.of("库存", "有货", "现货", "备货", "还剩", "仓库");
    private static final List<String> PRICE_HINTS =
            List.of("多少钱", "价格", "报价", "总价", "单价", "买");
    private static final List<String> KNOWLEDGE_HINTS =
            List.of("年假", "请假", "报销", "考勤", "试用期", "入职", "离职", "vpn", "密码", "公司");

    private final AgentProperties properties;

    public AgentPlanner(AgentProperties properties) {
        this.properties = properties;
    }

    /** 根据问题生成计划；识别不出任务时返回空计划。 */
    public Plan plan(String question) {
        if (!properties.getPlanner().isEnabled()) {
            return Plan.none(question, "规划器已关闭（app.agent.planner.enabled=false），全部工具交给模型自行决策。");
        }
        String text = question == null ? "" : question;
        String lower = text.toLowerCase(Locale.ROOT);

        boolean order = containsAny(lower, ORDER_HINTS) || ORDER_ID.matcher(text).find();
        boolean policy = containsAny(lower, POLICY_HINTS) || containsAny(lower, KNOWLEDGE_HINTS);
        boolean inventory = containsAny(lower, INVENTORY_HINTS);
        boolean price = containsAny(lower, PRICE_HINTS);

        List<String> intents = new ArrayList<>();
        List<Plan.Step> steps = new ArrayList<>();
        // 按依赖顺序排：先拿到订单事实（含 SKU），再查政策，再查库存 / 算价，最后汇总。
        if (order) {
            addStep(steps, "查询订单事实", "lookupOrder",
                    "获取订单状态、商品与 SKU、是否定制、收货天数、是否拆封");
            intents.add("订单查询");
        }
        if (policy) {
            addStep(steps, "检索公司政策/规定", "searchKnowledgeBase",
                    "检索退货、退款、制度等原文，作为结论依据");
            intents.add("政策检索");
        }
        if (inventory) {
            addStep(steps, "查询商品库存与单价", "checkInventory",
                    "获取商品单价、是否定制与各仓库可用库存");
            intents.add("库存查询");
        }
        if (price) {
            addStep(steps, "计算应付金额", "quotePrice",
                    "按商品与数量计算金额（含定制费与满减）");
            intents.add("价格计算");
        }

        if (steps.isEmpty()) {
            log.info("规划器未识别出任务类型，退回全量工具：question={}", text);
            return Plan.none(text, "未识别出明确的工具任务，保留全部工具由模型自行决策。");
        }
        if (steps.size() >= 2) {
            addStep(steps, "汇总并给出结论", null,
                    "综合以上工具结果作答，涉及政策时标注知识库引用编号");
        }
        List<String> tools = steps.stream()
                .map(Plan.Step::tool)
                .filter(tool -> tool != null)
                .distinct()
                .toList();
        String rationale = "识别到 " + String.join("、", intents) + "，按依赖顺序拆成 "
                + steps.size() + " 步，只向模型暴露计划内的 " + tools.size() + " 个工具。";
        Plan plan = new Plan(text, true, List.copyOf(steps), tools, rationale);
        log.info("规划器生成 {} 步计划：tools={}，question={}", steps.size(), tools, text);
        return plan;
    }

    /** 把计划渲染成注入系统提示词的一段说明；空计划返回空串。 */
    public String promptHint(Plan plan) {
        if (plan == null || plan.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("本轮任务规划（系统根据问题自动生成，请按顺序执行，每一步基于上一步的结果）：\n");
        for (Plan.Step step : plan.steps()) {
            sb.append(step.order()).append(". ").append(step.action()).append("：");
            if (step.tool() != null) {
                sb.append("调用 ").append(step.tool()).append("，");
            }
            sb.append(step.detail()).append('\n');
        }
        sb.append("若某一步的工具返回失败或「未查询到」，请如实说明，不要编造数据。");
        return sb.toString();
    }

    private static void addStep(List<Plan.Step> steps, String action, String tool, String detail) {
        steps.add(new Plan.Step(steps.size() + 1, action, tool, detail));
    }

    private static boolean containsAny(String lower, List<String> hints) {
        for (String hint : hints) {
            if (lower.contains(hint)) {
                return true;
            }
        }
        return false;
    }
}
