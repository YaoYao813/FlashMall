-- checkAndDecr.lua
-- 同时检查库存和用户���否已购买，原子性执行
-- KEYS[1] = stockKey
-- KEYS[2] = userSetKey (可以使用 set/hset/hash 表示已购用户)
-- ARGV[1] = userId

local stock = tonumber(redis.call('get', KEYS[1]) or '-1')
local userId = ARGV[1]

if stock < 0 then
    -- 未初始化库存
    return -2
end
if stock <= 0 then
    -- 库存不足
    return 0
end
-- 检查用户是否已购买：使用 SISMEMBER 判断
local exists = redis.call('sismember', KEYS[2], userId)
if exists == 1 then
    -- 重复购买
    return -1
end
-- 扣减库存并记录用户
redis.call('decr', KEYS[1])
redis.call('sadd', KEYS[2], userId)
return 1

