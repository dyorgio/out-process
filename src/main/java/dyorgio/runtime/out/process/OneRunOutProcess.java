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

import static dyorgio.runtime.out.process.OutProcessUtils.RUNNING_AS_OUT_PROCESS;
import static dyorgio.runtime.out.process.OutProcessUtils.getCurrentClasspath;
import dyorgio.runtime.out.process.entrypoint.OneRunRemoteMain;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutionException;

/**
 * Run serializable <code>Callable</code>s and <code>Runnable</code>s in another
 * JVM.<br>
 * Every <code>run()</code> or <code>call()</code> creates a new JVM and destroy
 * it.<br>
 * Normally this class can be a singleton if classpath and jvmOptions are always
 * equals, otherwise create a new instance for every cenario.<br>
 * <br>
 * If you need to share states/data between executions (<code>run</code> an
 * <code>call</code>) use <code>OutProcessExecutorService</code> class instead.
 *
 * @author dyorgio
 * @see CallableSerializable
 * @see RunnableSerializable
 * @see OutProcessExecutorService
 */
public class OneRunOutProcess {

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
     * Creates an instance with specific processBuilderFactory, classpath and
     * java options
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
        if (processBuilderFactory == null) {
            throw new NullPointerException("Process Builder Factory cannot be null.");
        }
        this.processBuilderFactory = processBuilderFactory;
        this.classpath = classpath == null ? getCurrentClasspath() : classpath;
        this.javaOptions = javaOptions;
    }

    /**
     * Runs runnable in a new JVM.
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
     * @param <T> Result type.
     * @param callable  A <code>CallableSerializable</code> to be called.
     * @return An <code>OutProcessResult</code> object containing the result and return code.
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
        
        // Create a tmp server
        PipeServer pipeServer = new PipeServer(callable);

        // create out process command
        List<String> commandList = new ArrayList<>();
        commandList.add(System.getProperty("java.home") + "/bin/java");
        commandList.addAll(Arrays.asList(javaOptions));
        commandList.add("-cp");
        commandList.add(classpath);
        commandList.add(OneRunRemoteMain.class.getName());
        commandList.add(String.valueOf(pipeServer.server.getLocalPort()));
        commandList.add(pipeServer.secret);

        // adjust in processBuilderFactory and starts
        Process process = processBuilderFactory.create(commandList)
                .inheritIO().start();

        int returnCode;
        try {
            returnCode = process.waitFor();
        } finally {
            pipeServer.close();
        }

        if (pipeServer.error != null) {
            throw new ExecutionException(pipeServer.error);
        } else {
            return new OutProcessResult(pipeServer.result, returnCode);
        }
    }

    /**
     * Pipe SocketServer to comunicate with out process.
     */
    private static class PipeServer {

        private final ServerSocket server;
        private final String secret;
        private final Thread acceptAndRead;
        private Throwable error;
        private Serializable result;

        public PipeServer(final Serializable command) {

            Random r = new Random(System.currentTimeMillis());
            ServerSocket tmpServer = null;
            while (true) {
                try {
                    tmpServer = new ServerSocket(1025 + r.nextInt(65535 - 1024));
                    break;
                } catch (Exception e) {

                }
            }
            this.server = tmpServer;
            this.secret = r.nextLong() + ":" + r.nextLong() + ":" + r.nextLong() + ":" + r.nextLong();

            acceptAndRead = new Thread() {
                @Override
                public void run() {
                    while (!isInterrupted()) {
                        try {
                            Socket s = server.accept();
                            if (s != null) {

                                ObjectInputStream objStream = new ObjectInputStream(s.getInputStream());
                                String clientSecret = objStream.readUTF();
                                if (clientSecret.equals(secret)) {

                                    ObjectOutputStream objOut = new ObjectOutputStream(s.getOutputStream());

                                    objOut.writeObject(command);
                                    objOut.flush();

                                    if (objStream.readBoolean()) {
                                        result = (Serializable) objStream.readObject();
                                    } else {
                                        error = (Throwable) objStream.readObject();
                                    }
                                } else {
                                    s.close();
                                }
                            }
                        } catch (Exception e) {
                        }
                    }
                }
            };
            acceptAndRead.start();
        }

        public void close() {
            try {
                acceptAndRead.interrupt();
                server.close();
            } catch (Exception e) {
            }

            try {
                acceptAndRead.join();
            } catch (Exception e) {
            }
        }
    }

    /**
     * Represents the result of a out process call
     * @param <V> Type of result
     * @see OneRunOutProcess#call(dyorgio.runtime.out.process.CallableSerializable) 
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

    /**
     * Create a new ProcessBuilder from a list os commands.
     */
    public static interface ProcessBuilderFactory {

        ProcessBuilder create(List<String> commands) throws Exception;
    }

    private static final class DefaultProcessBuilderFactory implements ProcessBuilderFactory {

        @Override
        public ProcessBuilder create(List<String> commands) throws Exception {
            return new ProcessBuilder(commands);
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
