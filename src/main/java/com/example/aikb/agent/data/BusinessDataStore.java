package com.example.aikb.agent.data;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 内部业务系统的轻量替身：启动时把订单、库存 JSON 读进内存 Map。
 *
 * <p>工具（{@code agent.tool}）只依赖这个类，因此从「读 JSON」换成「查数据库 / 调内部接口」
 * 时，工具的入参与返回结构都不用动，这也正是 Function Calling 分层的意义。
 */
@Slf4j
@Component
public class BusinessDataStore {

    private final Map<String, OrderRecord> orders;
    private final Map<String, InventoryRecord> inventory;

    public BusinessDataStore(ObjectMapper objectMapper,
                             @Value("classpath:agent/orders.json") Resource ordersResource,
                             @Value("classpath:agent/inventory.json") Resource inventoryResource) {
        this.orders = load(objectMapper, ordersResource, OrderRecord.class, "订单");
        this.inventory = load(objectMapper, inventoryResource, InventoryRecord.class, "库存");
        log.info("已加载业务模拟数据：订单 {} 条，商品 {} 条", orders.size(), inventory.size());
    }

    /** 按订单号查询，忽略大小写与首尾空格。 */
    public Optional<OrderRecord> findOrder(String orderId) {
        return lookup(orders, orderId);
    }

    /** 按 SKU 查询商品与库存。 */
    public Optional<InventoryRecord> findInventory(String sku) {
        return lookup(inventory, sku);
    }

    public int orderCount() {
        return orders.size();
    }

    public int inventoryCount() {
        return inventory.size();
    }

    private static <T> Optional<T> lookup(Map<String, T> source, String key) {
        if (key == null || key.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(source.get(key.trim().toUpperCase()));
    }

    private static <T> Map<String, T> load(ObjectMapper objectMapper, Resource resource,
                                           Class<T> type, String label) {
        if (!resource.exists()) {
            log.warn("未找到{}模拟数据文件：{}", label, resource);
            return Map.of();
        }
        try (InputStream in = resource.getInputStream()) {
            List<T> records = objectMapper.readValue(in,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, type));
            Map<String, T> indexed = new LinkedHashMap<>();
            for (T record : records) {
                indexed.put(keyOf(record), record);
            }
            return Map.copyOf(indexed);
        } catch (IOException e) {
            throw new IllegalStateException("解析" + label + "模拟数据失败：" + resource, e);
        }
    }

    private static String keyOf(Object record) {
        if (record instanceof OrderRecord order) {
            return order.orderId().toUpperCase();
        }
        if (record instanceof InventoryRecord item) {
            return item.sku().toUpperCase();
        }
        throw new IllegalArgumentException("不支持的记录类型：" + record.getClass());
    }
}
