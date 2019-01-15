/** *****************************************************************************
 * Copyright 2019 See AUTHORS file.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 ***************************************************************************** */
package dyorgio.runtime.out.process;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.Ignore;
import org.junit.Test;

/**
 *
 * @author dyorgio
 */
public class OutProcessPerfTest implements Serializable {

    /**
     * Test if a system variable is saved between executions, same JVM (Valid
     * for <code>OutProcessExecutorService</code>).
     *
     * @see OutProcessExecutorService
     */
    @Ignore("Local PERF Test.")
    @Test(timeout = 30000)
    public void testSharedOutProcess() throws Throwable {
        OutProcessExecutorService sharedProcess = null;
        try {
            sharedProcess = new OutProcessExecutorService("-Xmx32m");
            int callsCount = 10000;
            List<Future<String>> results = new ArrayList(callsCount);
            long start = System.currentTimeMillis();
            for (int i = 0; i < callsCount; i++) {
                final int iF = i;
                results.add(sharedProcess.submit((CallableSerializable<String>) () -> System.setProperty("$testSharedRun", "EXECUTED:" + System.currentTimeMillis())));
            }

            for (Future<String> result : results) {
                result.get();
            }

            long end = System.currentTimeMillis();

            System.out.println("Total Calls: " + callsCount);
            System.out.println("Total Time: " + (end - start));

        } finally {
            if (sharedProcess != null) {
                sharedProcess.shutdown();
                sharedProcess.awaitTermination(3, TimeUnit.SECONDS);
                sharedProcess.shutdownNow();
            }
        }
    }
}
