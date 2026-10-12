package com.example.aikb.agent.model;

import com.example.aikb.agent.data.OrderRecord;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * lookupOrder 工具的返回结构。
 *
 * <p>刻意只返回「事实」，不返回「能不能退」这类结论：判断要交给模型结合知识库里的退货政策完成，
 * 这样工具保持纯粹、可复用，也能在演示里看到「查订单 + 查政策 → 得结论」的完整推理链。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record OrderView(
        String orderId,
        boolean found,
        String customer,
        String productName,
        String sku,
        String category,
        Boolean customMade,
        String status,
        Double amount,
        Integer quantity,
        LocalDate orderDate,
        LocalDate deliveredDate,
        Long daysSinceDelivery,
        Boolean opened,
        String note) {

    public static OrderView of(OrderRecord order, Clock clock) {
        Long days = order.deliveredDate() == null
                ? null
                : ChronoUnit.DAYS.between(order.deliveredDate(), LocalDate.now(clock));
        return new OrderView(
                order.orderId(),
                true,
                order.customer(),
                order.productName(),
                order.sku(),
                order.category(),
                order.customMade(),
                order.status(),
                order.amount(),
                order.quantity(),
                order.orderDate(),
                order.deliveredDate(),
                days,
                order.opened(),
                null);
    }

    public static OrderView notFound(String orderId) {
        return new OrderView(orderId, false, null, null, null, null, null, null,
                null, null, null, null, null, null,
                "未查询到订单 " + orderId + "，请确认订单号是否正确。");
    }
}
