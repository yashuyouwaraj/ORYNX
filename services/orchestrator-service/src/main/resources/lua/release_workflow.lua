-- KEYS[1]: orynx:admission:active_workflows (Global Set of active workflow IDs)
-- KEYS[2]: orynx:admission:user_workflows:{userId} (Per-user Set of active workflow IDs)
-- KEYS[3]: orynx:admission:lease:{workflowId} (Lease key for specific workflow)
-- ARGV[1]: workflowId

local workflowId = ARGV[1]

redis.call('SREM', KEYS[1], workflowId)
redis.call('SREM', KEYS[2], workflowId)
redis.call('DEL', KEYS[3])

local currentGlobal = redis.call('SCARD', KEYS[1])
local currentUser = redis.call('SCARD', KEYS[2])

return {1, "RELEASED", tostring(currentGlobal), tostring(currentUser)}
