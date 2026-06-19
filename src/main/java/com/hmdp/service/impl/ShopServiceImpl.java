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
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.Objects;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

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
     * 根据id查询商铺数据
     *
     * @param id
     * @return
     */
    @Override
    public Result queryById(Long id) {
        // 调用解决缓存穿透的方法
//        Shop shop = cacheClient.handleCachePenetration(CACHE_SHOP_KEY, id, Shop.class,
//                this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);
//        if (Objects.isNull(shop)){
//            return Result.fail("店铺不存在");
//        }

        // 调用解决缓存击穿的方法
        Shop shop = cacheClient.handleCacheBreakdown(CACHE_SHOP_KEY, id, Shop.class,
                this::getById, CACHE_SHOP_TTL, TimeUnit.SECONDS);
        if (Objects.isNull(shop)) {
            return Result.fail("店铺不存在");
        }

        return Result.ok(shop);
    }




    /**
     * 更新店铺信息，并删除缓存
     *
     * @param shop 店铺数据
     * @return
     */
    @Override
    public Result updateByIdWithCache(Shop shop) {
        // 1. 更新数据库
        updateById(shop);
        // 2. 删除缓存
        String key = CACHE_SHOP_KEY + shop.getId();
        stringRedisTemplate.delete(key);
        return Result.ok();
    }




//    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);
//    public Shop queryWithLogicalExpire( Long id ) {
//        String key = CACHE_SHOP_KEY + id;
//        // 1.从redis查询商铺缓存
//        String json = stringRedisTemplate.opsForValue().get(key);
//        // 2.判断是否存在
//        if (StrUtil.isBlank(json)) {
//            // 3.不存在，直接返回
//            return null;


//        }
//        // 4.命中，需要先把json反序列化为对象
//        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
//        Shop shop = JSONUtil.toBean((JSONObject) redisData.getData(), Shop.class);
//        LocalDateTime expireTime = redisData.getExpireTime();
//        // 5.判断是否过期
//        if(expireTime.isAfter(LocalDateTime.now())) {
//            // 5.1.未过期，直接返回店铺信息
//            return shop;
//        }
//        // 5.2.已过期，需要缓存重建
//        // 6.缓存重建
//        // 6.1.获取互斥锁
//        String lockKey = LOCK_SHOP_KEY + id;
//        boolean isLock = tryLock(lockKey);
//        // 6.2.判断是否获取锁成功
//        if (isLock){
//            CACHE_REBUILD_EXECUTOR.submit( ()->{
//
//                try{
//                    //重建缓存
//                    this.saveShop2Redis(id,20L);
//                }catch (Exception e){
//                    throw new RuntimeException(e);
//                }finally {
//                    unlock(lockKey);
//                }
//            });
//        }
//        // 6.4.返回过期的商铺信息
//        return shop;
//    }


//    public void saveShop2Redis(Long id,Long expiredSeconds){
//        //1.查询店铺数据
//        Shop shop=getById(id);
//
//        //2.封装逻辑过期时间
//        RedisData redisData = new RedisData();
//        redisData.setData(shop);
//        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expiredSeconds));
//
//        //3.写入redis
//        stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY+id,JSONUtil.toJsonStr(redisData));
//    }
}
