package com.example.aikb.agent.tool;

import com.example.aikb.agent.data.BusinessDataStore;
import com.example.aikb.agent.model.OrderView;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

class OrderToolsTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    /** 固定「今天」为 2026-10-11，让「距收货几天」的断言不受真实日期影响。 */
    private static final Clock CLOCK = Clock.fixed(
            LocalDate.of(2026, 10, 11).atStartOfDay(ZONE).toInstant(), ZONE);

    private final OrderTools tools = new OrderTools(
            new BusinessDataStore(
                    new ObjectMapper().findAndRegisterModules(),
                    new ClassPathResource("agent/orders.json"),
                    new ClassPathResource("agent/inventory.json")),
            CLOCK);

    @Test
    void returnsOrderFactsWithDaysSinceDelivery() {
        OrderView view = tools.lookupOrder("A1001");

        assertThat(view.found()).isTrue();
        assertThat(view.customMade()).isTrue();
        assertThat(view.category()).isEqualTo("定制商品");
        assertThat(view.deliveredDate()).isEqualTo(LocalDate.of(2026, 10, 4));
        assertThat(view.daysSinceDelivery()).isEqualTo(7L);
        assertThat(view.opened()).isFalse();
        // 工具只给事实，不给「能不能退」的结论，结论要由模型结合退货政策得出。
        assertThat(view.note()).isNull();
    }

    @Test
    void reportsInTransitOrderWithoutDeliveryDate() {
        OrderView view = tools.lookupOrder("A1004");

        assertThat(view.found()).isTrue();
        assertThat(view.status()).isEqualTo("运输中");
        assertThat(view.deliveredDate()).isNull();
        assertThat(view.daysSinceDelivery()).isNull();
    }

    @Test
    void returnsNotFoundPayloadInsteadOfThrowing() {
        OrderView view = tools.lookupOrder("A0000");

        assertThat(view.found()).isFalse();
        assertThat(view.note()).contains("未查询到订单");
        assertThat(view.productName()).isNull();
    }

    @Test
    void fixedClockKeepsAssertionsStable() {
        assertThat(LocalDate.now(CLOCK)).isEqualTo(LocalDate.of(2026, 10, 11));
        assertThat(Instant.now(CLOCK)).isEqualTo(
                LocalDate.of(2026, 10, 11).atStartOfDay(ZONE).toInstant());
    }
}
