package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisData;
import io.netty.util.internal.StringUtil;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private CacheClient cacheClient;


    /**
     * 根据id查询商铺信息
     * @param id
     * @return
     */
    public Result queryById(Long id) {
        //缓存穿透
        //Shop shop = queryWithPassThrough(id);
        //自己实现工具类实现缓存穿透
//        Shop shop = cacheClient.queryWithPassThrough(
//                RedisConstants.CACHE_SHOP_KEY, id, Shop.class, this::getById, RedisConstants.CACHE_SHOP_TTL, TimeUnit.SECONDS);

        //互斥锁解决缓存击穿
        //Shop shop = queryWithMutex(id);
        //逻辑过期解决缓存击穿
        //Shop shop = queryWithLogicExpire(id);
        //自己实现工具类实现缓存击穿
        Shop shop = cacheClient.queryWithLogicExpire(
                RedisConstants.CACHE_SHOP_KEY, id, Shop.class, this::getById, RedisConstants.CACHE_SHOP_TTL, TimeUnit.SECONDS);
        if (shop == null) {
            return Result.fail("商铺不存在");
        }
        return Result.success(shop);
    }

    /**
     * 缓存击穿-互斥锁
     * @param id
     * @return
     */
    public Shop queryWithMutex(Long id){
        String key= RedisConstants.CACHE_SHOP_KEY;
        //查询redis缓存是否命中
        String shopJson = stringRedisTemplate.opsForValue().get(key + id);
        if (StrUtil.isNotBlank(shopJson)) {
            //命中，直接返回
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return shop;
        }
        if (shopJson != null ) {
            //命中空值，返回错误信息
            return null ;
        }
        Shop shop = null;
        String lockKey = RedisConstants.LOCK_SHOP_KEY;
        try {
            //如果没有命中,尝试获取互斥锁
            boolean isLock = tryLock(lockKey+ id);
            //判断是否获取锁
            if(!isLock){
                Thread.sleep(50);
                return queryWithMutex(id);
            }
            //再次查询redis缓存是否命中
            shopJson = stringRedisTemplate.opsForValue().get(key + id);
            if (StrUtil.isNotBlank(shopJson)) {
                //命中，直接返回
                shop = JSONUtil.toBean(shopJson, Shop.class);
                return shop;
            }
            if (shopJson != null ) {
                //命中空值，返回错误信息
                return null ;
            }
            //查询数据库
            shop = getById(id);
            //模拟重建延迟
            Thread.sleep(200);
            if (shop == null) {
                //数据库中不存在，空值写入redis并返回错误信息
                stringRedisTemplate.opsForValue().set(key+id,"",RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }
            //数据库中存在，将数据写入redis缓存
            stringRedisTemplate.opsForValue().set(key+id,JSONUtil.toJsonStr(shop),RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            //释放互斥锁
            unlock(lockKey + id);
        }
        //返回店铺数据
        return shop;
    }
    /**
     * 缓存穿透
     * @param id
     * @return
     */
    public Shop queryWithPassThrough(Long id){
        String key= RedisConstants.CACHE_SHOP_KEY;
        //查询redis缓存是否命中
        String shopJson = stringRedisTemplate.opsForValue().get(key + id);
        if (StrUtil.isNotBlank(shopJson)) {
            //命中，直接返回
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return shop;
        }
        if (shopJson != null ) {
            //命中空值，返回错误信息
            return null ;
        }
        //如果没有命中，查询数据库
        Shop shop = getById(id);
        if (shop == null) {
            //数据库中不存在，空值写入redis并返回错误信息
            stringRedisTemplate.opsForValue().set(key+id,"",RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        //数据库中存在，将数据写入redis缓存
        stringRedisTemplate.opsForValue().set(key+id,JSONUtil.toJsonStr(shop),RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
        //返回店铺数据
        return shop;
    }

    //线程池
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);
    /**
     * 缓存击穿-逻辑过期
     * @param id
     * @return
     */
    public Shop queryWithLogicExpire(Long id){
        String key= RedisConstants.CACHE_SHOP_KEY;
        //查询redis缓存是否命中
        String shopJson = stringRedisTemplate.opsForValue().get(key + id);
        if (StrUtil.isBlank(shopJson)) {
            //未命中，直接返回
            return null;
        }
        //如果命中，判断缓存是否过期
        RedisData shopData = JSONUtil.toBean(shopJson, RedisData.class);
        JSONObject data = (JSONObject) shopData.getData();
        Shop shop = JSONUtil.toBean(data, Shop.class);
        LocalDateTime expireTime = shopData.getExpireTime();
        //如果没有过期，直接返回商铺信息
        if (LocalDateTime.now().isBefore(expireTime)){
            return shop;
        }
        //如果过期，需要重建缓存
        //尝试获取互斥锁
        String lockKey = RedisConstants.LOCK_SHOP_KEY+id;
        boolean islock = tryLock(lockKey);
        //判断是否获取锁
        if (islock){
            //成功，再次检测redis缓存是否过期，如果存在无需重建缓存开启独立线程重建缓存
            shopJson = stringRedisTemplate.opsForValue().get(key + id);
            shopData = JSONUtil.toBean(shopJson, RedisData.class);
            expireTime = shopData.getExpireTime();
            if (LocalDateTime.now().isBefore(expireTime)){
                //释放锁
                unlock(lockKey);
                return shop;
            }
            //开启独立线程重建缓存
            CACHE_REBUILD_EXECUTOR.submit(()->{
                //重建缓存
                try {
                    this.saveShop2Redis(id,30L);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    //释放锁
                    unlock(lockKey);
                }
            });
        }
        //返回过期店铺数据
        return shop;
    }

    /**
     * 获得互斥锁
     * @param key
     * @return
     */
    private boolean tryLock(String key){
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    /**
     * 释放互斥锁
     * @param key
     */
    private void unlock(String key){
        stringRedisTemplate.delete(key);
    }

    public void saveShop2Redis(Long id,Long expireSeconds) throws InterruptedException {
        //查询店铺数据
        Shop shop = getById(id);
        //模拟时长消耗
        Thread.sleep(200);
        //封装逻辑过期时间
        RedisData redisData=new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
        //写入redis
        stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY+id,JSONUtil.toJsonStr(redisData));
    }
    /**
     * 更新商铺信息
     * @param
     * @return
     */
    @Transactional
    public Result update(Shop shop) {
        Long id = shop.getId();
        if (id == null) {
            return Result.fail("商铺id不能为空");
        }
        //更新数据库
        updateById(shop);
        //删除缓存
        stringRedisTemplate.delete(RedisConstants.CACHE_SHOP_KEY + id);
        return Result.success();
    }
}
