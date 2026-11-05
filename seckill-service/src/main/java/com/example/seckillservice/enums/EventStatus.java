package com.example.seckillservice.enums;

import com.fasterxml.jackson.annotation.JsonValue;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 秒杀活动状态。库表没有 status 字段，状态由 start_time / end_time 推导，
 * 口径见 agent-service/contracts/tools.md §1.2：
 * <ul>
 *   <li>now &lt; startTime → pending</li>
 *   <li>startTime &lt;= now &lt;= endTime → running</li>
 *   <li>now &gt; endTime → ended</li>
 * </ul>
 */
public enum EventStatus {

    PENDING("pending"),
    RUNNING("running"),
    ENDED("ended");

    private final String code;

    EventStatus(String code) {
        this.code = code;
    }

    @JsonValue
    public String getCode() {
        return code;
    }

    /**
     * 按起止时间推导状态。边界按契约处理：恰好在 startTime 或 endTime 上算 running。
     */
    public static EventStatus of(LocalDateTime startTime, LocalDateTime endTime, LocalDateTime now) {
        if (startTime == null || endTime == null) {
            throw new IllegalArgumentException("活动起止时间不能为空");
        }
        if (now.isBefore(startTime)) {
            return PENDING;
        }
        if (now.isAfter(endTime)) {
            return ENDED;
        }
        return RUNNING;
    }

    /**
     * 解析列表查询的 status 参数。未知值返回空，由调用方决定报错还是忽略。
     */
    public static Optional<EventStatus> fromCode(String code) {
        if (code == null) {
            return Optional.empty();
        }
        for (EventStatus status : values()) {
            if (status.code.equalsIgnoreCase(code)) {
                return Optional.of(status);
            }
        }
        return Optional.empty();
    }
}
