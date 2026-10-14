package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Follow;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.service.IFollowService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IUserService;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;
import java.util.Locale;
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

    @Override
    public Result follow(Long id, Boolean isFollow) {
        //获取当前用户id
        Long userId = UserHolder.getUser().getId();
        String key = "follows:"+userId;
        //判断是关注或取关
        if (isFollow){
            Follow follow = new Follow();
            follow.setUserId(userId);
            follow.setFollowUserId(id);
            boolean success = save(follow);
            if (success) {
                stringRedisTemplate.opsForSet().add(key,id.toString());
            }
        }
        else {
            boolean success = remove(new QueryWrapper<Follow>()
                    .eq("user_id", userId).eq("follow_user_id", id));
            if (success) {
                stringRedisTemplate.opsForSet().remove(key,id.toString());
            }
        }

        return Result.success();
    }

    @Override
    public Result isFollow(Long id) {
        //获取当前用户id
        Long userId = UserHolder.getUser().getId();
        Integer count = query().eq("user_id", userId).eq("follow_user_id", id).count();
        return Result.success(count>0);
    }

    @Override
    public Result followCommons(Long id) {
        //获取当前用户id
        Long userId = UserHolder.getUser().getId();
        String key1 = "follows:" + userId;
        String key2 = "follows:" + id;
        //查询共同关注id
        Set<String> commonFollow = stringRedisTemplate.opsForSet().intersect(key1, key2);
        if (commonFollow == null || commonFollow.isEmpty()) {
            return Result.success();
        }
        List<Long> ids = commonFollow.stream().map(Long::valueOf).collect(Collectors.toList());
        List<UserDTO> userDTO = userService.listByIds(ids).stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());
        return Result.success(userDTO);
    }
}
