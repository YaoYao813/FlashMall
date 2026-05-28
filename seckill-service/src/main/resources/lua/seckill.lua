-- 秒杀业务Lua脚本
-- KEYS[1]: seckill:stock:{productId} // 库存数量缓存
-- KEYS[2]: seckill:user:{productId}:{userId} // 用户是否已经参与过秒杀的标记
-- ARGV[1]: userId
-- ARGV[2]: productId

local userKey = KEYS[2]
local exists = redis.call('exists', userKey) -- 用户是否已经参与过秒杀的标记
if exists == 1 then
    return 2 -- 已经参与过秒杀，返回2表示重复参与
end

local stockKey = KEYS[1]
local stock = redis.call('get', stockKey) -- 检查库存数量
if not stock or tonumber(stock) <= 0 then
    return 1 -- 库存不足，返回1表示秒杀结束
end

redis.call('decr', stockKey) -- 减少库存数量
redis.call('setex', userKey, 3600, 1) -- 设置用户参与秒杀的标记，过期时间为1小时
return 0
