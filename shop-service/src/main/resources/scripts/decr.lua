-- decr.lua
-- 原子性扣减库存脚本
-- KEYS[1] = stockKey
-- ARGV[1] = numToDecr (optional, default 1)

local stock = tonumber(redis.call('get', KEYS[1]) or '-1')
local decr = tonumber(ARGV[1] or '1')
if stock < 0 then
    -- 未初始化库存，返回 -2
    return -2
end
if stock < decr then
    -- 库存不足
    return 0
end
-- 扣减
local res = redis.call('decrby', KEYS[1], decr)
return res

