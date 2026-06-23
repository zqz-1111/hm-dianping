package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import com.hmdp.utils.SystemConstants;
import org.springframework.data.geo.Circle;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.geo.Metrics;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.RedisCallback;

import javax.annotation.Resource;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.Comparator;
import java.util.stream.Collectors;
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

        // UV统计：记录用户访问，用HyperLogLog去重计数
        Long userId = UserHolder.getUser().getId();
        String key = UV_KEY + id + ":" + LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        stringRedisTemplate.opsForHyperLogLog().add(key, userId.toString());

        return Result.ok(shop);
    }




    /**
     * 将商铺数据按类型写入 Redis GEO
     */
    public void saveShop2Redis() {
        // 1. 查询所有商铺
        List<Shop> list = list();
        // 2. 按 typeId 分组，批量写入 GEO
        Map<Long, List<Shop>> map = list.stream().collect(Collectors.groupingBy(Shop::getTypeId));
        for (Map.Entry<Long, List<Shop>> entry : map.entrySet()) {
            Long typeId = entry.getKey();
            List<Shop> shops = entry.getValue();
            String key = SHOP_GEO_KEY + typeId;
            // 用 pipeline 批量写入，提升性能
            stringRedisTemplate.executePipelined((RedisCallback<Object>) connection -> {
                for (Shop shop : shops) {
                    connection.geoCommands().geoAdd(
                            key.getBytes(),
                            new Point(shop.getX(), shop.getY()),
                            shop.getId().toString().getBytes()
                    );
                }
                return null;
            });
        }
    }

    /**
     * 查询附近商铺
     * @param typeId 商铺类型
     * @param x 用户经度
     * @param y 用户纬度
     * @param current 页码
     * @return 带距离的商铺列表
     */
    @Override
    public Result queryNearby(Integer typeId, Integer current, Double x, Double y) {
        // 1. 判断是否需要坐标查询
        if (x == null || y == null) {
            Page<Shop> page = query()
                    .eq("type_id", typeId)
                    .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
            return Result.ok(page.getRecords());
        }

        // 2. 计算分页参数
        int from = (current - 1) * SystemConstants.DEFAULT_PAGE_SIZE;
        int end = current * SystemConstants.DEFAULT_PAGE_SIZE;

        // 3. GEORADIUS 查询附近 5km 的商户，按距离排序，取前 end 个
        String key = SHOP_GEO_KEY + typeId;
        Circle circle = new Circle(new Point(x, y), new Distance(5, Metrics.KILOMETERS));
        GeoResults<RedisGeoCommands.GeoLocation<String>> results = stringRedisTemplate
                .opsForGeo()
                .radius(key, circle,
                        RedisGeoCommands.GeoRadiusCommandArgs.newGeoRadiusArgs()
                                .includeDistance()
                                .sortAscending()
                                .limit(end)
                );
        if (results == null || results.getContent().isEmpty()) {
            return Result.ok(Collections.emptyList());
        }

        // 4. 解析结果，截取 from ~ end 部分
        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> list = results.getContent();
        if (list.size() <= from) {
            return Result.ok(Collections.emptyList());
        }
        List<Long> ids = new ArrayList<>(list.size());
        Map<String, Distance> distanceMap = new HashMap<>(list.size());
        list.stream().skip(from).forEach(result -> {
            String shopIdStr = result.getContent().getName();
            ids.add(Long.valueOf(shopIdStr));
            distanceMap.put(shopIdStr, result.getDistance());
        });

        // 5. 根据 id 查询 Shop，用 ORDER BY FIELD 保持 Redis 的距离排序
        String idStr = StrUtil.join(",", ids);
        List<Shop> shops = query()
                .in("id", ids)
                .last("ORDER BY FIELD(id," + idStr + ")")
                .list();
        for (Shop shop : shops) {
            shop.setDistance(distanceMap.get(shop.getId().toString()).getValue());
        }
        return Result.ok(shops);
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
