package com.example.aikb.agent.model;

import com.example.aikb.agent.data.InventoryRecord;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;

/** checkInventory 工具的返回结构：单价与各仓库库存。 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record InventoryView(
        String sku,
        boolean found,
        String productName,
        Double unitPrice,
        Boolean customMade,
        Integer totalStock,
        Map<String, Integer> stockByWarehouse,
        String note) {

    public static InventoryView of(InventoryRecord item) {
        return new InventoryView(item.sku(), true, item.productName(), item.unitPrice(),
                item.customMade(), item.totalStock(), item.stockByWarehouse(), null);
    }

    public static InventoryView notFound(String sku) {
        return new InventoryView(sku, false, null, null, null, null, null,
                "未查询到商品 " + sku + "，请确认 SKU 是否正确。");
    }
}
