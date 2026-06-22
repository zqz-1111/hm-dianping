package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Follow;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.service.IFollowService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IUserService;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;

import static com.hmdp.utils.RedisConstants.FOLLOW_KEY;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private IUserService userService;

    /**
     * 关注/取关用户
     *
     * @param followUserId 关注用户的id
     * @param isFollow     true=关注，false=取关
     * @return
     */
    @Override
    public Result follow(Long followUserId, Boolean isFollow) {
        Long userId = UserHolder.getUser().getId();
        // 不能关注自己
        if (userId.equals(followUserId)) {
            return Result.fail("不能关注自己！");
        }
        String key = FOLLOW_KEY + userId;
        if (isFollow) {
            // 关注：先判断是否已关注，避免重复插入
            boolean exists = this.count(new LambdaQueryWrapper<Follow>()
                    .eq(Follow::getUserId, userId)
                    .eq(Follow::getFollowUserId, followUserId)) > 0;
            if (!exists) {
                Follow follow = new Follow();
                follow.setUserId(userId);
                follow.setFollowUserId(followUserId);
                boolean isSuccess = this.save(follow);
                if (isSuccess) {
                    // 写入Redis Set，方便后续共同关注查询
                    stringRedisTemplate.opsForSet().add(key, followUserId.toString());
                }
            }
        } else {
            // 取关：删除关注记录
            boolean isSuccess = this.remove(new LambdaQueryWrapper<Follow>()
                    .eq(Follow::getUserId, userId)
                    .eq(Follow::getFollowUserId, followUserId));
            if (isSuccess) {
                // 从Redis Set移除
                stringRedisTemplate.opsForSet().remove(key, followUserId.toString());
            }
        }
        return Result.ok();
    }

    /**
     * 是否关注了该用户
     *
     * @param followUserId 目标用户id
     * @return data=true 已关注，data=false 未关注
     */
    @Override
    public Result isFollow(Long followUserId) {
        Long userId = UserHolder.getUser().getId();
        int count = this.count(new LambdaQueryWrapper<Follow>()
                .eq(Follow::getUserId, userId)
                .eq(Follow::getFollowUserId, followUserId));
        return Result.ok(count > 0);
    }

    /**
     * 查询共同关注
     *
     * @param id
     * @return
     */
    @Override
    public Result followCommons(Long id) {
        Long userId = UserHolder.getUser().getId();
        String key1 = FOLLOW_KEY + userId;
        String key2 = FOLLOW_KEY + id;
        // 查询当前用户与目标用户的共同关注对象
        Set<String> intersect = stringRedisTemplate.opsForSet().intersect(key1, key2);
        if (Objects.isNull(intersect) || intersect.isEmpty()) {
            return Result.ok(Collections.emptyList());
        }
        List<Long> ids = intersect.stream().map(Long::valueOf).collect(Collectors.toList());
        // 查询共同关注的用户信息
        List<UserDTO> userDTOList = userService.listByIds(ids).stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());
        return Result.ok(userDTOList);
    }

}

