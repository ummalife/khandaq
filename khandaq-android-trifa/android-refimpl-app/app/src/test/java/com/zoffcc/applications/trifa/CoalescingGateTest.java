package com.zoffcc.applications.trifa;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * KHANDAQ (0.2.56): the gate HelperGroup flushes a group's waiting messages through. Two flushes at once read
 * the same PENDING rows and both sent them; through the gate one runs, and a flush asked for meanwhile becomes
 * one more pass of the running one, so nothing queued meanwhile is left behind and nobody waits.
 */
public class CoalescingGateTest
{
    private static final long WAIT_S = 10;

    @Test
    public void a_free_key_runs_its_pass_once()
    {
        final CoalescingGate gate = new CoalescingGate();
        final AtomicInteger passes = new AtomicInteger();
        assertTrue(gate.run("g", passes::incrementAndGet));
        assertEquals(1, passes.get());
        // Free again afterwards.
        assertTrue(gate.run("g", passes::incrementAndGet));
        assertEquals(2, passes.get());
    }

    @Test
    public void a_pass_asked_for_during_a_pass_runs_once_after_it()
    {
        final CoalescingGate gate = new CoalescingGate();
        final List<String> log = new ArrayList<>();
        final AtomicBoolean first = new AtomicBoolean(true);
        final Runnable pass = new Runnable()
        {
            @Override
            public void run()
            {
                log.add("pass");
                if (first.getAndSet(false))
                {
                    // Three more asked for while this one runs: handed over, and made as one.
                    log.add("asked:" + gate.run("g", this));
                    log.add("asked:" + gate.run("g", this));
                    log.add("asked:" + gate.run("g", this));
                }
            }
        };
        assertTrue(gate.run("g", pass));
        assertEquals(Arrays.asList("pass", "asked:false", "asked:false", "asked:false", "pass"), log);
    }

    @Test
    public void keys_do_not_hold_each_other()
    {
        final CoalescingGate gate = new CoalescingGate();
        final AtomicInteger other = new AtomicInteger();
        assertTrue(gate.run("a", () -> assertTrue(gate.run("b", other::incrementAndGet))));
        assertEquals(1, other.get());
    }

    @Test
    public void a_pass_that_throws_frees_its_key()
    {
        final CoalescingGate gate = new CoalescingGate();
        try
        {
            gate.run("g", () -> {
                throw new IllegalStateException("boom");
            });
            fail("the throwable goes on");
        }
        catch (IllegalStateException expected)
        {
            assertEquals("boom", expected.getMessage());
        }
        final AtomicInteger passes = new AtomicInteger();
        assertTrue(gate.run("g", passes::incrementAndGet));
        assertEquals(1, passes.get());
    }

    @Test
    public void an_abandoned_request_frees_its_key()
    {
        final CoalescingGate gate = new CoalescingGate();
        assertTrue(gate.request("g"));
        assertFalse(gate.request("g"));
        gate.abandon("g");
        final AtomicInteger passes = new AtomicInteger();
        assertTrue(gate.run("g", passes::incrementAndGet));
        assertEquals(1, passes.get());
    }

    @Test
    public void a_second_thread_neither_waits_nor_runs_beside_the_first() throws Exception
    {
        final CoalescingGate gate = new CoalescingGate();
        final CountDownLatch inPass = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicInteger passes = new AtomicInteger();
        final AtomicInteger running = new AtomicInteger();
        final AtomicInteger mostAtOnce = new AtomicInteger();
        final Runnable pass = () -> {
            mostAtOnce.accumulateAndGet(running.incrementAndGet(), Math::max);
            if (passes.incrementAndGet() == 1)
            {
                inPass.countDown();
                try
                {
                    assertTrue(release.await(WAIT_S, TimeUnit.SECONDS));
                }
                catch (InterruptedException e)
                {
                    Thread.currentThread().interrupt();
                }
            }
            running.decrementAndGet();
        };
        final Thread first = new Thread(() -> gate.run("g", pass));
        first.start();
        assertTrue(inPass.await(WAIT_S, TimeUnit.SECONDS));

        // The first pass holds the key: this call returns at once, without a pass of its own.
        assertFalse(gate.run("g", pass));
        assertEquals(1, passes.get());

        release.countDown();
        first.join(TimeUnit.SECONDS.toMillis(WAIT_S));
        assertFalse(first.isAlive());
        // The first thread made the pass the second asked for.
        assertEquals(2, passes.get());
        assertEquals(1, mostAtOnce.get());
    }

    @Test
    public void flushes_from_many_threads_send_each_waiting_message_once_and_in_order() throws Exception
    {
        // A flush as HelperGroup makes it: read the waiting rows oldest first, send each, mark it sent.
        final CoalescingGate gate = new CoalescingGate();
        final List<Integer> waiting = Collections.synchronizedList(new ArrayList<>());
        final ConcurrentHashMap<Integer, Boolean> marked = new ConcurrentHashMap<>();
        final List<Integer> sent = Collections.synchronizedList(new ArrayList<>());
        final Runnable flush = () -> {
            final List<Integer> rows;
            synchronized (waiting)
            {
                rows = new ArrayList<>(waiting);
            }
            for (final Integer row : rows)
            {
                if (marked.containsKey(row))
                {
                    continue;
                }
                sent.add(row);
                Thread.yield();
                marked.put(row, Boolean.TRUE);
                waiting.remove(row);
            }
        };

        final int threads = 8;
        final int perThread = 50;
        final AtomicInteger next = new AtomicInteger();
        final CountDownLatch start = new CountDownLatch(1);
        final List<Thread> workers = new ArrayList<>();
        for (int t = 0; t < threads; t++)
        {
            final Thread worker = new Thread(() -> {
                try
                {
                    assertTrue(start.await(WAIT_S, TimeUnit.SECONDS));
                }
                catch (InterruptedException e)
                {
                    Thread.currentThread().interrupt();
                    return;
                }
                for (int i = 0; i < perThread; i++)
                {
                    // Queue one in order, then ask for a flush, as a send to a group not connected does.
                    synchronized (waiting)
                    {
                        waiting.add(next.getAndIncrement());
                    }
                    gate.run("g", flush);
                }
            });
            workers.add(worker);
            worker.start();
        }
        start.countDown();
        for (final Thread worker : workers)
        {
            worker.join(TimeUnit.SECONDS.toMillis(WAIT_S));
            assertFalse(worker.isAlive());
        }

        final int total = threads * perThread;
        assertEquals("every message went exactly once", total, sent.size());
        for (int i = 0; i < total; i++)
        {
            assertEquals("in the order they were queued", Integer.valueOf(i), sent.get(i));
        }
        assertTrue(waiting.isEmpty());
    }
}
