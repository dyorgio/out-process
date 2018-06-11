/** *****************************************************************************
 * Copyright 2017 See AUTHORS file.
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

import java.io.File;
import java.io.Serializable;
import java.lang.management.ManagementFactory;
import java.lang.management.RuntimeMXBean;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.Assert;
import org.junit.Test;

/**
 * Library unit tests.
 *
 * @author dyorgio
 */
public class OutProcessTest implements Serializable {

    /**
     * Test if a system variable is saved between executions, always a new JVM
     * (Not valid for <code>OneRunOutProcess</code>).
     *
     * @see OneRunOutProcess
     */
    @Test
    public void testOneRun() throws Throwable {
        OneRunOutProcess oneRun = new OneRunOutProcess("-Xmx32m");
        oneRun.run(() -> System.setProperty("$testOneRun", "EXECUTED"));
        String value = oneRun.call(() -> System.getProperty("$testOneRun")).getResult();
        Assert.assertNull("Expected a null value.", value);
    }

    /**
     * Test if a system variable is saved between executions, always a new JVM
     * (Not valid for <code>OneRunOutProcess</code>).
     *
     * @see OneRunOutProcess
     */
    @Test(expected = ExecutionException.class)
    public void testOneRunThrows() throws Throwable {
        OneRunOutProcess oneRun = new OneRunOutProcess("-Xmx32m");
        oneRun.run(() -> {
            throw new RuntimeException("Error");
        });
    }

    /**
     * Test if a system variable is saved between executions, same JVM (Valid
     * for <code>OutProcessExecutorService</code>).
     *
     * @see OutProcessExecutorService
     */
    @Test
    public void testSharedOutProcess() throws Throwable {
        OutProcessExecutorService sharedProcess = null;
        try {
            sharedProcess = new OutProcessExecutorService("-Xmx32m");
            String value = sharedProcess.submit(new CallableSerializable<String>() {
                @Override
                public String call() {
                    return System.setProperty("$testSharedRun", "EXECUTED");
                }
            }).get();
            Assert.assertNull("Expected a null value.", value);

            value = sharedProcess.submit(new CallableSerializable<String>() {
                @Override
                public String call() {
                    return System.getProperty("$testSharedRun");
                }
            }).get();

            Assert.assertEquals("Expected equals values.", "EXECUTED", value);
        } finally {
            if (sharedProcess != null) {
                sharedProcess.shutdown();
                sharedProcess.awaitTermination(3, TimeUnit.SECONDS);
                sharedProcess.shutdownNow();
            }
        }
    }

    /**
     * Test if current PID (Test JVM) is different of out process JVM PID.
     */
    @Test
    public void testOutProcessDifferentPID() throws Throwable {
        int myPID = getProcessPID();
        int outProcessPID = callInOutProcess(() -> getProcessPID());
        Assert.assertNotEquals("Expected a different PID.", outProcessPID, myPID);
    }

    /**
     * Test if out of memory is correctly handled by out-process library.
     */
    @Test(expected = OutOfMemoryError.class)
    public void testOutProcessOutOfMemory() throws Throwable {
        runInOutProcess(() -> {
            // just create some random data
            List<byte[]> dummy = new ArrayList<>(100000);
            Random r = new Random();
            while (true) {
                byte[] data = new byte[1024];
                r.nextBytes(data);
                dummy.add(data);
            }
        });
    }

    /**
     * Test if JVM crash is correctly handled by out-process library.
     */
    @Test
    public void testJVMCrash() throws Throwable {
        OutProcessExecutorService sharedProcess = null;
        Integer jvmPID = null;
        try {
            sharedProcess = new OutProcessExecutorService((List<String> commands) -> {
                ProcessBuilder processBuilder = new ProcessBuilder(commands);
                processBuilder.directory(new File("./target"));
                return processBuilder;
            }, "-Xmx32m");
            jvmPID = sharedProcess.submit(new CallableSerializable<Integer>() {
                @Override
                public Integer call() {
                    try {
                        return getProcessPID();
                    } catch (Exception ex) {
                        throw new RuntimeException(ex);
                    }
                }
            }).get();

            sharedProcess.submit(new RunnableSerializable() {
                @Override
                public void run() {
                    try {
                        Class unsafeClass = Class.forName("sun.misc.Unsafe");
                        Field f = unsafeClass.getDeclaredField("theUnsafe");
                        f.setAccessible(true);
                        Object unsafe = f.get(null);
                        Method m = unsafeClass.getDeclaredMethod("putAddress", long.class, long.class);
                        m.setAccessible(true);
                        m.invoke(unsafe, 0, 0);
                    } catch (Exception ex) {
                        throw new RuntimeException(ex);
                    }
                }
            }).get();
        } catch (ExecutionException ex) {
            Assert.assertTrue("Error file not exists.", new File("./target", "hs_err_pid" + jvmPID + ".log").exists());
        } finally {
            if (sharedProcess != null) {
                sharedProcess.shutdown();
                sharedProcess.awaitTermination(3, TimeUnit.SECONDS);
                sharedProcess.shutdownNow();
            }
        }
    }

    private static void runInOutProcess(RunnableSerializable runnable) throws Throwable {
        callInOutProcess(() -> {
            runnable.run();
            return null;
        });
    }

    private static <V extends Serializable> V callInOutProcess(CallableSerializable<V> callable) throws Throwable {
        OutProcessExecutorService ops = null;
        try {
            ops = new OutProcessExecutorService("-Xmx32m");
            Future result = ops.submit(callable);
            return (V) result.get();
        } catch (ExecutionException r) {
            throw r.getCause();
        } finally {
            if (ops != null) {
                ops.shutdown();
                ops.awaitTermination(3, TimeUnit.SECONDS);
                ops.shutdownNow();
            }
        }
    }

    private static int getProcessPID() throws Exception {
        RuntimeMXBean runtime = ManagementFactory.getRuntimeMXBean();
        Field jvm = runtime.getClass().getDeclaredField("jvm");
        jvm.setAccessible(true);
        Object mgmtObj = jvm.get(runtime);
        Method pid_method = mgmtObj.getClass().getDeclaredMethod("getProcessId");
        pid_method.setAccessible(true);

        return (Integer) pid_method.invoke(mgmtObj);
    }
}
