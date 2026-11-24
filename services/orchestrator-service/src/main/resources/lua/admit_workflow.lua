-- KEYS[1]: orynx:admission:active_workflows (Global Set of active workflow IDs)
-- KEYS[2]: orynx:admission:user_workflows:{userId} (Per-user Set of active workflow IDs)
-- KEYS[3]: orynx:admission:lease:{workflowId} (Lease key for specific workflow)
-- ARGV[1]: workflowId
-- ARGV[2]: maxGlobal (number, <= 0 means unlimited)
-- ARGV[3]: maxUser (number, <= 0 means unlimited)
-- ARGV[4]: ttlSeconds (lease TTL in seconds)
-- ARGV[5]: leaseToken (metadata token / instance ID)

local workflowId = ARGV[1]
local maxGlobal = tonumber(ARGV[2])
local maxUser = tonumber(ARGV[3])
local ttlSeconds = tonumber(ARGV[4])
local leaseToken = ARGV[5]

-- 1. Idempotency Check: Already admitted?
if redis.call('SISMEMBER', KEYS[1], workflowId) == 1 then
    -- Refresh TTL on lease key
    redis.call('EXPIRE', KEYS[3], ttlSeconds)
    local currentGlobal = redis.call('SCARD', KEYS[1])
    local currentUser = redis.call('SCARD', KEYS[2])
    return {1, "ALREADY_ADMITTED", tostring(currentGlobal), tostring(currentUser), tostring(leaseToken)}
end

-- 2. Check Global Limit
local currentGlobal = redis.call('SCARD', KEYS[1])
if maxGlobal > 0 and currentGlobal >= maxGlobal then
    local currentUser = redis.call('SCARD', KEYS[2])
    return {0, "GLOBAL_LIMIT_EXCEEDED", tostring(currentGlobal), tostring(currentUser), ""}
end

-- 3. Check Per-User Limit
local currentUser = redis.call('SCARD', KEYS[2])
if maxUser > 0 and currentUser >= maxUser then
    return {0, "USER_LIMIT_EXCEEDED", tostring(currentGlobal), tostring(currentUser), ""}
end

-- 4. Admit Workflow Atomically
redis.call('SADD', KEYS[1], workflowId)
redis.call('SADD', KEYS[2], workflowId)
redis.call('SET', KEYS[3], leaseToken, 'EX', ttlSeconds)

-- Fallback safety TTL on per-user set (2x lease TTL) to prevent permanent key leak
redis.call('EXPIRE', KEYS[2], ttlSeconds * 2)

return {1, "ADMITTED", tostring(currentGlobal + 1), tostring(currentUser + 1), tostring(leaseToken)}
