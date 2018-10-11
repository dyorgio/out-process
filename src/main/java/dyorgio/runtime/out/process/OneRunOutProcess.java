/** *****************************************************************************
 * Copyright 2018 See AUTHORS file.
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

import static dyorgio.runtime.out.process.OutProcessUtils.RUNNING_AS_OUT_PROCESS;
import static dyorgio.runtime.out.process.OutProcessUtils.getCurrentClasspath;
import static dyorgio.runtime.out.process.OutProcessUtils.serialize;
import static dyorgio.runtime.out.process.OutProcessUtils.unserialize;
import dyorgio.runtime.out.process.entrypoint.OneRunRemoteMain;
import java.io.File;
import java.io.FileInputStream;
import java.io.RandomAccessFile;
import java.io.Serializable;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutionException;

/**
 * Run serializable <code>Callable</code>s and <code>Runnable</code>s in another
 * JVM.<br>
 * Every <code>run()</code> or <code>call()</code> creates a new JVM and destroy
 * it.<br>
 * Normally this class can be a singleton if classpath and jvmOptions are always
 * equals, otherwise create a new instance for every cenario.<br>
 * <br>
 * If you need to share states/data between executions (<code>run</code> and
 * <code>call</code>) use <code>OutProcessExecutorService</code> class instead.
 *
 * @author dyorgio
 * @see CallableSerializable
 * @see RunnableSerializable
 * @see OutProcessExecutorService
 */
public class OneRunOutProcess {

    public static int DEFAULT_IN_BUFFER_SIZE = 32 * 1024;
    public static int DEFAULT_OUT_BUFFER_SIZE = 32 * 1024 * 1024;

    private final File tmpDir;
    private final int inBufferSize;
    private final int outBufferSize;
    private final ProcessBuilderFactory processBuilderFactory;
    private final String classpath;
    private final String[] javaOptions;

    /**
     * Creates an instance with specific java options
     *
     * @param javaOptions JVM options (ex:"-xmx32m")
     */
    public OneRunOutProcess(String... javaOptions) {
        this(new DefaultProcessBuilderFactory(), null, javaOptions);
    }

    /**
     * Creates an instance with specific classpath and java options
     *
     * @param classpath JVM classpath, if <code>null</code> will use current
     * thread classpath.
     * @param javaOptions JVM options (ex:"-xmx32m")
     * @see OutProcessUtils#getCurrentClasspath()
     */
    public OneRunOutProcess(String classpath, String[] javaOptions) {
        this(new DefaultProcessBuilderFactory(), classpath, javaOptions);
    }

    /**
     * Creates an instance with specific processBuilderFactory and java options
     *
     * @param processBuilderFactory A factory to convert a
     * <code>List&lt;String&gt;</code> to <code>ProcessBuilder</code>.
     * @param javaOptions JVM options (ex:"-xmx32m")
     * @see ProcessBuilderFactory
     * @see ProcessBuilder
     */
    public OneRunOutProcess(ProcessBuilderFactory processBuilderFactory, String... javaOptions) {
        this(processBuilderFactory, null, javaOptions);
    }

    /**
     * Creates an instance with default jvm temporary dir and specific
     * processBuilderFactory, classpath and java options
     *
     * @param processBuilderFactory A factory to convert a
     * <code>List&lt;String&gt;</code> to <code>ProcessBuilder</code>.
     * @param classpath JVM classpath, if <code>null</code> will use current
     * thread classpath.
     * @param javaOptions JVM options (ex:"-xmx32m")
     * @see ProcessBuilderFactory
     * @see ProcessBuilder
     * @see OutProcessUtils#getCurrentClasspath()
     * @throws NullPointerException If <code>processBuilderFactory</code> is
     * <code>null</code>.
     */
    public OneRunOutProcess(ProcessBuilderFactory processBuilderFactory, String classpath, String[] javaOptions) {
        this(null, processBuilderFactory, classpath, javaOptions);
    }

    /**
     * Creates an instance with specific temporary dir, processBuilderFactory,
     * classpath and java options.
     *
     * @param tmpDir Temporary directory to create IPC files, if
     * <code>null</code> uses default.
     * @param processBuilderFactory A factory to convert a
     * <code>List&lt;String&gt;</code> to <code>ProcessBuilder</code>.
     * @param classpath JVM classpath, if <code>null</code> will use current
     * thread classpath.
     * @param javaOptions JVM options (ex:"-xmx32m")
     * @see ProcessBuilderFactory
     * @see ProcessBuilder
     * @see OutProcessUtils#getCurrentClasspath()
     * @throws NullPointerException If <code>processBuilderFactory</code> is
     * <code>null</code>.
     */
    public OneRunOutProcess(File tmpDir, ProcessBuilderFactory processBuilderFactory, String classpath, String[] javaOptions) {
        this(tmpDir, DEFAULT_IN_BUFFER_SIZE, DEFAULT_OUT_BUFFER_SIZE, processBuilderFactory, classpath, javaOptions);
    }

    /**
     * Creates an instance with specific temporary dir, processBuilderFactory,
     * classpath and java options.
     *
     * @param tmpDir Temporary directory to create IPC files, if
     * <code>null</code> uses default.
     * @param inBufferSize Size of input buffer.
     * @param outBufferSize Size of output buffer.
     * @param processBuilderFactory A factory to convert a
     * <code>List&lt;String&gt;</code> to <code>ProcessBuilder</code>.
     * @param classpath JVM classpath, if <code>null</code> will use current
     * thread classpath.
     * @param javaOptions JVM options (ex:"-xmx32m")
     * @see ProcessBuilderFactory
     * @see ProcessBuilder
     * @see OutProcessUtils#getCurrentClasspath()
     * @throws NullPointerException If <code>processBuilderFactory</code> is
     * <code>null</code>.
     */
    public OneRunOutProcess(File tmpDir, int inBufferSize, int outBufferSize, ProcessBuilderFactory processBuilderFactory, String classpath, String[] javaOptions) {
        this.tmpDir = tmpDir;
        this.inBufferSize = inBufferSize;
        this.outBufferSize = outBufferSize;
        if (processBuilderFactory == null) {
            throw new NullPointerException("Process Builder Factory cannot be null.");
        }
        this.processBuilderFactory = processBuilderFactory;
        this.classpath = classpath == null ? getCurrentClasspath() : classpath;
        this.javaOptions = javaOptions;
    }

    /**
     * Runs runnable in a new JVM.
     *
     * @param runnable A <code>RunnableSerializable</code> to run.
     * @return The process <code>int</code> return code.
     * @throws Exception If cannot create a new JVM.
     * @throws ExecutionException If a error occurred in execution.
     * @see RunnableSerializable
     * @serialData
     */
    public int run(RunnableSerializable runnable) throws Exception, ExecutionException {
        return call(new RunnableCallableWrapper(runnable)).getReturnCode();
    }

    /**
     * Calls callable in a new JVM.
     *
     * @param <T> Result type.
     * @param callable A <code>CallableSerializable</code> to be called.
     * @return An <code>OutProcessResult</code> object containing the result and
     * return code.
     * @throws Exception If cannot create a new JVM.
     * @throws ExecutionException If a error occurred in execution.
     * @see CallableSerializable
     * @see OutProcessResult
     * @serialData
     */
    public <T extends Serializable> OutProcessResult<T> call(CallableSerializable<T> callable) throws Exception, ExecutionException {

        // If is already out process
        if (System.getProperty(RUNNING_AS_OUT_PROCESS) != null) {
            // run here
            return new OutProcessResult(callable.call(), 0);
        }

        // Create tmp files
        File ipcFile = File.createTempFile("out-process", ".dat", tmpDir);
        ipcFile.deleteOnExit();
        try {
            int returnCode;
            try (RandomAccessFile ipcRaf = new RandomAccessFile(ipcFile, "rw")) {
                try (FileChannel ipcFC = ipcRaf.getChannel()) {
                    MappedByteBuffer ipcBuffer = ipcFC.map(FileChannel.MapMode.READ_WRITE, 0, inBufferSize + outBufferSize);
                    byte[] data = serialize(callable);
                    ipcBuffer.putInt(data.length);
                    ipcBuffer.put(data);

                    // create out process command
                    List<String> commandList = new ArrayList<>();
                    commandList.add("\"" + System.getProperty("java.home") + File.separatorChar + "bin" + File.separatorChar + "java\"");
                    commandList.addAll(Arrays.asList(javaOptions));
                    commandList.add("-cp");
                    commandList.add("\"" + classpath + "\"");
                    commandList.add(OneRunRemoteMain.class.getName());
                    commandList.add("\"" + ipcFile.getAbsolutePath() + "\"");

                    ProcessBuilder builder = processBuilderFactory.create(commandList);

                    File outputFile = null;
                    File errFile = null;

                    if (builder.redirectOutput() != ProcessBuilder.Redirect.INHERIT) {

                        commandList = new ArrayList<>(builder.command());

                        // Create tmp output, err files
                        outputFile = File.createTempFile("out-process", ".out", tmpDir);
                        outputFile.deleteOnExit();
                        errFile = File.createTempFile("out-process", ".err", tmpDir);
                        errFile.deleteOnExit();
                        
                        // Append to final command
                        commandList.add("\"" + outputFile.getAbsolutePath() + "\"");
                        commandList.add("\"" + errFile.getAbsolutePath() + "\"");
                        builder.command(commandList);
                    }

                    // adjust in processBuilderFactory and starts
                    Process process = builder.start();

                    returnCode = process.waitFor();

                    if (outputFile != null) {
                        byte[] buffer = new byte[1024];
                        int readed;
                        try (FileInputStream input = new FileInputStream(outputFile)) {
                            while ((readed = input.read(buffer)) != -1) {
                                System.out.write(buffer, 0, readed);
                            }
                        } finally {
                            outputFile.delete();
                        }
                        try (FileInputStream input = new FileInputStream(errFile)) {
                            while ((readed = input.read(buffer)) != -1) {
                                System.err.write(buffer, 0, readed);
                            }
                        } finally {
                            errFile.delete();
                        }
                    }

                    boolean error = ipcBuffer.get() == 0;

                    byte[] buffer = new byte[ipcBuffer.getInt()];
                    ipcBuffer.get(buffer);
                    Object obj = buffer.length == 0 ? null : unserialize(buffer, Object.class);

                    if (error && obj != null) {
                        throw new ExecutionException((Throwable) obj);
                    }
                    return new OutProcessResult((Serializable) obj, returnCode);
                }
            }
        } finally {
            ipcFile.delete();
        }
    }

    /**
     * Represents the result of a out process call
     *
     * @param <V> Type of result
     * @see
     * OneRunOutProcess#call(dyorgio.runtime.out.process.CallableSerializable)
     */
    public static final class OutProcessResult<V extends Serializable> {

        private final V result;
        private final int returnCode;

        private OutProcessResult(final V result, final int returnCode) {
            this.result = result;
            this.returnCode = returnCode;
        }

        public V getResult() {
            return result;
        }

        public int getReturnCode() {
            return returnCode;
        }
    }

    private static final class RunnableCallableWrapper implements CallableSerializable<Serializable> {

        private final Runnable runnable;

        private RunnableCallableWrapper(final Runnable runnable) {
            this.runnable = runnable;
        }

        @Override
        public Serializable call() throws Exception {
            runnable.run();
            return null;
        }
    }
}
