package com.orynx.orchestrator.coordination.lock;

import java.time.Duration;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Distributed locking abstraction ensuring safe distributed coordination across multiple
 * orchestrator instances using Redis token-fenced mutexes.
 */
public interface DistributedLockService {

    /**
     * Attempts to acquire a distributed lock with explicit lease expiration.
     *
     * @param lockKey the identifier of the resource being locked
     * @param token unique token identifying the lock owner
     * @param leaseDuration duration after which the lock automatically expires
     * @return true if acquired successfully, false if already held by another instance
     */
    boolean tryAcquire(String lockKey, String token, Duration leaseDuration);

    /**
     * Releases a previously acquired lock only if the current holder matches the expected token.
     *
     * @param lockKey the identifier of the locked resource
     * @param token the unique token of the expected owner
     * @return true if released by the rightful owner, false if token did not match or lock expired
     */
    boolean release(String lockKey, String token);

    /**
     * Verifies whether the lock is currently held by the expected owner token.
     * Implementations MUST fail closed: if ownership cannot be verified or lock storage is unavailable,
     * this method must return false.
     *
     * @param lockKey the identifier of the locked resource
     * @param token the unique token of the expected owner
     * @return true if the lock is confirmed held by the specified token, false otherwise
     */
    boolean isLockHeldBy(String lockKey, String token);

    /**
     * Executes an operation within a distributed lock boundary, automatically releasing
     * the lock upon completion or error.
     */
    <T> Optional<T> executeWithLock(String lockKey, Duration leaseDuration, Supplier<T> action);
}
