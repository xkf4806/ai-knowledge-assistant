package com.example.aikb.agent.tool;

import com.example.aikb.agent.data.BusinessDataStore;
import com.example.aikb.agent.model.InventoryView;
import com.example.aikb.agent.model.PriceQuote;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import static org.assertj.core.api.Assertions.assertThat;

class PricingToolsTest {

    private final BusinessDataStore store = new BusinessDataStore(
            new ObjectMapper().findAndRegisterModules(),
            new ClassPathResource("agent/orders.json"),
            new ClassPathResource("agent/inventory.json"));

    private final PricingTools pricingTools = new PricingTools(store);
    private final InventoryTools inventoryTools = new InventoryTools(store);

    @Test
    void appliesFullReductionOverFiveHundred() {
        // 299 × 2 = 598，满 500 减 50 → 548
        PriceQuote quote = pricingTools.quotePrice("SKU-3003", 2);

        assertThat(quote.found()).isTrue();
        assertThat(quote.subtotal()).isEqualTo(598.0);
        assertThat(quote.customFee()).isEqualTo(0.0);
        assertThat(quote.discount()).isEqualTo(50.0);
        assertThat(quote.total()).isEqualTo(548.0);
    }

    @Test
    void addsCustomFeeForMadeToOrderGoods() {
        // 199 × 3 = 597，定制费 59.7 → 656.7，满 500 减 50 → 606.7
        PriceQuote quote = pricingTools.quotePrice("SKU-1001", 3);

        assertThat(quote.customFee()).isEqualTo(59.7);
        assertThat(quote.discount()).isEqualTo(50.0);
        assertThat(quote.total()).isEqualTo(606.7);
        assertThat(quote.note()).contains("定制费");
    }

    @Test
    void rejectsUnknownSkuAndNonPositiveQuantity() {
        assertThat(pricingTools.quotePrice("SKU-0000", 1).found()).isFalse();
        assertThat(pricingTools.quotePrice("SKU-3003", 0).found()).isFalse();
    }

    @Test
    void inventoryReportsStockAndUnitPrice() {
        InventoryView view = inventoryTools.checkInventory("SKU-2002");

        assertThat(view.found()).isTrue();
        assertThat(view.unitPrice()).isEqualTo(499.0);
        assertThat(view.totalStock()).isEqualTo(65);
        assertThat(inventoryTools.checkInventory("SKU-0000").found()).isFalse();
    }
}
