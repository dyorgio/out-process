/** *****************************************************************************
 * Copyright 2020 See AUTHORS file.
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

import static dyorgio.runtime.out.process.OutProcessUtils.getCurrentClasspath;
import static dyorgio.runtime.out.process.OutProcessUtils.isRunning;
import static dyorgio.runtime.out.process.OutProcessUtils.readObject;
import static dyorgio.runtime.out.process.OutProcessUtils.writeObject;
import dyorgio.runtime.out.process.entrypoint.RemoteMain;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.io.NotSerializableException;
import java.io.Serializable;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.*;

/**
 * Run serializable <code>Callable</code>s and <code>Runnable</code>s in another
 * JVM.<br>
 * Only one JVM is created to execute tasks, allowing state/data sharing between
 * executions.<br>
 * Normally this class can be a singleton if classpath and jvmOptions are always
 * equals and state/data sharing is not a problem, otherwise create a new
 * instance for every cenario.<br>
 * This class acts like an <code>Executors#newSingleThreadExecutor()</code>
 * instance, so you can use it like any other ExecutorService instance.<br>
 * <br>
 * If you need to isolate states/data between executions use
 * <code>OneRunOutProcess</code> class instead.
 *
 * @author dyorgio
 * @see CallableSerializable
 * @see RunnableSerializable
 * @see Executors#newSingleThreadExecutor()
 * @see ExecutorService
 * @see OneRunOutProcess
 */
public class OutProcessExecutorService extends AbstractExecutorService {

    private static final String RUNNING_AS_OUT_PROCESS = "$RunnningAsOutProcess";

    private volatile boolean shutdown = false;
    private final ProcessBuilderFactory processBuilderFactory;
    private final PipeServer pipeServer;
    private final BlockingQueue<SerializableFutureTask> toProcessQueue = new LinkedBlockingQueue<>();

    /**
     * Creates an instance with specific java options
     *
     * @param javaOptions JVM options (ex:"-xmx32m")
     * @throws Exception If cannot create external JVM.
     */
    public OutProcessExecutorService(String... javaOptions) throws Exception {
        this(new DefaultProcessBuilderFactory(), null, javaOptions);
    }

    /**
     * Creates an instance with specific classpath and java options
     *
     * @param classpath JVM classpath, if <code>null</code> will use current
     * thread classpath.
     * @param javaOptions JVM options (ex:"-xmx32m")
     * @see OutProcessUtils#getCurrentClasspath()
     * @throws Exception If cannot create external JVM.
     */
    public OutProcessExecutorService(String classpath, String[] javaOptions) throws Exception {
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
     * @throws Exception If cannot create external JVM.
     * @throws NullPointerException If <code>processBuilderFactory</code> is
     * <code>null</code>.
     */
    public OutProcessExecutorService(ProcessBuilderFactory processBuilderFactory, String... javaOptions) throws Exception {
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
     * @throws Exception If cannot create external JVM.
     * @throws NullPointerException If <code>processBuilderFactory</code> is
     * <code>null</code>.
     */
    public OutProcessExecutorService(ProcessBuilderFactory processBuilderFactory, String classpath, String[] javaOptions) throws Exception {
        if (processBuilderFactory == null) {
            throw new NullPointerException("Process Builder Factory cannot be null.");
        }
        this.processBuilderFactory = processBuilderFactory;
        this.pipeServer = new PipeServer(classpath == null ? getCurrentClasspath() : classpath, javaOptions);
        this.pipeServer.start();
        int socketWaitCount = 0;
        RuntimeException startupException = null;
        while (startupException != null && this.pipeServer.socket == null && socketWaitCount < 300) {
            socketWaitCount++;
            startupException = checkStart();
            Thread.sleep(50);
        }
        if (startupException != null) {
            shutdownNow();
            awaitTermination(3, TimeUnit.SECONDS);
            throw startupException;
        }
    }

    private RuntimeException checkStart() throws IOException {
        RuntimeException startupException = null;
        if (!isRunning(this.pipeServer.process)) {
            int exitCode = this.pipeServer.process.exitValue();
            byte[] errorBuffer = new byte[32 * 1024];
            int readed = this.pipeServer.process.getErrorStream().available() > 0 ? this.pipeServer.process.getErrorStream().read(errorBuffer) : -1;
            if (readed < 1) {
                readed = this.pipeServer.process.getInputStream().available() > 0 ? this.pipeServer.process.getInputStream().read(errorBuffer) : -1;
                if (readed < 1) {
                    startupException = new RuntimeException("Cannot start an OutProcess: " + exitCode);
                } else {
                    startupException = new RuntimeException("Cannot start an OutProcess: " + exitCode + "\r\nOUT:\r\n" + new String(errorBuffer, 0, readed));
                }
            } else {
                startupException = new RuntimeException("Cannot start an OutProcess: " + exitCode + "\r\nERROR:\r\n" + new String(errorBuffer, 0, readed));
            }
        }
        return startupException;
    }

    private boolean checkProcess(SerializableFutureTask task, Throwable ex) {
        if (!isRunning(this.pipeServer.process) || ex instanceof SocketException) {
            int exitCode = this.pipeServer.process.exitValue();
            ExecutionException executionException = null;
            if (exitCode != 0) {
                try {
                    byte[] errorBuffer = new byte[32 * 1024];
                    int readed = this.pipeServer.process.getErrorStream().read(errorBuffer);
                    if (readed < 1) {
                        readed = this.pipeServer.process.getInputStream().read(errorBuffer);
                        if (readed > 0) {
                            executionException = new ExecutionException(new RejectedExecutionException("Error in OutProcess: " + exitCode + "\r\nOUT:\r\n" + new String(errorBuffer, 0, readed), ex));
                        }
                    } else {
                        executionException = new ExecutionException(new RejectedExecutionException("Error in OutProcess: " + exitCode + "\r\nERROR:\r\n" + new String(errorBuffer, 0, readed), ex));
                    }
                } catch (IOException ioex) {
                    // just ignore
                }
            }
            if (executionException == null) {
                executionException = new ExecutionException(new RejectedExecutionException("OutProcess Finished: " + exitCode));
            }

            shutdown();
            while (task != null) {
                task.executionException = executionException;
                task.done = true;
                synchronized (task) {
                    task.notifyAll();
                }
                task = toProcessQueue.poll();
            }
            shutdownNow();
            return false;
        }
        return true;
    }

    @Override
    public void shutdown() {
        shutdown = true;
    }

    @Override
    public List<Runnable> shutdownNow() {
        shutdown();
        pipeServer.close();
        List<Runnable> notProcessed = new ArrayList<>();
        toProcessQueue.drainTo(notProcessed);
        return notProcessed;
    }

    @Override
    public boolean isShutdown() {
        return shutdown;
    }

    @Override
    public boolean isTerminated() {
        return isShutdown() && !pipeServer.isAlive();
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        pipeServer.join(unit.toMillis(timeout));
        return pipeServer.isAlive();
    }

    @Override
    protected <T> RunnableFuture<T> newTaskFor(Callable<T> callable) {
        return (RunnableFuture<T>) new SerializableFutureTask(this, (Callable<Serializable>) callable);
    }

    @Override
    protected <T> RunnableFuture<T> newTaskFor(Runnable runnable, T value) {
        return (RunnableFuture<T>) new SerializableFutureTask(this, runnable, (Serializable) value);
    }

    @Override
    public void execute(Runnable runnable) {
        if (shutdown) {
            throw new RejectedExecutionException("Task " + runnable.toString()
                    + " rejected from "
                    + this.toString());
        }
        if (System.getProperty(RUNNING_AS_OUT_PROCESS) != null) {
            runnable.run();
        } else if (runnable instanceof SerializableFutureTask) {
            try {
                toProcessQueue.put((SerializableFutureTask) runnable);
            } catch (InterruptedException ex) {
                throw new RejectedExecutionException(ex);
            }
        } else {
            try {
                toProcessQueue.put(new SerializableFutureTask(this, runnable, (Serializable) null));
            } catch (InterruptedException ex) {
                throw new RejectedExecutionException(ex);
            }
        }
    }

    /**
     * Pipe SocketServer to comunicate with out process.
     */
    private class PipeServer extends Thread {

        private final ServerSocket server;
        private final String secret;
        private final Process process;
        private Socket socket;

        PipeServer(String classpath, String... javaOptions) throws Exception {
            super("OutProcess-PipeServer");
            this.server = new ServerSocket(0);
            this.server.setSoTimeout(250);
            Random r = new Random(System.nanoTime());
            this.secret = r.nextLong() + ":" + r.nextLong();

            List<String> commandList = new ArrayList<>();

            commandList.add(System.getProperty("java.home") + File.separatorChar + "bin" + File.separatorChar + "java");
            commandList.addAll(Arrays.asList(javaOptions));
            commandList.add("-cp");
            commandList.add(classpath);
            commandList.add(RemoteMain.class.getName());
            commandList.add(String.valueOf(server.getLocalPort()));
            commandList.add(secret);

            // adjust in processBuilderFactory and starts
            process = processBuilderFactory.create(commandList).start();
            processBuilderFactory.consume(process);
        }

        @Override
        public void run() {
            while (!shutdown && !isInterrupted()) {
                try {
                    try {
                        socket = server.accept();
                    } catch (SocketTimeoutException ste) {
                        RuntimeException startException = checkStart();
                        if (startException != null) {
                            throw startException;
                        }
                    }

                    if (socket != null) {

                        DataInputStream input = new DataInputStream(socket.getInputStream());

                        String clientSecret = input.readUTF();
                        if (clientSecret.equals(secret)) {

                            DataOutputStream output = new DataOutputStream(socket.getOutputStream());

                            SerializableFutureTask task;
                            int length[] = new int[1];

                            while (!shutdown) {
                                task = toProcessQueue.poll(50, TimeUnit.MILLISECONDS);
                                if (task != null) {
                                    try {
                                        writeObject(output, task.callable, length);

                                        if (input.readBoolean()) {
                                            task.result = readObject(input, Serializable.class);
                                        } else {
                                            Throwable throwable = readObject(input, Throwable.class);
                                            task.executionException = new ExecutionException(throwable);
                                            if (throwable instanceof OutProcessDiedException) {
                                                shutdownNow();
                                            }
                                        }
                                    } catch (EOFException e) {
                                        task.executionException = new ExecutionException(new RejectedExecutionException("Closed OutProcess socket."));
                                        shutdownNow();
                                    } catch (Throwable e) {
                                        if (checkProcess(task, e)) {
                                            task.executionException = new ExecutionException(e);
                                            shutdownNow();
                                        }
                                    } finally {
                                        task.done = true;
                                        synchronized (task) {
                                            task.notifyAll();
                                        }
                                    }
                                }
                            }
                        } else {
                            socket.close();
                        }
                    }
                } catch (Exception e) {
                    break;
                }
            }
        }

        public void close() {
            try {
                server.close();
            } catch (Exception e) {
            }

            try {
                interrupt();
                if (!isInterrupted()) {
                    interrupt();
                }
            } catch (Exception e) {
            }

            try {
                join(3000);
            } catch (Exception e) {
            }

            try {
                process.destroy();
            } catch (Exception e) {
            }
        }
    }

    private static class SerializableFutureTask implements RunnableFuture<Serializable> {

        private final OutProcessExecutorService executor;
        private final Callable<Serializable> callable;
        private boolean done = false;
        private Serializable result;
        private ExecutionException executionException;

        public SerializableFutureTask(final OutProcessExecutorService executor, final Runnable runnable, final Serializable value) {
            if (!(runnable instanceof Serializable)) {
                throw new RejectedExecutionException(new NotSerializableException());
            }
            this.executor = executor;
            this.callable = new SerializableCall(runnable, value);
        }

        public SerializableFutureTask(final OutProcessExecutorService executor, Callable<Serializable> callable) {
            if (!(callable instanceof Serializable)) {
                throw new RejectedExecutionException(new NotSerializableException());
            }
            this.executor = executor;
            this.callable = callable;
        }

        @Override
        public void run() {
            throw new UnsupportedOperationException("Cannot run a remote task locally.");
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            throw new UnsupportedOperationException("Cannot cancel a remote task.");
        }

        @Override
        public boolean isCancelled() {
            return false;
        }

        @Override
        public boolean isDone() {
            return done;
        }

        @Override
        public Serializable get() throws InterruptedException, ExecutionException {
            if (done) {
                return getResult();
            }
            while (!done) {
                if (!executor.checkProcess(this, null)) {
                    synchronized (this) {
                        wait(50);
                    }
                }
            }
            return getResult();
        }

        @Override
        public Serializable get(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
            if (done) {
                return getResult();
            }
            synchronized (this) {
                wait(unit.toMillis(timeout));
            }
            if (done) {
                return getResult();
            } else {
                throw new TimeoutException();
            }
        }

        private Serializable getResult() throws ExecutionException {
            if (executionException != null) {
                throw executionException;
            }
            return result;
        }
    }

    private static final class SerializableCall implements CallableSerializable {

        private final Runnable runnable;
        private final Serializable value;

        private SerializableCall(final Runnable runnable, final Serializable value) {
            this.runnable = runnable;
            this.value = value;
        }

        @Override
        public Serializable call() throws Exception {
            runnable.run();
            return value;
        }
    };
}
