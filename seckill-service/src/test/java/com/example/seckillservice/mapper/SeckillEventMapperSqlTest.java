package com.example.seckillservice.mapper;

import com.example.flashmall.common.entity.SeckillEvent;
import com.example.flashmall.common.mapper.SeckillEventMapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.scripting.xmltags.XMLLanguageDriver;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.SqlSource;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 不连库，只验证 @Select 里的 &lt;script&gt; 能被 MyBatis 正常解析，
 * 以及 excludeId 的动态条件按预期拼进去。
 */
class SeckillEventMapperSqlTest {

    private final Configuration configuration = new Configuration();
    private final XMLLanguageDriver driver = new XMLLanguageDriver();

    private String boundSql(Long excludeId) throws NoSuchMethodException {
        Method method = SeckillEventMapper.class.getMethod(
                "selectUnfinishedByProductId", Long.class, LocalDateTime.class, Long.class);
        String script = String.join(" ", method.getAnnotation(Select.class).value());
        SqlSource sqlSource = driver.createSqlSource(configuration, script, SeckillEvent.class);

        Map<String, Object> params = new HashMap<>();
        params.put("productId", 7L);
        params.put("now", LocalDateTime.of(2026, 11, 11, 10, 0));
        params.put("excludeId", excludeId);

        BoundSql boundSql = sqlSource.getBoundSql(params);
        return boundSql.getSql().replaceAll("\\s+", " ");
    }

    @Test
    void sqlAlwaysFiltersUnfinishedEvents() throws NoSuchMethodException {
        String sql = boundSql(null);
        assertTrue(sql.contains("end_time >"), sql);
        assertTrue(sql.contains("product_id = ?"), sql);
    }

    @Test
    void excludeIdIsIncludedWhenProvided() throws NoSuchMethodException {
        assertTrue(boundSql(9L).contains("id <> ?"));
    }

    @Test
    void excludeIdIsOmittedWhenNull() throws NoSuchMethodException {
        assertFalse(boundSql(null).contains("id <> ?"));
    }
}
