-- 判断锁的标识是否与当前线程一致
if redis.call('GET', KEYS[1]) == ARGV[1] then
    -- 一致，释放锁
    return redis.call('DEL', KEYS[1])
end
-- 不一致，返回0
return 0