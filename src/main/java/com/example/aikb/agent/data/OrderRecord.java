package com.example.aikb.agent.data;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.LocalDate;

/**
 * 订单记录：工具返回给模型的原始业务数据。
 *
 * <p>这里用内存 JSON 模拟内部订单系统，真实项目里把 {@link BusinessDataStore} 换成
 * DAO 或 HTTP 客户端即可，工具方法本身不需要改。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OrderRecord(
        String orderId,
        String customer,
        String sku,
        String productName,
        String category,
        boolean customMade,
        String status,
        double amount,
        int quantity,
        LocalDate orderDate,
        LocalDate deliveredDate,
        boolean opened) {
}
