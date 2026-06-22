package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.dto.ScrollResult;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IFollowService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.*;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.BLOG_LIKED_KEY;
import static com.hmdp.utils.RedisConstants.FEED_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {

    @Resource
    private IUserService userService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private IFollowService followService;

    /**
     * 根据id查询博客
     *
     * @param id
     * @return
     */
    @Override
    public Result queryBlogById(Long id) {
        // 查询博客信息
        Blog blog = this.getById(id);
        if (Objects.isNull(blog)){
            return Result.fail("笔记不存在");
        }
        // 查询blog相关的用户信息
        queryUserByBlog(blog);
        // 判断当前用户是否点赞该博客
        isBlogLiked(blog);
        return Result.ok(blog);
    }

    /**
     * 判断当前用户是否点赞该博客
     */
    private void isBlogLiked(Blog blog) {
        UserDTO user = UserHolder.getUser();
        if (Objects.isNull(user)){
            // 当前用户未登录，无需查询点赞
            return;
        }
        Long userId = user.getId();
        String key = BLOG_LIKED_KEY + blog.getId();
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        blog.setIsLike(Objects.nonNull(score));
    }



    /**
     * 查询热门博客
     *
     * @param current
     * @return
     */
    @Override
    public Result queryHotBlog(Integer current) {
        // 根据用户查询
        Page<Blog> page = this.query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        // 查询用户
        records.forEach(blog -> {
            this.queryUserByBlog(blog);
            this.isBlogLiked(blog);
        });
        return Result.ok(records);
    }

    /**
     * 点赞
     *
     * @param id
     * @return
     */
    @Override
    public Result likeBlog(Long id) {
        // 1、判断用户是否点赞
        Long userId = UserHolder.getUser().getId();
        String key = BLOG_LIKED_KEY + id;
        // zscore key value
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        boolean result;
        if (score == null) {
            // 1.1 用户未点赞，点赞数+1
            result = this.update(new LambdaUpdateWrapper<Blog>()
                    .eq(Blog::getId, id)
                    .setSql("liked = liked + 1"));
            if (result) {
                // 数据库更新成功，更新缓存 zadd key value score
                stringRedisTemplate.opsForZSet().add(key, userId.toString(), System.currentTimeMillis());
            }
        } else {
            // 1.2 用户已点赞，点赞数-1
            result = this.update(new LambdaUpdateWrapper<Blog>()
                    .eq(Blog::getId, id)
                    .setSql("liked = liked - 1"));
            if (result) {
                // 数据更新成功，更新缓存 zrem key value
                stringRedisTemplate.opsForZSet().remove(key, userId.toString());
            }
        }
        return Result.ok();
    }

    /**
     * 查询所有点赞博客的用户
     *
     * @param id
     * @return
     */
    @Override
    public Result queryBlogLikes(Long id) {
        // 查询Top5的点赞用户 zrange key 0 4
        String key = BLOG_LIKED_KEY + id;
        Set<String> top5 = stringRedisTemplate.opsForZSet().range(key, 0, 4);
        if (top5 == null || top5.isEmpty()) {
            return Result.ok(Collections.emptyList());
        }
        List<Long> ids = top5.stream().map(Long::valueOf).collect(Collectors.toList());
        List<UserDTO> userDTOList = userService.listByIds(ids).stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());
        return Result.ok(userDTOList);
    }

    /**
     * 保存探店笔记
     *
     * @param blog
     * @return
     */
    @Override
    public Result saveBlog(Blog blog) {
        Long userId = UserHolder.getUser().getId();
        blog.setUserId(userId);
        // 保存探店笔记
        boolean isSuccess = this.save(blog);
        if (!isSuccess){
            return Result.fail("笔记保存失败");
        }
        // 查询笔记作者的所有粉丝
        List<Follow> follows = followService.list(new LambdaQueryWrapper<Follow>()
                .eq(Follow::getFollowUserId, userId));
        // 将笔记推送给所有的粉丝
        for (Follow follow : follows) {
            // 获取粉丝的id
            Long id = follow.getUserId();
            // 推送笔记
            String key = FEED_KEY + id;
            stringRedisTemplate.opsForZSet().add(key, blog.getId().toString(), System.currentTimeMillis());
        }
        return Result.ok(blog.getId());
    }

    /**
     * 关注推送页面的笔记分页
     *
     * @param max
     * @param offset
     * @return
     */
    @Override
    public Result queryBlogOfFollow(Long max, Integer offset) {
        // 1、查询收件箱
        Long userId = UserHolder.getUser().getId();
        String key = FEED_KEY + userId;
        // ZREVRANGEBYSCORE key Max Min LIMIT offset count
        Set<ZSetOperations.TypedTuple<String>> typedTuples = stringRedisTemplate.opsForZSet()
                .reverseRangeByScoreWithScores(key, 0, max, offset, 5);
        // 2、判断收件箱中是否有数据
        if (typedTuples == null || typedTuples.isEmpty()) {
            return Result.ok();
        }

        // 3、收件箱中有数据，则解析数据: blogId、minTime（时间戳）、offset
        List<Long> ids = new ArrayList<>(typedTuples.size());
        long minTime = 0; // 记录当前最小值
        int os = 0; // 偏移量offset，用来计数
        for (ZSetOperations.TypedTuple<String> tuple : typedTuples) { // 5 4 4 2 2
            // 获取id
            ids.add(Long.valueOf(tuple.getValue()));
            // 获取分数（时间戳）
            long time = tuple.getScore().longValue();
            if (time == minTime) {
                // 当前时间等于最小时间，偏移量+1
                os++;
            } else {
                // 当前时间不等于最小时间，重置
                minTime = time;
                os = 1;
            }
        }

        // 4、根据id查询blog（使用in查询的数据是默认按照id升序排序的，这里需要使用我们自己指定的顺序排序）
        String idStr = StrUtil.join(",", ids);
        List<Blog> blogs = this.list(new LambdaQueryWrapper<Blog>().in(Blog::getId, ids)
                .last("ORDER BY FIELD(id," + idStr + ")"));
        // 设置blog相关的用户数据，是否被点赞等属性值
        for (Blog blog : blogs) {
            // 查询blog有关的用户
            queryUserByBlog(blog);
            // 查询blog是否被点赞
            isBlogLiked(blog);
        }

        // 5、封装并返回
        ScrollResult scrollResult = new ScrollResult();
        scrollResult.setList(blogs);
        scrollResult.setOffset(os);
        scrollResult.setMinTime(minTime);

        return Result.ok(scrollResult);
    }



    /**
     * 查询博客相关用户信息
     * @param blog
     */
    private void queryUserByBlog(Blog blog) {
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
    }

}
