package com.hmdp.service.impl;

import cn.hutool.json.JSONUtil;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;
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
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public List<ShopType> queryList() {
        //查询redis缓存是否存在商铺列表信息
        String key = RedisConstants.SHOP_TYPE_LIST;
        String shopTypeListJson = stringRedisTemplate.opsForValue().get(key);

        //如果不为空，转为shopType对象返回
        if(shopTypeListJson!=null){
            List<ShopType> shopTypeList = JSONUtil.toList(shopTypeListJson, ShopType.class);
            return shopTypeList;
        }
        //如果为空，查询数据库
        List<ShopType> shopTypeList = query().orderByAsc("sort").list();
        if (shopTypeList ==null || shopTypeList.isEmpty()){
            return null;
        }
        //写入redis缓存
        stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(shopTypeList),RedisConstants.SHOP_TYPE_LIST_TTL, TimeUnit.MINUTES);
        return shopTypeList;
    }
}
