package ru.fix.stdlib.concurrency.threads;

import ru.fix.aggregating.profiler.ProfiledCall;
import ru.fix.aggregating.profiler.Profiler;
import ru.fix.dynamic.property.api.DynamicProperty;
import ru.fix.dynamic.property.api.PropertySubscription;

import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

public class ProfiledScheduledThreadPoolExecutor extends ScheduledThreadPoolExecutor {

    private final Profiler profiler;

    private final ThreadLocal<ProfiledCall> runExecution = new ThreadLocal<>();
    private final PropertySubscription<Integer> maxPoolSizeSubscription;

    /**
     * Invoked by ctor
     */
    private ThreadFactory threadFactory(String poolName) {
        AtomicInteger counter = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, poolName + "-" + counter.getAndIncrement());
            thread.setContextClassLoader(getClass().getClassLoader());
            return thread;
        };
    }

    private final String poolName;
    private final String queueIndicatorName;
    private final String activeThreadsIndicatorName;
    private final String callRunName;
    private final String poolSizeIndicatorName;
    private final String maxPoolSizeIndicatorName;


    public ProfiledScheduledThreadPoolExecutor(String poolName, DynamicProperty<Integer> maxPoolSize, Profiler profiler) {
        super(
                maxPoolSize.get()
        );
        setThreadFactory(threadFactory(poolName));
        this.profiler = profiler;
        this.poolName = poolName;

        String profilerPoolName = poolName.replace('.', '_');

        queueIndicatorName = metricName(profilerPoolName, "queue");
        activeThreadsIndicatorName = metricName(profilerPoolName,"activeThreads");
        callRunName = metricName(profilerPoolName,"run");
        poolSizeIndicatorName = metricName(profilerPoolName,"poolSize");
        maxPoolSizeIndicatorName = metricName(profilerPoolName,"maxPoolSize");

        this.setRemoveOnCancelPolicy(true);
        //Do not use KeepAliveTime, since idle threads is forbidden to kill
        //If we kill idle threads, tasks scheduled with delay bigger that keepAliveTime
        //will never be launched by scheduler
        super.allowCoreThreadTimeOut(false);

        this.maxPoolSizeSubscription = maxPoolSize
                .createSubscription()
                .setAndCallListener((oldVal, newVal) -> this.setMaxPoolSize(newVal));

        profiler.attachIndicator(queueIndicatorName, () -> (long) this.getQueue().size());
        profiler.attachIndicator(activeThreadsIndicatorName, () -> (long) this.getActiveCount());
        profiler.attachIndicator(poolSizeIndicatorName, () -> (long) this.getPoolSize());
        profiler.attachIndicator(maxPoolSizeIndicatorName, () -> (long) this.getMaximumPoolSize());
    }

    private String metricName(String profilerPoolName, String metricName) {
        return "pool." + profilerPoolName + "." + metricName;
    }

    /**
     * It is restricted to allow core threads timeout for scheduling executor.
     *
     * @throws IllegalStateException this method always throws such exception to indicate that
     * it is not allowed to be called.
     */
    @Override
    public final void allowCoreThreadTimeOut(boolean value) {
        throw new IllegalStateException(
                "It is not allowed to change allowCoreThreadTimeOut property for scheduling executor, " +
                        "since idle threads is forbidden to kill. If we kill idle threads, tasks scheduled " +
                        "with delay bigger that keepAliveTime will never be launched by scheduler."
        );
    }

    @Override
    protected void beforeExecute(Thread t, Runnable r) {
        runExecution.set(profiler.profiledCall(callRunName).start());
        super.beforeExecute(t, r);
    }

    @Override
    protected void afterExecute(Runnable r, Throwable t) {
        ProfiledCall runCall = runExecution.get();
        if (runCall != null) {
            runCall.stop();
            runExecution.remove();
        }
        super.afterExecute(r, t);
    }

    @Override
    protected void terminated() {
        profiler.detachIndicator(queueIndicatorName);
        profiler.detachIndicator(activeThreadsIndicatorName);
        profiler.detachIndicator(poolSizeIndicatorName);
        profiler.detachIndicator(maxPoolSizeIndicatorName);
        maxPoolSizeSubscription.close();
        super.terminated();
    }


    public void setMaxPoolSize(int maxPoolSize) {
        if (maxPoolSize >= getMaximumPoolSize()) {
            setMaximumPoolSize(maxPoolSize);
            setCorePoolSize(maxPoolSize);
        } else {
            setCorePoolSize(maxPoolSize);
            setMaximumPoolSize(maxPoolSize);
        }
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + "(" + this.poolName + ")";
    }
}
