package com.example.aikb.agent.tool;

import com.example.aikb.agent.data.BusinessDataStore;
import com.example.aikb.agent.data.OrderRecord;
import com.example.aikb.agent.model.OrderView;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.Optional;

/**
 * 订单查询工具。
 *
 * <p>{@link Tool} 注解的作用是「把普通 Java 方法暴露成模型可调用的函数」：
 * 方法名 / {@code name} 是工具名，{@code description} 与 {@code ToolParam#description}
 * 会被 Spring AI 生成成 JSON Schema 发给模型，模型据此决定「要不要调、传什么参数」。
 * 所以描述写得好不好，直接决定工具选得对不对，这是 Agent 里最容易被忽视的一环。
 */
@Slf4j
@Component
public class OrderTools {

    private final BusinessDataStore store;
    private final Clock clock;

    public OrderTools(BusinessDataStore store, Clock clock) {
        this.store = store;
        this.clock = clock;
    }

    @Tool(name = "lookupOrder",
            description = "根据订单号查询订单详情：状态、商品名称、SKU、类别、是否定制商品、"
                    + "下单时间、收货时间、距收货已过天数、金额、是否已拆封。"
                    + "用户询问某个订单的状态、能不能退、什么时候买的时，先调用它。")
    public OrderView lookupOrder(
            @ToolParam(description = "订单号，例如 A1001") String orderId) {
        Optional<OrderRecord> order = store.findOrder(orderId);
        if (order.isEmpty()) {
            log.info("lookupOrder 未命中：{}", orderId);
            return OrderView.notFound(orderId);
        }
        OrderView view = OrderView.of(order.get(), clock);
        log.info("lookupOrder 命中：{} 状态={} 已收货 {} 天",
                view.orderId(), view.status(), view.daysSinceDelivery());
        return view;
    }
}
