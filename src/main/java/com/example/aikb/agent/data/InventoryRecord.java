package com.example.aikb.agent.data;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.Map;

/** 商品库存记录：单价、是否定制、各仓库可用库存。 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record InventoryRecord(
        String sku,
        String productName,
        double unitPrice,
        boolean customMade,
        Map<String, Integer> stockByWarehouse) {

    public int totalStock() {
        return stockByWarehouse == null
                ? 0
                : stockByWarehouse.values().stream().mapToInt(Integer::intValue).sum();
    }
}
