package com.example.aikb.agent.tool;

import com.example.aikb.agent.data.BusinessDataStore;
import com.example.aikb.agent.data.InventoryRecord;
import com.example.aikb.agent.model.PriceQuote;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.Optional;

/** 价格计算工具：多参数工具，用来演示模型如何填参数。 */
@Slf4j
@Component
public class PricingTools {

    private final BusinessDataStore store;

    public PricingTools(BusinessDataStore store) {
        this.store = store;
    }

    @Tool(name = "quotePrice",
            description = "根据商品编号与购买数量计算应付金额，返回单价、小计、定制费、优惠与总额。"
                    + "用户询问买 N 件要多少钱、算一下价格时调用它。")
    public PriceQuote quotePrice(
            @ToolParam(description = "商品编号（SKU），例如 SKU-3003") String sku,
            @ToolParam(description = "购买数量，正整数，例如 2") int quantity) {
        if (quantity <= 0) {
            return PriceQuote.notFound(sku);
        }
        Optional<InventoryRecord> item = store.findInventory(sku);
        if (item.isEmpty()) {
            log.info("quotePrice 未命中：{}", sku);
            return PriceQuote.notFound(sku);
        }
        PriceQuote quote = PriceQuote.of(item.get(), quantity);
        log.info("quotePrice 命中：{} × {} = {}", quote.sku(), quantity, quote.total());
        return quote;
    }
}
