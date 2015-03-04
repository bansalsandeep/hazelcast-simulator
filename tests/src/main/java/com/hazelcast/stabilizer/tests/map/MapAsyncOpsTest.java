package com.hazelcast.stabilizer.tests.map;

import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.core.IList;
import com.hazelcast.core.IMap;
import com.hazelcast.logging.ILogger;
import com.hazelcast.logging.Logger;
import com.hazelcast.stabilizer.test.TestContext;
import com.hazelcast.stabilizer.test.TestRunner;
import com.hazelcast.stabilizer.test.annotations.RunWithWorker;
import com.hazelcast.stabilizer.test.annotations.Setup;
import com.hazelcast.stabilizer.test.annotations.Verify;
import com.hazelcast.stabilizer.tests.map.helpers.MapOperationCounter;
import com.hazelcast.stabilizer.worker.selector.OperationSelectorBuilder;
import com.hazelcast.stabilizer.worker.tasks.AbstractWorkerTask;

import java.util.concurrent.TimeUnit;

public class MapAsyncOpsTest {

    private enum Operation {
        PUT_ASYNC,
        PUT_ASYNC_TTL,
        GET_ASYNC,
        REMOVE_ASYNC,
        DESTROY
    }

    private final static ILogger log = Logger.getLogger(MapAsyncOpsTest.class);

    // properties
    public String basename = MapAsyncOpsTest.class.getSimpleName();
    public int threadCount = 3;
    public int keyCount = 10;
    public int maxTTLExpirySeconds = 3;

    public double putAsyncProb = 0.2;
    public double putAsyncTTLProb = 0.2;
    public double getAsyncProb = 0.2;
    public double removeAsyncProb = 0.2;
    public double destroyProb = 0.2;

    private final OperationSelectorBuilder<Operation> operationSelectorBuilder = new OperationSelectorBuilder<Operation>();
    private final MapOperationCounter count = new MapOperationCounter();

    private IMap<Integer, Object> map;
    private IList<MapOperationCounter> results;

    @Setup
    public void setup(TestContext testContext) throws Exception {
        HazelcastInstance targetInstance = testContext.getTargetInstance();
        map = targetInstance.getMap(basename);
        results = targetInstance.getList(basename + "report");

        operationSelectorBuilder.addOperation(Operation.PUT_ASYNC, putAsyncProb)
                                .addOperation(Operation.PUT_ASYNC_TTL, putAsyncTTLProb)
                                .addOperation(Operation.GET_ASYNC, getAsyncProb)
                                .addOperation(Operation.REMOVE_ASYNC, removeAsyncProb)
                                .addOperation(Operation.DESTROY, destroyProb);
    }

    @Verify(global = true)
    public void globalVerify() throws Exception {
        MapOperationCounter totalMapOperationsCount = new MapOperationCounter();
        for (MapOperationCounter mapOperationsCount : results) {
            totalMapOperationsCount.add(mapOperationsCount);
        }
        log.info(basename + ": " + totalMapOperationsCount + " total of " + results.size());
    }

    @Verify(global = false)
    public void verify() throws Exception {
        Thread.sleep(maxTTLExpirySeconds * 2);

        log.info(basename + ": map size  =" + map.size());
    }

    @RunWithWorker
    public AbstractWorkerTask<Operation> createWorker() {
        return new WorkerTask();
    }

    private class WorkerTask extends AbstractWorkerTask<Operation> {
        public WorkerTask() {
            super(operationSelectorBuilder);
        }

        @Override
        protected void timeStep(Operation operation) {
            int key = randomInt(keyCount);
            switch (operation) {
                case PUT_ASYNC:
                    Object value = randomInt();
                    map.putAsync(key, value);
                    count.putAsyncCount.incrementAndGet();
                    break;
                case PUT_ASYNC_TTL:
                    value = randomInt();
                    int delay = 1 + randomInt(maxTTLExpirySeconds);
                    map.putAsync(key, value, delay, TimeUnit.SECONDS);
                    count.putAsyncTTLCount.incrementAndGet();
                    break;
                case GET_ASYNC:
                    map.getAsync(key);
                    count.getAsyncCount.incrementAndGet();
                    break;
                case REMOVE_ASYNC:
                    map.removeAsync(key);
                    count.removeAsyncCount.incrementAndGet();
                    break;
                case DESTROY:
                    map.destroy();
                    count.destroyCount.incrementAndGet();
                    break;
                default:
                    throw new UnsupportedOperationException();
            }
        }

        @Override
        protected void afterRun() {
            results.add(count);
        }
    }

    public static void main(String[] args) throws Throwable {
        new TestRunner<MapAsyncOpsTest>(new MapAsyncOpsTest()).run();
    }
}