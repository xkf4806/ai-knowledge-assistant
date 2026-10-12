package com.example.aikb.agent.data;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import static org.assertj.core.api.Assertions.assertThat;

class BusinessDataStoreTest {

    private final BusinessDataStore store = new BusinessDataStore(
            new ObjectMapper().findAndRegisterModules(),
            new ClassPathResource("agent/orders.json"),
            new ClassPathResource("agent/inventory.json"));

    @Test
    void loadsOrdersAndInventoryFromClasspath() {
        assertThat(store.orderCount()).isEqualTo(4);
        assertThat(store.inventoryCount()).isEqualTo(3);
    }

    @Test
    void findsOrderIgnoringCaseAndWhitespace() {
        OrderRecord order = store.findOrder(" a1001 ").orElseThrow();
        assertThat(order.productName()).isEqualTo("定制刻字保温杯");
        assertThat(order.customMade()).isTrue();
        assertThat(order.deliveredDate()).isNotNull();
    }

    @Test
    void returnsEmptyForUnknownKeys() {
        assertThat(store.findOrder("A9999")).isEmpty();
        assertThat(store.findOrder(null)).isEmpty();
        assertThat(store.findInventory("SKU-0000")).isEmpty();
    }

    @Test
    void computesTotalStockAcrossWarehouses() {
        InventoryRecord item = store.findInventory("SKU-3003").orElseThrow();
        assertThat(item.stockByWarehouse()).containsEntry("北京仓", 0);
        assertThat(item.totalStock()).isEqualTo(8);
    }
}
