--参数列表
---优惠券id
local voucherId = ARGV[1]
---用户id
local userId = ARGV[2]
--订单id
local id = ARGV[3]

--数据key
---库存key
local stockKey = 'seckill:stock:' .. voucherId
---订单key
local orderKey = 'seckill:order:' .. voucherId

--脚本业务
--1.判断库存是否充足
if (tonumber(redis.call('get', stockKey)) <= 0) then
    --库存不足
    return 1
end
--2.判断用户是否下单
if (redis.call('sismember',orderKey,userId) == 1) then
    --用户已下单
    return 2
end

--3.扣减库存
redis.call('incrby', stockKey, -1)
--4.保存用户下单信息
redis.call('sadd', orderKey, userId)
--5.发送消息到队列
redis.call('xadd', 'stream.orders', '*', 'userId', userId,'voucherId', voucherId, 'id', id)
return 0
