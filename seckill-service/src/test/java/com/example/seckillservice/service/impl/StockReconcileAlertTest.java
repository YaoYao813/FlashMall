package com.example.seckillservice.service.impl;

import com.example.seckillservice.vo.ReconcileVO;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 告警行必须是单行 JSON，且字段与 {@code GET /reconcile} 同名同义。
 * 日志系统按 fingerprint 聚合，字段名改了就等于悄悄失效，所以这里锁住格式。
 */
class StockReconcileAlertTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    @DisplayName("第一次 DRIFT 是 SUSPECT，带成因指纹与两侧明细")
    void testSuspectLine() throws Exception {
        JsonNode line = JSON.readTree(
                StockReconcileServiceImpl.alertLine(driftVo("DUPLICATE_ROLLBACK"), 1));

        assertEquals("SUSPECT", line.get("level").asText());
        assertEquals(100L, line.get("eventId").asLong());
        assertEquals("DRIFT", line.get("verdict").asText());
        assertEquals("DUPLICATE_ROLLBACK", line.get("fingerprint").asText());
        assertEquals(1, line.get("streak").asInt());
        assertEquals(3, line.get("backlog").asInt());
        assertEquals(-1, line.get("quota").get("gap").asInt());
        assertEquals(-1, line.get("warehouse").get("gap").asInt());
    }

    @Test
    @DisplayName("连续第二次 DRIFT 升级为 ALERT")
    void testStreakEscalates() throws Exception {
        JsonNode line = JSON.readTree(
                StockReconcileServiceImpl.alertLine(driftVo("ROLLBACK_MISSED"), 2));

        assertEquals("ALERT", line.get("level").asText());
        assertEquals(2, line.get("streak").asInt());
    }

    @Test
    @DisplayName("未知的一侧按接口约定省略字段，不写 0")
    void testUnknownSideIsOmitted() throws Exception {
        ReconcileVO vo = ReconcileVO.builder()
                .eventId(100L)
                .productId(7L)
                .status("running")
                .verdict("DRIFT")
                .fingerprint("REDIS_OVER_ROLLBACK")
                .backlog(0)
                .quota(ReconcileVO.Quota.builder()
                        .total(10).redisStock(11).orderCount(0).cancelledCount(0)
                        .expectedRedis(10).gap(-1).verdict("DRIFT")
                        .build())
                .warehouse(ReconcileVO.Warehouse.builder()
                        .dbStock(50).verdict("UNKNOWN")
                        .build())
                .build();

        JsonNode line = JSON.readTree(StockReconcileServiceImpl.alertLine(vo, 3));

        // 顶层 null 字段被省略；warehouse 内部的 null 显式输出，绝不能是 0
        assertTrue(line.get("warehouse").get("baseline").isNull());
        assertTrue(line.get("warehouse").get("gap").isNull());
        assertTrue(line.get("quota").get("gap").isInt());
    }

    private ReconcileVO driftVo(String fingerprint) {
        return ReconcileVO.builder()
                .eventId(100L)
                .productId(7L)
                .status("running")
                .verdict("DRIFT")
                .fingerprint(fingerprint)
                .backlog(3)
                .quota(ReconcileVO.Quota.builder()
                        .total(10).redisStock(10).orderCount(2).cancelledCount(1)
                        .expectedRedis(9).gap(-1).verdict("DRIFT")
                        .build())
                .warehouse(ReconcileVO.Warehouse.builder()
                        .baseline(50).dbStock(50).expectedDb(49).gap(-1).verdict("DRIFT")
                        .build())
                .build();
    }
}
