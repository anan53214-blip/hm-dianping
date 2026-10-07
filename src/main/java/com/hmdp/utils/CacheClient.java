package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

@Component
@Slf4j
public class CacheClient {

    private StringRedisTemplate stringRedisTemplate;

    public CacheClient(StringRedisTemplate stringRedisTemplate){
        this.stringRedisTemplate=stringRedisTemplate;
    }

    public void set(String key, Object value, Long time, TimeUnit unit){
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, unit);
    }

    public void setLogicExpire(String key,Object value, Long time, TimeUnit unit){
        //设置逻辑过期
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        //写入redis
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    public <R,ID> R queryWithPassThrough(
            String keyPrefix, ID id, Class<R> type, Function<ID,R> dbFallback,Long time, TimeUnit unit){
        String key= keyPrefix+id;
        //查询redis缓存是否命中
        String json = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isNotBlank(json)) {
            //命中，直接返回
            R r = JSONUtil.toBean(json, type);
            return r;
        }
        if (json != null ) {
            //命中空值，返回错误信息
            return null ;
        }
        //如果没有命中，查询数据库
        R r = dbFallback.apply(id);
        if (r == null) {
            //数据库中不存在，空值写入redis并返回错误信息
            stringRedisTemplate.opsForValue().set(key,"",RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        //数据库中存在，将数据写入redis缓存
        this.set(key,r,time,unit);
        //返回店铺数据
        return r;
    }

    //线程池
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);
    /**
     * 缓存击穿-逻辑过期
     * @param id
     * @return
     */
    public <R,ID> R queryWithLogicExpire(String keyPrefix, ID id,Class<R> type,Function<ID,R> dbFallback,Long time, TimeUnit unit){
        String key= keyPrefix+id;
        //查询redis缓存是否命中
        String json = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isBlank(json)) {
            //未命中，直接返回
            return null;
        }
        //如果命中，判断缓存是否过期
        RedisData rData = JSONUtil.toBean(json, RedisData.class);
        JSONObject data = (JSONObject) rData.getData();
        R r = JSONUtil.toBean(data, type);
        LocalDateTime expireTime = rData.getExpireTime();
        //如果没有过期，直接返回商铺信息
        if (LocalDateTime.now().isBefore(expireTime)){
            return r;
        }
        //如果过期，需要重建缓存
        //尝试获取互斥锁
        String lockKey = RedisConstants.LOCK_SHOP_KEY+id;
        boolean islock = tryLock(lockKey);
        //判断是否获取锁
        if (islock){
            //成功，再次检测redis缓存是否过期，如果存在无需重建缓存开启独立线程重建缓存
            json = stringRedisTemplate.opsForValue().get(key + id);
            rData = JSONUtil.toBean(json, RedisData.class);
            expireTime = rData.getExpireTime();
            if (LocalDateTime.now().isBefore(expireTime)){
                //释放锁
                unlock(lockKey);
                return r;
            }
            //开启独立线程重建缓存
            CACHE_REBUILD_EXECUTOR.submit(()->{
                //重建缓存
                try {
                    //查询数据库
                    R r1 = dbFallback.apply(id);
                    //写入redis
                    this.set(key,r1,time,unit);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    //释放锁
                    unlock(lockKey);
                }
            });
        }
        //返回过期店铺数据
        return r;
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

}
