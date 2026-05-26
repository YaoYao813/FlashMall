-- 秒杀业务Lua脚本
-- KEYS[1]: seckill:stock:{productId} // 库存数量缓存
-- KEYS[2]: seckill:user:{productId}:{userId} // 用户是否已经参与过秒杀的标记
-- ARGV[1]: userId
-- ARGV[2]: productId

local userKey = KEYS[2]
local exists = redis.call('exists', userKey)
if exists == 1 then
    return 2
end

local stockKey = KEYS[1]
local stock = redis.call('get', stockKey)
if not stock or tonumber(stock) <= 0 then
    return 1
end

redis.call('decr', stockKey)
redis.call('setex', userKey, 3600, 1)
return 0
