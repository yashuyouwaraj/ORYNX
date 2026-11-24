-- KEYS[1]: orynx:{admission}:active_workflows (Global Set of active workflow IDs)
-- KEYS[2]: orynx:{admission}:user_workflows:{fallbackUserId} (Fallback user set)
-- KEYS[3]: orynx:{admission}:lease:{workflowId} (Lease key for specific workflow)
-- ARGV[1]: workflowId

local workflowId = ARGV[1]

-- 1. Remove from global active set
redis.call('SREM', KEYS[1], workflowId)

-- 2. Inspect lease key to extract real userId from metadata (format: userId:leaseToken:admittedAtMs)
local leaseValue = redis.call('GET', KEYS[3])
local realUserId = nil
if leaseValue then
    local sep1 = string.find(leaseValue, ":")
    if sep1 then
        realUserId = string.sub(leaseValue, 1, sep1 - 1)
    end
end

-- 3. Clean up user active set using extracted realUserId
if realUserId and realUserId ~= "" then
    local realUserKey = "orynx:{admission}:user_workflows:" .. realUserId
    redis.call('SREM', realUserKey, workflowId)
end

-- 4. Also clean up fallback user set if specified
if KEYS[2] and KEYS[2] ~= "" then
    redis.call('SREM', KEYS[2], workflowId)
end

-- 5. Delete lease key
redis.call('DEL', KEYS[3])

local currentGlobal = redis.call('SCARD', KEYS[1])
local currentUser = 0
if realUserId and realUserId ~= "" then
    currentUser = redis.call('SCARD', "orynx:{admission}:user_workflows:" .. realUserId)
elseif KEYS[2] and KEYS[2] ~= "" then
    currentUser = redis.call('SCARD', KEYS[2])
end

return {1, "RELEASED", tostring(currentGlobal), tostring(currentUser)}
