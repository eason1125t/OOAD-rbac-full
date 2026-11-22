-- 文件职责：原子维护同一 sid 的登录/已消费摘要，固定绝对期限；不使用用户版本号。
-- 协作关系：RedisLoginStore 传入摘要及可信时间，不向 Redis 发送原始刷新凭证。
-- KEYS: login hash, consumed-digests set (same sid hash tag).
-- ARGV: action, nowMs, employeeId, createdAtMs, expiresAtMs, digest, nextDigest.
local action, now = ARGV[1], tonumber(ARGV[2])
if action == 'CREATE' then
    if redis.call('EXISTS', KEYS[1], KEYS[2]) ~= 0 then return {'INVALID'} end
    local expires = tonumber(ARGV[5])
    if not expires or expires <= now then return {'INVALID'} end
    redis.call('HSET', KEYS[1], 'employeeId', ARGV[3], 'createdAt', ARGV[4],
        'expiresAt', ARGV[5], 'refreshDigest', ARGV[6])
    redis.call('PEXPIREAT', KEYS[1], expires)
    return {'OK'}
end
if redis.call('EXISTS', KEYS[1]) == 0 then return {'INVALID'} end
local employee = redis.call('HGET', KEYS[1], 'employeeId')
local created = redis.call('HGET', KEYS[1], 'createdAt')
local expiresText = redis.call('HGET', KEYS[1], 'expiresAt')
local expires = tonumber(expiresText)
local current = redis.call('HGET', KEYS[1], 'refreshDigest')
if not employee or not string.match(employee, '^[1-9][0-9]*$') or not tonumber(created)
    or not expires or expires <= tonumber(created) or not current or string.len(current) ~= 64 then
    return {'CORRUPT'}
end
if expires <= now then
    redis.call('DEL', KEYS[1], KEYS[2])
    return {'INVALID'}
end
if action == 'CHECK' then
    if employee == ARGV[3] then return {'OK'} end
    return {'INVALID'}
end
if action == 'REJECT_EMPLOYEE' then
    if employee == ARGV[3] then
        redis.call('DEL', KEYS[1], KEYS[2])
        return {'OK'}
    end
    return {'INVALID'}
end
local matches = current == ARGV[6]
local consumed = redis.call('SISMEMBER', KEYS[2], ARGV[6]) == 1
if action == 'REVOKE' then
    if matches or consumed then
        redis.call('DEL', KEYS[1], KEYS[2])
        return {'OK'}
    end
    return {'INVALID'}
end
if consumed then
    redis.call('DEL', KEYS[1], KEYS[2])
    return {'REUSED'}
end
if not matches then return {'INVALID'} end
if action == 'INSPECT' then return {'OK', employee, created, expiresText} end
if action == 'ROTATE' and employee == ARGV[3] then
    redis.call('SADD', KEYS[2], current)
    redis.call('HSET', KEYS[1], 'refreshDigest', ARGV[7])
    redis.call('PEXPIREAT', KEYS[1], expires)
    redis.call('PEXPIREAT', KEYS[2], expires)
    return {'OK'}
end
return {'INVALID'}
