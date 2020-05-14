package ru.fix.stdlib.concurrency.threads

import ru.fix.aggregating.profiler.Profiler
import ru.fix.dynamic.property.api.DynamicProperty
import java.lang.management.ManagementFactory
import java.lang.management.ThreadInfo
import java.util.concurrent.ForkJoinPool

open class ThreadPoolGuard(
        profiler: Profiler,
        checkRate: DynamicProperty<Schedule>,
        private val predicate: () -> Boolean,
        private val listener: (queueSize: Int, threadDump: String) -> Unit
) : AutoCloseable {

    private val scheduler = NamedExecutors.newScheduler(
            "thread-pool-guard",
            DynamicProperty.of(1),
            profiler)

    init {
        scheduler.schedule(checkRate, 0) {
            val queueSize = ForkJoinPool.commonPool().queuedSubmissionCount

            if (predicate()) {
                listener(queueSize, buildDump())
            }
        }
    }

    private fun buildDump(): String {
        val dump = StringBuilder()
        val threadMXBean = ManagementFactory.getThreadMXBean()
        val threadInfos = threadMXBean.getThreadInfo(threadMXBean.allThreadIds, 1000)
        for (threadInfo: ThreadInfo? in threadInfos) {
            if (threadInfo == null) {
                continue
            }
            val state = threadInfo.threadState
            dump
                    .append("\"")
                    .append(threadInfo.threadName)
                    .append("\" ")
                    .append(state)
            val stackTraceElements = threadInfo.stackTrace
            for (stackTraceElement in stackTraceElements) {
                dump
                        .append("\n    at ")
                        .append(stackTraceElement)
            }
            dump.append("\n")
        }
        return dump.toString()
    }

    override fun close() {
        scheduler.shutdown()
    }
}