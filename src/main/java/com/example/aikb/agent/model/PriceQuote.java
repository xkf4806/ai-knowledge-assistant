package com.example.aikb.agent.model;

import com.example.aikb.agent.data.InventoryRecord;
import com.fasterxml.jackson.annotation.JsonInclude;

/** quotePrice 工具的返回结构：把价格构成拆开，便于模型向用户解释。 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PriceQuote(
        String sku,
        boolean found,
        String productName,
        Double unitPrice,
        Integer quantity,
        Double subtotal,
        Double customFee,
        Double discount,
        Double total,
        String note) {

    public static PriceQuote of(InventoryRecord item, int quantity) {
        double unitPrice = item.unitPrice();
        double subtotal = round(unitPrice * quantity);
        double customFee = item.customMade() ? round(subtotal * 0.10) : 0.0;
        double discount = round(subtotal + customFee) >= 500.0 ? 50.0 : 0.0;
        double total = round(subtotal + customFee - discount);
        String rule = "计价规则：单价 × 数量"
                + (item.customMade() ? "，定制商品加收 10% 定制费" : "")
                + "，订单金额满 500 元减 50 元。";
        return new PriceQuote(item.sku(), true, item.productName(), unitPrice, quantity,
                subtotal, customFee, discount, total, rule);
    }

    public static PriceQuote notFound(String sku) {
        return new PriceQuote(sku, false, null, null, null, null, null, null, null,
                "未查询到商品 " + sku + "，无法报价。");
    }

    private static double round(double value) {
        return Math.round(value * 100d) / 100d;
    }
}
