package com.example.aikb.agent.tool;

import com.example.aikb.agent.data.BusinessDataStore;
import com.example.aikb.agent.data.InventoryRecord;
import com.example.aikb.agent.model.InventoryView;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.Optional;

/** 库存查询工具。 */
@Slf4j
@Component
public class InventoryTools {

    private final BusinessDataStore store;

    public InventoryTools(BusinessDataStore store) {
        this.store = store;
    }

    @Tool(name = "checkInventory",
            description = "根据商品编号查询商品的单价、是否定制商品，以及各仓库的可用库存数量。"
                    + "用户询问有没有货、库存多少、单价多少时调用它。")
    public InventoryView checkInventory(
            @ToolParam(description = "商品编号（SKU），例如 SKU-2002") String sku) {
        Optional<InventoryRecord> item = store.findInventory(sku);
        if (item.isEmpty()) {
            log.info("checkInventory 未命中：{}", sku);
            return InventoryView.notFound(sku);
        }
        InventoryView view = InventoryView.of(item.get());
        log.info("checkInventory 命中：{} 总库存={}", view.sku(), view.totalStock());
        return view;
    }
}
