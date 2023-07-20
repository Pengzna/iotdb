package org.apache.iotdb.commons.service.metric;

import com.google.common.base.Preconditions;
import org.apache.iotdb.commons.concurrent.IoTDBThreadPoolFactory;
import org.apache.iotdb.commons.concurrent.threadpool.ScheduledExecutorUtil;
import org.apache.iotdb.metrics.AbstractMetricService;
import org.apache.iotdb.metrics.metricsets.IMetricSet;
import org.apache.iotdb.metrics.utils.MetricLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public class JvmGcMetrics implements IMetricSet {
    private static final Logger logger = LoggerFactory.getLogger(JvmGcMetrics.class);
    private final ScheduledExecutorService scheduledGCInfoMonitor =
            IoTDBThreadPoolFactory.newSingleThreadScheduledExecutor(
                    "JVM-GC-Statistics-Monitor");
    private Future<?> scheduledGcMonitorFuture;
    // Ring buffers containing GC timings and timestamps when timings were taken
    private final TsAndData[] gcDataBuf;
//    private final long maxGcTimePercentage = 0L;
    private final long maxGcTimePercentage = 70L;
    private final long observationWindowMs = TimeUnit.MINUTES.toMillis(1);
//        private final long observationWindowMs = TimeUnit.SECONDS.toMillis(1);
    private final long sleepIntervalMs = TimeUnit.SECONDS.toMillis(5);
//        private final long sleepIntervalMs = TimeUnit.MILLISECONDS.toMillis(1000);
    private final int bufSize;
    private int startIdx;
    private int endIdx;
    private long startTime;
    private final GcData curData = new GcData();
    private boolean shouldRun = true;
    private final GcTimeAlertHandler alertHandler;

    public JvmGcMetrics() {
        bufSize = (int) (observationWindowMs / sleepIntervalMs + 2);
        // Prevent the user from accidentally creating an abnormally big buffer, which will result in slow calculations and likely inaccuracy.
        Preconditions.checkArgument(bufSize <= 128 * 1024);
        gcDataBuf = new TsAndData[bufSize];
        for (int i = 0; i < bufSize; i++) {
            gcDataBuf[i] = new TsAndData();
        }

        alertHandler = new GcTimeAlerter();
    }

    @Override
    public void bindTo(AbstractMetricService metricService) {
        metricService.createAutoGauge(
                "jvm_gc_accumulated_time_percentage", MetricLevel.CORE, curData, GcData::getGcTimePercentage
        );

        startTime = System.currentTimeMillis();
        curData.timestamp.set(startTime);
        gcDataBuf[startIdx].setValues(startTime, 0);
        scheduledGcMonitorFuture = ScheduledExecutorUtil.safelyScheduleWithFixedDelay(
                scheduledGCInfoMonitor,
                this::scheduledMonitoring,
                TimeUnit.MILLISECONDS.toMillis(50), // to prevent / ZERO exception
                sleepIntervalMs,
                TimeUnit.MILLISECONDS
        );
    }

    @Override
    public void unbindFrom(AbstractMetricService metricService) {
        shouldRun = false;
        if (scheduledGcMonitorFuture != null) {
            scheduledGcMonitorFuture.cancel(false);
            scheduledGcMonitorFuture = null;
            logger.info("JVM GC scheduled monitor is stopped successfully.");
        }
    }

    private void scheduledMonitoring() {
        if (shouldRun) {
            calculateGCTimePercentageWithinObservedInterval();
        }
        // TODO: we can add an alertHandler here to handle the abnormal GC case.
        if (alertHandler != null &&
                curData.gcTimePercentage.get() > maxGcTimePercentage) {
            alertHandler.alert(curData.clone());
        }
    }

    private void calculateGCTimePercentageWithinObservedInterval() {
        long prevTotalGcTime = curData.totalGcTime.get();
        long totalGcTime = 0;
        long totalGcCount = 0;
        for (GarbageCollectorMXBean gcBean : ManagementFactory.getGarbageCollectorMXBeans()) {
            totalGcTime += gcBean.getCollectionTime();
            totalGcCount += gcBean.getCollectionCount();
        }
        long gcTimeWithinSleepInterval = totalGcTime - prevTotalGcTime;

        long curTime = System.currentTimeMillis();
        long gcMonitorRunTime = curTime - startTime;

        endIdx = (endIdx + 1) % bufSize;
        gcDataBuf[endIdx].setValues(curTime, gcTimeWithinSleepInterval);

        // Move startIdx forward until we reach the first buffer entry with
        // timestamp within the observation window.
        long startObsWindowTs = curTime - observationWindowMs;
        while (gcDataBuf[startIdx].ts < startObsWindowTs && startIdx != endIdx) {
            startIdx = (startIdx + 1) % bufSize;
        }

        // Calculate total GC time within observationWindowMs.
        // We should be careful about GC time that passed before the first timestamp
        // in our observation window.
        long gcTimeWithinObservationWindow = Math.min(
                gcDataBuf[startIdx].gcPause, gcDataBuf[startIdx].ts - startObsWindowTs);
        if (startIdx != endIdx) {
            for (int i = (startIdx + 1) % bufSize; i != endIdx;
                 i = (i + 1) % bufSize) {
                gcTimeWithinObservationWindow += gcDataBuf[i].gcPause;
            }
        }

        curData.update(curTime, gcMonitorRunTime, totalGcTime, totalGcCount,
                (int) (gcTimeWithinObservationWindow * 100 /
                        Math.min(observationWindowMs, gcMonitorRunTime)));
    }


    /**
     * Encapsulates data about GC pauses measured at the specific timestamp.
     */
    public static class GcData implements Cloneable {
        private final AtomicLong timestamp = new AtomicLong();
        private AtomicLong gcMonitorRunTime = new AtomicLong();
        private AtomicLong totalGcTime = new AtomicLong();
        private AtomicLong totalGcCount = new AtomicLong();
        private AtomicLong gcTimePercentage = new AtomicLong();

        /**
         * Returns the absolute timestamp when this measurement was taken.
         *
         * @return timestamp.
         */
        public long getTimestamp() {
            return timestamp.get();
        }

        /**
         * Returns the time since the start of the associated GCTimeMonitor.
         *
         * @return GcMonitorRunTime.
         */
        public long getGcMonitorRunTime() {
            return gcMonitorRunTime.get();
        }

        /**
         * Returns accumulated GC time since this JVM started.
         *
         * @return AccumulatedGcTime.
         */
        public long getAccumulatedGcTime() {
            return totalGcTime.get();
        }

        /**
         * Returns the accumulated number of GC pauses since this JVM started.
         *
         * @return AccumulatedGcCount.
         */
        public long getAccumulatedGcCount() {
            return totalGcCount.get();
        }

        /**
         * Returns the percentage (0..100) of time that the JVM spent in GC pauses
         * within the observation window of the associated GCTimeMonitor.
         *
         * @return GcTimePercentage.
         */
        public long getGcTimePercentage() {
            return gcTimePercentage.get();
        }

        private synchronized void update(long inTimestamp, long inGcMonitorRunTime,
                                         long inTotalGcTime, long inTotalGcCount, int inGcTimePercentage) {
            this.timestamp.set(inTimestamp);
            this.gcMonitorRunTime.set(inGcMonitorRunTime);
            this.totalGcTime.set(inTotalGcTime);
            this.totalGcCount.set(inTotalGcCount);
            this.gcTimePercentage.set(inGcTimePercentage);
        }

        @Override
        public synchronized GcData clone() {
            try {
                return (GcData) super.clone();
            } catch (CloneNotSupportedException e) {
                throw new RuntimeException(e);
            }
        }
    }

    private static class TsAndData {
        private long ts;      // Timestamp when this measurement was taken
        private long gcPause; // Total GC pause time within the interval between ts

        // and the timestamp of the previous measurement.
        void setValues(long inTs, long inGcPause) {
            this.ts = inTs;
            this.gcPause = inGcPause;
        }
    }

    /**
     * The user can provide an instance of a class implementing this interface
     * when initializing a GCTimeMonitor to receive alerts when GC time
     * percentage exceeds the specified threshold.
     */
    public interface GcTimeAlertHandler {
        void alert(GcData gcData);
    }

    private static class JvmGcMetricsHolder {

        private static final JvmGcMetrics INSTANCE = new JvmGcMetrics();

        private JvmGcMetricsHolder() {
            // empty constructor
        }
    }

    public static JvmGcMetrics getInstance() {
        return JvmGcMetricsHolder.INSTANCE;
    }

}
