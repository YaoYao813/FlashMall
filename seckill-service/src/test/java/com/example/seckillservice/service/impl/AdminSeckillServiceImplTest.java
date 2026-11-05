package com.example.seckillservice.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.flashmall.common.entity.AdminIdempotency;
import com.example.flashmall.common.entity.Product;
import com.example.flashmall.common.entity.ProductStock;
import com.example.flashmall.common.entity.SeckillEvent;
import com.example.flashmall.common.mapper.AdminIdempotencyMapper;
import com.example.flashmall.common.mapper.ProductMapper;
import com.example.flashmall.common.mapper.ProductStockMapper;
import com.example.flashmall.common.mapper.SeckillEventMapper;
import com.example.flashmall.common.util.PageResult;
import com.example.seckillservice.cache.SeckillEventCache;
import com.example.seckillservice.dto.CreateEventRequest;
import com.example.seckillservice.dto.UpdateEventRequest;
import com.example.seckillservice.vo.EventVO;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@SuppressWarnings({"unchecked", "rawtypes"})
class AdminSeckillServiceImplTest {

    private static final String KEY = "thread-1:call-1";

    private SeckillEventMapper seckillEventMapper;
    private ProductMapper productMapper;
    private ProductStockMapper productStockMapper;
    private AdminIdempotencyMapper idempotencyMapper;
    private ObjectMapper objectMapper;
    private SeckillEventCache seckillEventCache;
    private AdminSeckillServiceImpl service;

    /**
     * 单测不启动 Spring/MyBatis，LambdaQueryWrapper 渲染 SQL 前需要实体元信息。
     */
    @BeforeAll
    static void initMybatisPlusLambdaCache() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        assistant.setCurrentNamespace("com.example.flashmall.common.mapper.SeckillEventMapper");
        TableInfoHelper.initTableInfo(assistant, SeckillEvent.class);
    }

    @BeforeEach
    void setUp() {
        seckillEventMapper = mock(SeckillEventMapper.class);
        productMapper = mock(ProductMapper.class);
        productStockMapper = mock(ProductStockMapper.class);
        idempotencyMapper = mock(AdminIdempotencyMapper.class);
        seckillEventCache = mock(SeckillEventCache.class);
        // 默认仓库可售 30；个别用例可覆盖
        lenient().when(productStockMapper.selectByProductId(anyLong()))
                .thenReturn(warehouse(30));
        objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        // 测试里不需要真实事务：直接执行回调
        TransactionOperations noTx = new TransactionOperations() {
            @Override
            public <T> T execute(TransactionCallback<T> action) {
                return action.doInTransaction(new SimpleTransactionStatus());
            }
        };
        service = new AdminSeckillServiceImpl(
                seckillEventMapper, productMapper, productStockMapper, idempotencyMapper, noTx,
                objectMapper, seckillEventCache);
    }

    // ==================== 接口 1：创建 ====================

    @Test
    void createsEventAndStoresIdempotencyRecord() {
        LocalDateTime start = LocalDateTime.now().plusDays(1);
        CreateEventRequest request = request(7L, "299.90", 10, start, start.plusHours(2));
        when(productMapper.selectById(7L)).thenReturn(product("899.00"));
        when(seckillEventMapper.selectUnfinishedByProductId(eq(7L), any(), isNull())).thenReturn(null);
        when(seckillEventMapper.insert(any(SeckillEvent.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, SeckillEvent.class).setId(9L);
            return 1;
        });
        when(idempotencyMapper.selectById(KEY)).thenReturn(null);
        when(idempotencyMapper.insert(any(AdminIdempotency.class))).thenReturn(1);

        EventVO vo = service.createEvent(request, KEY, 1L);

        assertEquals(9L, vo.getId());
        assertEquals(7L, vo.getProductId());
        assertFalse(vo.getReplayed());
        assertEquals("pending", vo.getStatus());

        ArgumentCaptor<SeckillEvent> eventCaptor = ArgumentCaptor.forClass(SeckillEvent.class);
        verify(seckillEventMapper).insert(eventCaptor.capture());
        assertEquals(10, eventCaptor.getValue().getStock());
        assertEquals(new BigDecimal("299.90"), eventCaptor.getValue().getSeckillPrice());

        ArgumentCaptor<AdminIdempotency> idemCaptor = ArgumentCaptor.forClass(AdminIdempotency.class);
        verify(idempotencyMapper).insert(idemCaptor.capture());
        assertEquals(KEY, idemCaptor.getValue().getIdempotencyKey());
        assertTrue(idemCaptor.getValue().getResponseJson().contains("\"productId\":7"));
    }

    @Test
    void replaysStoredResponseWithoutTouchingEventTable() throws Exception {
        EventVO stored = storedVO();
        when(idempotencyMapper.selectById(KEY)).thenReturn(idempotencyRow(stored));

        EventVO vo = service.createEvent(validRequest(), KEY, 1L);

        assertTrue(vo.getReplayed());
        assertEquals(9L, vo.getId());
        assertEquals("pending", vo.getStatus());
        verifyNoInteractions(seckillEventMapper, productMapper);
        verify(idempotencyMapper, never()).insert(any());
    }

    @Test
    void duplicateKeyFallsBackToReplay() throws Exception {
        EventVO stored = storedVO();
        when(idempotencyMapper.selectById(KEY)).thenReturn(null, idempotencyRow(stored));
        when(productMapper.selectById(7L)).thenReturn(product("899.00"));
        when(seckillEventMapper.selectUnfinishedByProductId(eq(7L), any(), isNull())).thenReturn(null);
        when(seckillEventMapper.insert(any(SeckillEvent.class))).thenReturn(1);
        when(idempotencyMapper.insert(any(AdminIdempotency.class)))
                .thenThrow(new DuplicateKeyException("duplicate idempotency key"));

        EventVO vo = service.createEvent(validRequest(), KEY, 1L);

        assertTrue(vo.getReplayed());
        assertEquals(9L, vo.getId());
    }

    @Test
    void rejectsBlankIdempotencyKey() {
        RuntimeException e = assertThrows(RuntimeException.class,
                () -> service.createEvent(validRequest(), "  ", 1L));
        assertEquals("Idempotency-Key 不能为空", e.getMessage());
    }

    @Test
    void rejectsMissingProduct() {
        when(productMapper.selectById(7L)).thenReturn(null);
        assertMessage("商品不存在", validRequest());
    }

    @Test
    void rejectsPriceNotLowerThanOriginal() {
        when(productMapper.selectById(7L)).thenReturn(product("299.90"));
        assertMessage("秒杀价必须低于商品原价", validRequest());
    }

    @Test
    void rejectsZeroStock() {
        when(productMapper.selectById(7L)).thenReturn(product("899.00"));
        assertMessage("秒杀库存必须在 1 到商品库存之间",
                request(7L, "299.90", 0, nowPlusDay(), nowPlusDay().plusHours(2)));
    }

    @Test
    void rejectsStockAboveProductStock() {
        when(productMapper.selectById(7L)).thenReturn(product("899.00"));
        assertMessage("秒杀库存必须在 1 到商品库存之间",
                request(7L, "299.90", 31, nowPlusDay(), nowPlusDay().plusHours(2)));
    }

    @Test
    void rejectsStartNotBeforeEnd() {
        when(productMapper.selectById(7L)).thenReturn(product("899.00"));
        LocalDateTime start = nowPlusDay();
        assertMessage("开始时间必须早于结束时间",
                request(7L, "299.90", 10, start, start));
    }

    @Test
    void rejectsEndNotInFuture() {
        when(productMapper.selectById(7L)).thenReturn(product("899.00"));
        LocalDateTime now = LocalDateTime.now();
        assertMessage("结束时间必须晚于当前时间",
                request(7L, "299.90", 10, now.minusHours(2), now.minusMinutes(1)));
    }

    @Test
    void rejectsWhenProductHasUnfinishedEvent() {
        when(productMapper.selectById(7L)).thenReturn(product("899.00"));
        SeckillEvent conflict = new SeckillEvent();
        conflict.setId(9L);
        when(seckillEventMapper.selectUnfinishedByProductId(eq(7L), any(), isNull()))
                .thenReturn(conflict);
        assertMessage("该商品已存在未结束的活动（id=9）", validRequest());
    }

    @Test
    void derivesRunningStatusWhenStartInPast() {
        LocalDateTime now = LocalDateTime.now();
        CreateEventRequest request = request(7L, "299.90", 10,
                now.minusHours(1), now.plusHours(1));
        when(productMapper.selectById(7L)).thenReturn(product("899.00"));
        when(seckillEventMapper.selectUnfinishedByProductId(eq(7L), any(), isNull())).thenReturn(null);
        when(seckillEventMapper.insert(any(SeckillEvent.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, SeckillEvent.class).setId(10L);
            return 1;
        });
        when(idempotencyMapper.selectById(KEY)).thenReturn(null);
        when(idempotencyMapper.insert(any(AdminIdempotency.class))).thenReturn(1);

        EventVO vo = service.createEvent(request, KEY, 1L);

        assertEquals("running", vo.getStatus());
    }

    // ==================== 接口 2：列表 ====================

    @Test
    void listsEventsWithProductNamesAndDerivedStatus() {
        LocalDateTime now = LocalDateTime.now();
        SeckillEvent running = event(9L, 7L, "299.90", 10,
                now.minusHours(1), now.plusHours(1));
        SeckillEvent pending = event(10L, 8L, "19.90", 5,
                now.plusDays(1), now.plusDays(1).plusHours(1));
        doReturn(pageOf(running, pending)).when(seckillEventMapper).selectPage(any(), any());
        when(productMapper.selectBatchIds(any())).thenReturn(Arrays.asList(
                product(7L, "4K显示器", "899.00"),
                product(8L, "无线鼠标", "55.00")));

        PageResult<EventVO> result = service.listEvents("all", null, 1, 10);

        assertEquals(2, result.getTotal());
        assertEquals(2, result.getRecords().size());
        assertEquals("running", result.getRecords().get(0).getStatus());
        assertEquals("4K显示器", result.getRecords().get(0).getProductName());
        assertEquals("pending", result.getRecords().get(1).getStatus());
        assertEquals("无线鼠标", result.getRecords().get(1).getProductName());
    }

    @Test
    void translatesPendingFilterToStartTimeCondition() {
        doReturn(pageOf()).when(seckillEventMapper).selectPage(any(), any());

        service.listEvents("pending", null, 1, 10);

        String sql = capturedWrapperSql();
        assertTrue(sql.contains("start_time >"), sql);
        assertFalse(sql.contains("end_time"), sql);
    }

    @Test
    void translatesRunningFilterToTimeWindow() {
        doReturn(pageOf()).when(seckillEventMapper).selectPage(any(), any());

        service.listEvents("running", null, 1, 10);

        String sql = capturedWrapperSql();
        assertTrue(sql.contains("start_time <="), sql);
        assertTrue(sql.contains("end_time >="), sql);
    }

    @Test
    void translatesEndedFilterToEndTimeCondition() {
        doReturn(pageOf()).when(seckillEventMapper).selectPage(any(), any());

        service.listEvents("ended", null, 1, 10);

        String sql = capturedWrapperSql();
        assertTrue(sql.contains("end_time <"), sql);
    }

    @Test
    void allStatusAddsNoWhereCondition() {
        doReturn(pageOf()).when(seckillEventMapper).selectPage(any(), any());

        service.listEvents("all", null, 1, 10);

        assertFalse(capturedWrapperSql().contains("WHERE"));
    }

    @Test
    void clampsPageSizeToTwenty() {
        doReturn(pageOf()).when(seckillEventMapper).selectPage(any(), any());

        service.listEvents("all", null, 1, 50);

        ArgumentCaptor<Page> pageCaptor = ArgumentCaptor.forClass(Page.class);
        verify(seckillEventMapper).selectPage(pageCaptor.capture(), any());
        assertEquals(20, pageCaptor.getValue().getSize());
    }

    @Test
    void rejectsUnknownStatus() {
        RuntimeException e = assertThrows(RuntimeException.class,
                () -> service.listEvents("bogus", null, 1, 10));
        assertEquals("status 只能是 all、pending、running、ended", e.getMessage());
    }

    // ==================== 接口 3：修改 / 下线 ====================

    @Test
    void updatesPendingEventFields() {
        LocalDateTime now = LocalDateTime.now();
        SeckillEvent event = event(9L, 7L, "299.90", 10,
                now.plusDays(1), now.plusDays(1).plusHours(2));
        when(seckillEventMapper.selectById(9L)).thenReturn(event);
        when(productMapper.selectById(7L)).thenReturn(product("899.00"));
        when(seckillEventMapper.selectUnfinishedByProductId(eq(7L), any(), eq(9L))).thenReturn(null);
        when(seckillEventMapper.updateById(any(SeckillEvent.class))).thenReturn(1);

        UpdateEventRequest request = new UpdateEventRequest();
        request.setSeckillPrice(new BigDecimal("199.90"));
        request.setStock(20);

        EventVO vo = service.updateEvent(9L, request);

        assertEquals(new BigDecimal("199.90"), vo.getSeckillPrice());
        assertEquals(20, vo.getStock());
        assertEquals("pending", vo.getStatus());
        verify(seckillEventMapper).updateById(event);
    }

    @Test
    void rejectsPriceChangeOnRunningEvent() {
        SeckillEvent event = runningEvent();
        when(seckillEventMapper.selectById(9L)).thenReturn(event);

        UpdateEventRequest request = new UpdateEventRequest();
        request.setSeckillPrice(new BigDecimal("199.90"));

        RuntimeException e = assertThrows(RuntimeException.class,
                () -> service.updateEvent(9L, request));
        assertEquals("进行中的活动只能修改结束时间", e.getMessage());
        verify(seckillEventMapper, never()).updateById(any());
    }

    @Test
    void allowsDownlineByMovingEndTimeToNow() {
        SeckillEvent event = runningEvent();
        when(seckillEventMapper.selectById(9L)).thenReturn(event);
        when(seckillEventMapper.updateById(any(SeckillEvent.class))).thenReturn(1);

        UpdateEventRequest request = new UpdateEventRequest();
        request.setEndTime(LocalDateTime.now().minusSeconds(1));

        EventVO vo = service.updateEvent(9L, request);

        assertEquals("ended", vo.getStatus());
        assertEquals(request.getEndTime(), vo.getEndTime());
        verify(seckillEventMapper).updateById(event);
    }

    @Test
    void allowsDownlineOfPendingEvent() {
        LocalDateTime now = LocalDateTime.now();
        SeckillEvent event = event(9L, 7L, "299.90", 10,
                now.plusDays(1), now.plusDays(1).plusHours(2));
        when(seckillEventMapper.selectById(9L)).thenReturn(event);
        when(seckillEventMapper.updateById(any(SeckillEvent.class))).thenReturn(1);

        UpdateEventRequest request = new UpdateEventRequest();
        request.setEndTime(now.minusSeconds(1));

        EventVO vo = service.updateEvent(9L, request);

        assertEquals("ended", vo.getStatus());
        assertEquals(request.getEndTime(), vo.getEndTime());
        // 未开始活动下线后起止时间不能倒挂
        assertEquals(request.getEndTime(), vo.getStartTime());
        verify(seckillEventMapper).updateById(event);
    }

    @Test
    void refreshesStockAndWindowWhenPendingEventIsAlreadyWarm() {
        LocalDateTime now = LocalDateTime.now();
        SeckillEvent event = event(9L, 7L, "299.90", 10,
                now.plusDays(1), now.plusDays(1).plusHours(2));
        when(seckillEventMapper.selectById(9L)).thenReturn(event);
        when(productMapper.selectById(7L)).thenReturn(product("899.00"));
        when(seckillEventMapper.selectUnfinishedByProductId(eq(7L), any(), eq(9L))).thenReturn(null);
        when(seckillEventMapper.updateById(any(SeckillEvent.class))).thenReturn(1);
        when(seckillEventCache.windowExists(7L)).thenReturn(true);

        UpdateEventRequest request = new UpdateEventRequest();
        request.setStock(20);

        service.updateEvent(9L, request);

        verify(seckillEventCache).preheat(event);
        verify(seckillEventCache, never()).writeWindow(any());
    }

    @Test
    void downlineOnlyRefreshesWindowWhenAlreadyWarm() {
        SeckillEvent event = runningEvent();
        when(seckillEventMapper.selectById(9L)).thenReturn(event);
        when(seckillEventMapper.updateById(any(SeckillEvent.class))).thenReturn(1);
        when(seckillEventCache.windowExists(7L)).thenReturn(true);

        UpdateEventRequest request = new UpdateEventRequest();
        request.setEndTime(LocalDateTime.now().minusSeconds(1));

        service.updateEvent(9L, request);

        verify(seckillEventCache).writeWindow(event);
        verify(seckillEventCache, never()).preheat(any());
    }

    @Test
    void deletesWindowWhenRefreshFails() {
        SeckillEvent event = runningEvent();
        when(seckillEventMapper.selectById(9L)).thenReturn(event);
        when(seckillEventMapper.updateById(any(SeckillEvent.class))).thenReturn(1);
        when(seckillEventCache.windowExists(7L)).thenReturn(true);
        doThrow(new RuntimeException("redis down")).when(seckillEventCache).writeWindow(any());

        UpdateEventRequest request = new UpdateEventRequest();
        request.setEndTime(LocalDateTime.now().minusSeconds(1));

        service.updateEvent(9L, request);

        verify(seckillEventCache).deleteWindow(7L);
    }

    @Test
    void doesNotTouchCacheWhenEventWasNeverWarm() {
        SeckillEvent event = runningEvent();
        when(seckillEventMapper.selectById(9L)).thenReturn(event);
        when(seckillEventMapper.updateById(any(SeckillEvent.class))).thenReturn(1);
        when(seckillEventCache.windowExists(7L)).thenReturn(false);

        UpdateEventRequest request = new UpdateEventRequest();
        request.setEndTime(LocalDateTime.now().minusSeconds(1));

        service.updateEvent(9L, request);

        verify(seckillEventCache).windowExists(7L);
        verifyNoMoreInteractions(seckillEventCache);
    }

    @Test
    void rejectsUpdateOnEndedEvent() {
        LocalDateTime now = LocalDateTime.now();
        SeckillEvent event = event(9L, 7L, "299.90", 10,
                now.minusHours(2), now.minusHours(1));
        when(seckillEventMapper.selectById(9L)).thenReturn(event);

        UpdateEventRequest request = new UpdateEventRequest();
        request.setEndTime(now.plusHours(1));

        assertUpdateMessage("活动已结束，不可修改", request);
    }

    @Test
    void rejectsEmptyUpdateRequest() {
        assertUpdateMessage("至少需要修改一个字段", new UpdateEventRequest());
    }

    @Test
    void rejectsStockAboveProductStockOnUpdate() {
        LocalDateTime now = LocalDateTime.now();
        SeckillEvent event = event(9L, 7L, "299.90", 10,
                now.plusDays(1), now.plusDays(1).plusHours(2));
        when(seckillEventMapper.selectById(9L)).thenReturn(event);
        when(productMapper.selectById(7L)).thenReturn(product("899.00"));

        UpdateEventRequest request = new UpdateEventRequest();
        request.setStock(31);

        assertUpdateMessage("秒杀库存必须在 1 到商品库存之间", request);
    }

    @Test
    void rejectsUpdateWhenAnotherUnfinishedEventExists() {
        LocalDateTime now = LocalDateTime.now();
        SeckillEvent event = event(9L, 7L, "299.90", 10,
                now.plusDays(1), now.plusDays(1).plusHours(2));
        when(seckillEventMapper.selectById(9L)).thenReturn(event);
        when(productMapper.selectById(7L)).thenReturn(product("899.00"));
        SeckillEvent conflict = new SeckillEvent();
        conflict.setId(3L);
        when(seckillEventMapper.selectUnfinishedByProductId(eq(7L), any(), eq(9L)))
                .thenReturn(conflict);

        UpdateEventRequest request = new UpdateEventRequest();
        request.setSeckillPrice(new BigDecimal("199.90"));

        assertUpdateMessage("该商品已存在未结束的活动（id=3）", request);
    }

    // ==================== 工具方法 ====================

    private void assertMessage(String expected, CreateEventRequest request) {
        RuntimeException e = assertThrows(RuntimeException.class,
                () -> service.createEvent(request, KEY, 1L));
        assertEquals(expected, e.getMessage());
    }

    private void assertUpdateMessage(String expected, UpdateEventRequest request) {
        RuntimeException e = assertThrows(RuntimeException.class,
                () -> service.updateEvent(9L, request));
        assertEquals(expected, e.getMessage());
    }

    private String capturedWrapperSql() {
        ArgumentCaptor<Wrapper> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(seckillEventMapper).selectPage(any(), captor.capture());
        return captor.getValue().getSqlSegment();
    }

    private Page<SeckillEvent> pageOf(SeckillEvent... events) {
        Page<SeckillEvent> page = new Page<>(1, 10);
        page.setRecords(Arrays.asList(events));
        page.setTotal(events.length);
        return page;
    }

    private SeckillEvent runningEvent() {
        LocalDateTime now = LocalDateTime.now();
        return event(9L, 7L, "299.90", 10, now.minusHours(1), now.plusHours(1));
    }

    private SeckillEvent event(Long id, Long productId, String price, int stock,
                               LocalDateTime start, LocalDateTime end) {
        SeckillEvent event = new SeckillEvent();
        event.setId(id);
        event.setProductId(productId);
        event.setSeckillPrice(new BigDecimal(price));
        event.setStock(stock);
        event.setStartTime(start);
        event.setEndTime(end);
        return event;
    }

    private CreateEventRequest validRequest() {
        LocalDateTime start = nowPlusDay();
        return request(7L, "299.90", 10, start, start.plusHours(2));
    }

    private CreateEventRequest request(Long productId, String price, Integer stock,
                                       LocalDateTime start, LocalDateTime end) {
        CreateEventRequest request = new CreateEventRequest();
        request.setProductId(productId);
        request.setSeckillPrice(new BigDecimal(price));
        request.setStock(stock);
        request.setStartTime(start);
        request.setEndTime(end);
        return request;
    }

    private ProductStock warehouse(int stock) {
        ProductStock productStock = new ProductStock();
        productStock.setProductId(7L);
        productStock.setStock(stock);
        return productStock;
    }

    private Product product(String price) {
        return product(7L, "4K显示器", price);
    }

    private Product product(Long id, String title, String price) {
        Product product = new Product();
        product.setId(id);
        product.setTitle(title);
        product.setPrice(new BigDecimal(price));
        return product;
    }

    private EventVO storedVO() {
        return EventVO.builder()
                .id(9L)
                .productId(7L)
                .seckillPrice(new BigDecimal("299.90"))
                .stock(10)
                .startTime(nowPlusDay())
                .endTime(nowPlusDay().plusHours(2))
                .status("pending")
                .replayed(false)
                .build();
    }

    private AdminIdempotency idempotencyRow(EventVO vo) throws Exception {
        AdminIdempotency row = new AdminIdempotency();
        row.setIdempotencyKey(KEY);
        row.setResponseJson(objectMapper.writeValueAsString(vo));
        return row;
    }

    private LocalDateTime nowPlusDay() {
        return LocalDateTime.now().plusDays(1);
    }
}
