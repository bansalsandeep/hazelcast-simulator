package com.hazelcast.simulator.worker.performance;

import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.simulator.common.TestCase;
import com.hazelcast.simulator.common.TestPhase;
import com.hazelcast.simulator.protocol.connector.ServerConnector;
import com.hazelcast.simulator.protocol.connector.WorkerConnector;
import com.hazelcast.simulator.protocol.core.AddressLevel;
import com.hazelcast.simulator.protocol.core.SimulatorAddress;
import com.hazelcast.simulator.protocol.operation.PerformanceStatsOperation;
import com.hazelcast.simulator.tests.PerformanceMonitorProbeTest;
import com.hazelcast.simulator.tests.SuccessTest;
import com.hazelcast.simulator.worker.testcontainer.TestContainer;
import com.hazelcast.simulator.worker.testcontainer.TestContextImpl;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;

import static com.hazelcast.simulator.TestEnvironmentUtils.setupFakeUserDir;
import static com.hazelcast.simulator.TestEnvironmentUtils.teardownFakeUserDir;
import static com.hazelcast.simulator.protocol.core.SimulatorAddress.COORDINATOR;
import static com.hazelcast.simulator.utils.CommonUtils.joinThread;
import static com.hazelcast.simulator.utils.CommonUtils.sleepMillis;
import static com.hazelcast.simulator.utils.EmptyStatement.ignore;
import static java.util.concurrent.TimeUnit.MICROSECONDS;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

public class PerformanceMonitorTest {

    private static final String TEST_NAME = "WorkerPerformanceMonitorTest";

    private final ConcurrentMap<String, TestContainer> tests = new ConcurrentHashMap<String, TestContainer>();

    private ServerConnector serverConnector;
    private PerformanceMonitor performanceMonitor;

    @Before
    public void before() {
        setupFakeUserDir();

        SimulatorAddress workerAddress = new SimulatorAddress(AddressLevel.WORKER, 1, 1, 0);

        serverConnector = mock(ServerConnector.class);
        when(serverConnector.getAddress()).thenReturn(workerAddress);

        performanceMonitor = new PerformanceMonitor(serverConnector, tests.values(), 100, TimeUnit.MILLISECONDS);
    }

    @After
    public void after() {
        performanceMonitor.shutdown();

        teardownFakeUserDir();
    }

    @Test
    public void test_shutdownTwice() {
        performanceMonitor.start();

        performanceMonitor.shutdown();
        performanceMonitor.shutdown();
    }

    @Test(expected = IllegalThreadStateException.class)
    public void test_restartAfterStop() {
        performanceMonitor.start();

        performanceMonitor.shutdown();

        performanceMonitor.start();
    }

    @Test
    public void test_whenTestWithoutProbe_thenDoNothing() {
        addTest(new SuccessTest());

        performanceMonitor.start();

        verifyNoMoreInteractions(serverConnector);
    }

    @Test
    public void test_whenTestWithProbeWhichIsNotRunning_thenDoNothing() {
        addTest(new com.hazelcast.simulator.tests.PerformanceMonitorTest());

        performanceMonitor.start();

        verifyNoMoreInteractions(serverConnector);
    }

    @Test
    public void test_whenTestWithProbeWhichIsRunning_thenSendPerformanceStats() {
        performanceMonitor.start();
        sleepMillis(300);

        PerformanceMonitorProbeTest test = new PerformanceMonitorProbeTest();
        addTest(test);

        Thread testRunnerThread = new TestRunnerThread();
        testRunnerThread.start();

        test.recordValue(MICROSECONDS.toNanos(500));
        sleepMillis(200);

        test.recordValue(MICROSECONDS.toNanos(200));
        test.recordValue(MICROSECONDS.toNanos(300));
        sleepMillis(200);

        test.stopTest();
        joinThread(testRunnerThread);

        performanceMonitor.shutdown();
        verifyServerConnector();
    }

    @Test
    public void test_whenTestWithProbeWhichIsRunningWithDelay_thenSendPerformanceStats() {
        PerformanceMonitorProbeTest test = new PerformanceMonitorProbeTest();
        addTest(test, 200);

        performanceMonitor.start();

        Thread testRunnerThread = new TestRunnerThread();
        testRunnerThread.start();

        test.stopTest();
        joinThread(testRunnerThread);

        performanceMonitor.shutdown();
        verifyServerConnector();
    }

    @Test
    public void test_whenTestWithProbeWhichIsNotRunningAnymore_thenDoNothing() throws Exception {
        addTest(new com.hazelcast.simulator.tests.PerformanceMonitorTest());
        tests.get(TEST_NAME).invoke(TestPhase.RUN);

        performanceMonitor.start();

        verifyNoMoreInteractions(serverConnector);
    }

    private void addTest(Object test) {
        addTest(test, 0);
    }

    private void addTest(Object test, int delayMillis) {
        TestCase testCase = new TestCase(TEST_NAME);
        TestContainer testContainer = new TestContainer(new DelayTestContext(delayMillis), test, testCase);
        tests.put(TEST_NAME, testContainer);
    }

    private void verifyServerConnector() {
        verify(serverConnector, atLeastOnce()).submit(eq(COORDINATOR), any(PerformanceStatsOperation.class));
        verifyNoMoreInteractions(serverConnector);
    }

    private static class DelayTestContext extends TestContextImpl {

        private final int delayMillis;

        DelayTestContext(int delayMillis) {
            super(null, TEST_NAME, "localhost", mock(WorkerConnector.class));
            this.delayMillis = delayMillis;
        }

        @Override
        public HazelcastInstance getTargetInstance() {
            return null;
        }

        @Override
        public String getTestId() {
            sleepMillis(delayMillis);
            return TEST_NAME;
        }

        @Override
        public String getPublicIpAddress() {
            return "127.0.0.1";
        }

        @Override
        public boolean isStopped() {
            return true;
        }

        @Override
        public void stop() {
        }
    }

    private class TestRunnerThread extends Thread {
        @Override
        public void run() {
            try {
                tests.get(TEST_NAME).invoke(TestPhase.RUN);
            } catch (Exception e) {
                ignore(e);
            }
        }
    }
}
