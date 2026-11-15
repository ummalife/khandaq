package com.zoffcc.applications.trifa;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * KHANDAQ (0.2.56): one pass at a time per key, and nobody waits for it. A pass asked for while another of the
 * same key runs is not made beside it: the running one makes one more when it is done, however many were asked
 * for meanwhile, so what they were asked for is still done, after what the running pass did.
 *
 * <p>HelperGroup flushes a group's waiting messages through it. Two flushes at once read the same waiting rows
 * and both sent them, so the members got a message twice.
 */
final class CoalescingGate
{
    /** Per key: 0 while no pass runs, else 1 plus the passes asked for while it runs. */
    private final ConcurrentHashMap<String, AtomicInteger> work = new ConcurrentHashMap<>();

    /**
     * Asks for a pass over {@code key}. True: the caller makes it now, with {@link #runPasses}. False: a pass of
     * that key is running and makes one more when it is done.
     */
    boolean request(final String key)
    {
        return counter(key).getAndIncrement() == 0;
    }

    /**
     * Makes the pass {@link #request} gave the caller, then one more for as long as passes were asked for during
     * the last one. A pass that throws ends it: the key is free again and the throwable goes on.
     */
    void runPasses(final String key, final Runnable pass)
    {
        final AtomicInteger asked = counter(key);
        int taken = 1;
        try
        {
            while (true)
            {
                pass.run();
                taken = asked.addAndGet(-taken);
                if (taken == 0)
                {
                    return;
                }
            }
        }
        catch (RuntimeException | Error e)
        {
            asked.set(0);
            throw e;
        }
    }

    /** {@link #request}, then {@link #runPasses} when it is this caller's turn. True when this caller made it. */
    boolean run(final String key, final Runnable pass)
    {
        if (!request(key))
        {
            return false;
        }
        runPasses(key, pass);
        return true;
    }

    /** Frees {@code key} when {@link #request} gave the caller a pass that could not be started. */
    void abandon(final String key)
    {
        counter(key).set(0);
    }

    private AtomicInteger counter(final String key)
    {
        return work.computeIfAbsent(key, k -> new AtomicInteger());
    }
}
