package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IVoucherService;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.context.ApplicationContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.annotation.Resource;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Slf4j
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;
    @Resource
    private RedisIdWorker redisIdWorker;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private RedissonClient redissonClient;
    @Resource
    private ApplicationContext applicationContext;

    // 保存完整订单，子线程不依赖请求线程中的 UserHolder
//    private final BlockingQueue<VoucherOrder> orderTasks = new LinkedBlockingQueue<>();
    private final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();
    private volatile boolean running = true;

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    static {
        SECKILL_SCRIPT=new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    @PostConstruct
    public void init() {
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }

    @PreDestroy
    public void destroy() {
        running = false;
        SECKILL_ORDER_EXECUTOR.shutdownNow();
    }

    private class VoucherOrderHandler implements Runnable {
        String queueName = "stream.orders";
        @Override
        public void run() {
            while (running && !Thread.currentThread().isInterrupted()) {

                try {
                    //获取消息队列中的订单信息
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create(queueName, ReadOffset.lastConsumed())
                    );
                    //判断消息是否获取成功
                    if (list == null || list.isEmpty()) {
                        //如果获取失败，说明队列没有消息，继续下一次循环
                        continue;
                    }
                    //获取成功，解析消息
                    MapRecord<String, Object, Object> record = list.get(0);
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(record.getValue(), new VoucherOrder(), true);
                    handleVoucherOrder(voucherOrder);
                    //ACK确认
                    stringRedisTemplate.opsForStream().acknowledge(queueName, "g1", record.getId());
                } catch (Exception e) {
                    if (!running || Thread.currentThread().isInterrupted()) {
                        return;
                    }
                    // 单个订单失败不能终止整个消费线程
                    log.error("处理订单异常", e);
                    handelPendingList();
                }
            }
        }
    }

    private void handelPendingList() {
        String queueName = "stream.orders";
            while (running && !Thread.currentThread().isInterrupted()) {
                try {
                    //获取pendinglist中的订单信息
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1),
                            StreamOffset.create(queueName, ReadOffset.from("0"))
                    );
                    //判断消息是否获取成功
                    if (list == null || list.isEmpty()) {
                        //如果获取失败，说明pendinglist没有消息，跳出循环
                        break;
                    }
                    //获取成功，解析消息
                    MapRecord<String, Object, Object> record = list.get(0);
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(record.getValue(), new VoucherOrder(), true);
                    handleVoucherOrder(voucherOrder);
                    //ACK确认
                    stringRedisTemplate.opsForStream().acknowledge(queueName, "g1", record.getId());
                } catch (Exception e) {
                    if (!running || Thread.currentThread().isInterrupted()) {
                        return;
                    }
                    // 单个订单失败不能终止整个消费线程
                    log.error("处理pendinglist异常", e);
                    try {
                        Thread.sleep(20);
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
    }

//    private class VoucherOrderHandler implements Runnable {
//        @Override
//        public void run() {
//            while (true) {
//                VoucherOrder voucherOrder = null;
//                try {
//                    // 队列为空时阻塞等待，有订单时取出并处理
//                    voucherOrder = orderTasks.take();
//                    //创建订单
//                    handleVoucherOrder(voucherOrder);
//                } catch (Exception e) {
//                    // 单个订单失败不能终止整个消费线程
//                    log.error("处理订单异常", e);
//                }
//            }
//        }
//    }

    private void handleVoucherOrder(VoucherOrder voucherOrder) throws InterruptedException {
        //获取用户
        Long userId = voucherOrder.getUserId();
        RLock lock = redissonClient.getLock("lock:order:" + userId);
        // 等待同一用户的锁，避免锁暂时被占用时直接丢弃订单
        boolean isLock = lock.tryLock();
        //判断是否获取锁成功
        if (!isLock) {
            log.error("不允许重复下单，用户 ID：{}", userId);
            return;
        }
        try {
            proxy.createVoucherOrder(voucherOrder);
        } finally {
            lock.unlock();
        }
    }
    private IVoucherOrderService proxy;
    /**
     * 秒杀优惠券
     * @param voucherId
     * @return
     */
    @Override
    public Result seckillVoucher(Long voucherId) {
        //获取用户
        Long userId = UserHolder.getUser().getId();
        // 提前生成订单 ID，避免 Lua 扣减库存后生成 ID 失败
        long orderId = redisIdWorker.nextId("order");
        //执行lua脚本
        Long result = stringRedisTemplate.execute(SECKILL_SCRIPT, Collections.emptyList(),
                voucherId.toString(), userId.toString(),String.valueOf(orderId));
        int r = result.intValue();
        //判断结果是否是0
        if (result != 0) {
            //不为0，代表没有购买资格
            return Result.fail(result == 1 ? "库存不足" : "不能重复下单");
        }
        //获取代理对象
        proxy = (IVoucherOrderService) AopContext.currentProxy();
        //返回订单id
        return Result.success(orderId);
    }


//    /**
//     * 秒杀优惠券
//     * @param voucherId
//     * @return
//     */
//    public Result seckillVoucher(Long voucherId) {
//        //查询优惠券
//        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
//        //判断秒杀是否开始
//        if (voucher.getBeginTime().isAfter(java.time.LocalDateTime.now())) {
//            return Result.fail("秒杀尚未开始");
//        }
//        //判断秒杀是否结束
//        if (voucher.getEndTime().isBefore(java.time.LocalDateTime.now())) {
//            return Result.fail("秒杀已经结束");
//        }
//        //判断库存是否充足
//        if (voucher.getStock() < 1) {
//            return Result.fail("库存不足");
//        }
//
//        //返回订单id
//        Long userId = UserHolder.getUser().getId();
//        //创建锁对象
//        SimpleRedisLock lock = new SimpleRedisLock(stringRedisTemplate, "order" + userId);
//        RLock lock = redissonClient.getLock("lock:order:" + userId);
//        //获取锁
//        boolean islock = lock.tryLock();
//        //判断是否获取成功
//        if (!islock) {
//            return Result.fail("不允许重复下单");
//        }
//        try {
//            //获取代理对象(事务)
//            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
//            return proxy.createVoucherOrder(voucherId);
//        } finally {
//            //释放锁
//            lock.unlock();
//        }
//
//    }

    /**
     * 将队列中的订单入库，在同一个事务中校验、扣减库存并保存订单
     * @param voucherOrder 请求线程生成的完整订单
     */
    @Override
    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        //一人一单
        Long userId = voucherOrder.getUserId();
        Long voucherId = voucherOrder.getVoucherId();
        Integer count = query().eq("user_id",userId )
                .eq("voucher_id", voucherId).count();
        if (count > 0) {
            log.warn("用户已经购买过一次，用户 ID：{}，优惠券 ID：{}", userId, voucherId);
            return;
        }
        //扣减库存
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherId)
                .gt("stock",0).update();
        if (!success) {
            throw new IllegalStateException("数据库库存不足，订单 ID：" + voucherOrder.getId());
        }
        // 保存请求线程生成的订单，确保入库 ID 与返回给前端的 ID 一致
        if (!save(voucherOrder)) {
            throw new IllegalStateException("保存订单失败，订单 ID：" + voucherOrder.getId());
        }
    }
}
