package com.github.phantomthief.pool.impl;

import static com.google.common.base.Throwables.throwIfUnchecked;
import static com.google.common.util.concurrent.MoreExecutors.shutdownAndAwaitTermination;
import static com.google.common.util.concurrent.Uninterruptibles.sleepUninterruptibly;
import static java.lang.Math.min;
import static java.lang.System.currentTimeMillis;
import static java.util.concurrent.Executors.newSingleThreadScheduledExecutor;
import static java.util.concurrent.TimeUnit.DAYS;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static java.util.function.Function.identity;
import static java.util.stream.Collectors.toMap;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Consumer;
import java.util.function.Supplier;

import javax.annotation.concurrent.ThreadSafe;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.phantomthief.pool.Pool;
import com.github.phantomthief.pool.impl.ConcurrencyAdjustStrategy.AdjustResult;
import com.github.phantomthief.pool.impl.ConcurrencyAdjustStrategy.CurrentObject;
import com.github.phantomthief.util.ThrowableFunction;
import com.google.common.util.concurrent.ThreadFactoryBuilder;

/**
 * 每个对象可以同时被使用n次
 *
 * @author w.vela
 * Created on 06/09/2016.
 */
@ThreadSafe
class ConcurrencyAwarePool<T> implements Pool<T> {

    private static final Logger logger = LoggerFactory.getLogger(ConcurrencyAwarePool.class);

    private final Consumer<T> destroy;

    private final List<CounterWrapper> currentAvailable;

    private final ScheduledExecutorService scheduledExecutor;
    private final ScheduledFuture<?> scheduledFuture;

    private volatile boolean closed = false;

    /**
     * see {@link ConcurrencyAwarePoolBuilder#builder()}
     */
    ConcurrencyAwarePool(ConcurrencyAwarePoolBuilder<T> builder) {
        this.destroy = builder.destroy;

        Supplier<T> factory = builder.factory;
        int minIdle = builder.minIdle;
        int maxSize = builder.maxSize;

        currentAvailable = new ArrayList<>(maxSize);

        for (int i = 0; i < minIdle; i++) {
            currentAvailable.add(new CounterWrapper(factory.get()));
        }

        ConcurrencyAdjustStrategy<T> strategy = builder.strategy;
        if (strategy != null) {
            long periodInMs = strategy.evaluatePeriod().toMillis();
            scheduledExecutor = newSingleThreadScheduledExecutor(new ThreadFactoryBuilder() //
                    .setNameFormat("concurrency-pool-adjust-%d") //
                    .build());
            scheduledFuture = scheduledExecutor.scheduleWithFixedDelay(() -> {
                try {
                    Map<CurrentObject<T>, CounterWrapper> map = copyCurrent();
                    AdjustResult<T> adjust = strategy.adjust(map.keySet());
                    if (adjust == null) {
                        return;
                    }
                    int realToCreate = min(adjust.getCreate(), maxSize - currentAvailable.size());
                    for (int i = 0; i < realToCreate; i++) {
                        currentAvailable.add(new CounterWrapper(factory.get()));
                    }

                    if (adjust.getEvict() != null) {
                        adjust.getEvict().stream() //
                                .map(map::get) //
                                .filter(Objects::nonNull) //
                                .distinct() //
                                .limit(Math.max(0, currentAvailable.size() - minIdle)) //
                                .forEach(wrapper -> {
                                    currentAvailable.removeIf(it -> it == wrapper);
                                    try {
                                        wrapper.shutdownAndAwaitTermination();
                                    } catch (Throwable e) {
                                        logger.error("", e);
                                    }
                                });
                    }
                } catch (Throwable e) {
                    logger.error("", e);
                }
            }, periodInMs, periodInMs, MILLISECONDS);
        } else {
            scheduledFuture = null;
            scheduledExecutor = null;
        }
    }

    private Map<CurrentObject<T>, CounterWrapper> copyCurrent() {
        return currentAvailable.stream() //
                .collect(toMap(CurrentObject::new, identity()));
    }

    @Override
    public <V, X extends Throwable> V supply(ThrowableFunction<T, V, X> function) throws X {
        if (closed || currentAvailable.isEmpty()) {
            throw new IllegalStateException("pool is closed.");
        }
        CounterWrapper counterWrapper;
        do {
            try {
                counterWrapper = currentAvailable
                        .get(ThreadLocalRandom.current().nextInt(currentAvailable.size()));
                break;
            } catch (IndexOutOfBoundsException e) {
                // ignore, for fast, do it without lock, retry if failed on concurrent modification.
            }
        } while (true);

        counterWrapper.concurrency.increment();
        try {
            return function.apply(counterWrapper.obj);
        } finally {
            counterWrapper.concurrency.decrement();
        }
    }

    @Override
    public void close() {
        if (scheduledFuture != null) {
            scheduledFuture.cancel(true);
            shutdownAndAwaitTermination(scheduledExecutor, 1, DAYS);
        }
        Iterator<CounterWrapper> iterator = currentAvailable.iterator();
        Throwable throwable = null;
        while (iterator.hasNext()) {
            CounterWrapper wrapper = iterator.next();
            iterator.remove();
            try {
                wrapper.shutdownAndAwaitTermination();
            } catch (Throwable e) {
                throwable = e;
            }
        }
        closed = true;
        if (throwable != null) {
            throwIfUnchecked(throwable);
            throw new RuntimeException(throwable);
        }
    }

    class CounterWrapper {

        final T obj;
        final LongAdder concurrency = new LongAdder();
        final long createTime = currentTimeMillis();

        CounterWrapper(T obj) {
            this.obj = obj;
        }

        void shutdownAndAwaitTermination() {
            while (concurrency.intValue() > 0) {
                sleepUninterruptibly(1, SECONDS);
            }
            if (destroy != null) {
                destroy.accept(obj);
            }
        }
    }
}