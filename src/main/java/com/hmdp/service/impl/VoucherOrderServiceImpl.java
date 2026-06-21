package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.context.ApplicationContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;


import static com.hmdp.utils.RedisConstants.STREAM_ORDER_KEY;

@Service
@Slf4j
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private RedissonClient redissonClient;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private ApplicationContext applicationContext;

    private IVoucherOrderService proxy;

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    // 异步处理线程池
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();

    // 消费者组和消费者名称
    private static final String GROUP_NAME = "g1";
    private static final String CONSUMER_NAME = "c1";

    // 在类初始化之后执行
    @PostConstruct
    private void init() {
        // 获取代理对象（用于 @Transactional）
        proxy = applicationContext.getBean(IVoucherOrderService.class);
        // 创建消费者组（stream 不存在时会报错，需要先确保 stream 存在）
        try {
            stringRedisTemplate.opsForStream().createGroup(STREAM_ORDER_KEY, GROUP_NAME);
        } catch (Exception e) {
            // 可能是 stream 不存在，也可能是组已存在，先尝试创建 stream 再建组
            try {
                // 推一条初始化消息，让 stream 自动创建
                stringRedisTemplate.opsForStream().add(STREAM_ORDER_KEY, Collections.singletonMap("init", "0"));
                // 再创建消费者组
                stringRedisTemplate.opsForStream().createGroup(STREAM_ORDER_KEY, GROUP_NAME);
            } catch (Exception ex) {
                log.debug("消费者组已存在，跳过创建");
            }
        }
        // 启动 Stream 消费者线程
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }

    /**
     * Stream 消费者线程
     */
    private class VoucherOrderHandler implements Runnable {
        @Override
        public void run() {
            while (true) {
                try {
                    // 1.从 Stream 读取消息（消费者组模式）
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from(GROUP_NAME, CONSUMER_NAME),
                            StreamReadOptions.empty().count(1).block(java.time.Duration.ofSeconds(2)),
                            StreamOffset.create(STREAM_ORDER_KEY, ReadOffset.lastConsumed())
                    );
                    // 2.判断消息是否为空
                    if (list == null || list.isEmpty()) {
                        // 没有消息，继续循环
                        continue;
                    }
                    // 3.解析消息中的订单信息
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> value = record.getValue();
                    // 跳过初始化消息（没有 orderId 字段）
                    Object orderIdObj = value.get("orderId");
                    if (orderIdObj == null) {
                        // ACK 掉初始化消息，避免反复读到
                        stringRedisTemplate.opsForStream().acknowledge(STREAM_ORDER_KEY, GROUP_NAME, record.getId());
                        continue;
                    }
                    VoucherOrder voucherOrder = new VoucherOrder();
                    voucherOrder.setId(Long.valueOf(orderIdObj.toString()));
                    voucherOrder.setUserId(Long.valueOf(value.get("userId").toString()));
                    voucherOrder.setVoucherId(Long.valueOf(value.get("voucherId").toString()));
                    // 4.创建订单
                    handleVoucherOrder(voucherOrder);
                    // 5.ACK 确认消息
                    stringRedisTemplate.opsForStream().acknowledge(STREAM_ORDER_KEY, GROUP_NAME, record.getId());
                } catch (Exception e) {
                    log.error("处理订单异常", e);
                    // 如果发生异常，处理 pending 消息（兜底，防止消息堆积）
                    handlePendingList();
                }
            }
        }
    }

    /**
     * 处理 pending 消息（兜底）
     * 上次读取后如果发生异常没有 ACK，消息会留在 pending 列表
     * 这里从 pending 列表重新读取并处理
     */
    private void handlePendingList() {
        while (true) {
            try {
                // 1.获取 pending 列表中的消息
                List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                        Consumer.from(GROUP_NAME, CONSUMER_NAME),
                        StreamReadOptions.empty().count(1),
                        StreamOffset.create(STREAM_ORDER_KEY, ReadOffset.from("0"))
                );
                // 2.判断是否为空
                if (list == null || list.isEmpty()) {
                    // 没有异常消息，跳出循环
                    break;
                }
                // 3.解析消息
                MapRecord<String, Object, Object> record = list.get(0);
                Map<Object, Object> value = record.getValue();
                VoucherOrder voucherOrder = new VoucherOrder();
                voucherOrder.setId(Long.valueOf(value.get("orderId").toString()));
                voucherOrder.setUserId(Long.valueOf(value.get("userId").toString()));
                voucherOrder.setVoucherId(Long.valueOf(value.get("voucherId").toString()));
                // 4.创建订单
                handleVoucherOrder(voucherOrder);
                // 5.ACK 确认
                stringRedisTemplate.opsForStream().acknowledge(STREAM_ORDER_KEY, GROUP_NAME, record.getId());
            } catch (Exception e) {
                log.error("处理 pending 订单异常", e);
                // 如果还有异常，暂停一会再试，避免死循环
                try {
                    Thread.sleep(20);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
                break;
            }
        }
    }

    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        // 1.获取用户
        Long userId = voucherOrder.getUserId();
        // 2.创建锁对象
        RLock redisLock = redissonClient.getLock("lock:order:" + userId);
        // 3.尝试获取锁
        boolean isLock = redisLock.tryLock();
        // 4.判断是否获得锁成功
        if (!isLock) {
            // 获取锁失败，直接返回失败或者重试
            log.error("不允许重复下单！");
            return;
        }
        try {
            // 注意：由于是spring的事务是放在threadLocal中，此时的是多线程，事务会失效
            proxy.createVoucherOrder(voucherOrder);
        } finally {
            // 释放锁
            redisLock.unlock();
        }
    }

    public Result seckillVoucher(Long voucherId) {
        Long userId = UserHolder.getUser().getId();
        // 1.生成订单id（Lua 脚本中需要用到）
        long orderId = redisIdWorker.nextId("order");
        // 2.执行lua脚本（传入 orderId，Lua 脚本会直接发送到 Stream）
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString(), String.valueOf(orderId)
        );
        int r = result.intValue();
        // 3.判断结果是否为0
        if (r != 0) {
            // 3.1.不为0 ，代表没有购买资格
            return Result.fail(r == 1 ? "库存不足" : "不能重复下单");
        }
        // 4.返回订单id
        return Result.ok(orderId);
    }

    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        Long userId = voucherOrder.getUserId();
        // 5.1.查询订单
        int count = query().eq("user_id", userId).eq("voucher_id", voucherOrder.getVoucherId()).count();
        // 5.2.判断是否存在
        if (count > 0) {
            // 用户已经购买过了
            log.error("用户已经购买过了");
            return;
        }

        // 6.扣减库存
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1") // set stock = stock - 1
                .eq("voucher_id", voucherOrder.getVoucherId()).gt("stock", 0) // where id = ? and stock > 0
                .update();

        save(voucherOrder);
    }
}
