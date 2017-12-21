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

import static dyorgio.runtime.out.process.OutProcessUtils.getCurrentClasspath;
import dyorgio.runtime.out.process.entrypoint.RemoteMain;
import java.io.IOException;
import java.io.NotSerializableException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.RunnableFuture;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

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

    private boolean shutdown = false;
    private final PipeServer pipe;
    private final SynchronousQueue<SerializableFutureTask> toProcess = new SynchronousQueue<>();

    /**
     * Creates an instance with specific java options
     *
     * @param javaOptions JVM options (ex:"-xmx32m")
     */
    public OutProcessExecutorService(String... javaOptions) throws IOException {
        this(null, javaOptions);
    }

    /**
     * Creates an instance with specific classpath and java options
     *
     * @param classpath JVM classpath, if <code>null</code> will use current
     * thread classpath.
     * @param javaOptions JVM options (ex:"-xmx32m")
     * @see OutProcessUtils#getCurrentClasspath()
     */
    public OutProcessExecutorService(String classpath, String[] javaOptions) throws IOException {
        this.pipe = new PipeServer(classpath == null ? getCurrentClasspath() : classpath, javaOptions);
    }

    @Override
    public void shutdown() {
        shutdown = true;
    }

    @Override
    public List<Runnable> shutdownNow() {
        shutdown();
        pipe.close();
        List<Runnable> notProcessed = new ArrayList<>();
        toProcess.drainTo(notProcessed);
        return notProcessed;
    }

    @Override
    public boolean isShutdown() {
        return shutdown;
    }

    @Override
    public boolean isTerminated() {
        return isShutdown() && !pipe.isAlive();
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        pipe.join(unit.toMillis(timeout));
        return pipe.isAlive();
    }

    @Override
    protected <T> RunnableFuture<T> newTaskFor(Callable<T> callable) {
        return (RunnableFuture<T>) new SerializableFutureTask((Callable<Serializable>) callable);
    }

    @Override
    protected <T> RunnableFuture<T> newTaskFor(Runnable runnable, T value) {
        return (RunnableFuture<T>) new SerializableFutureTask(runnable, (Serializable) value);
    }

    @Override
    public void execute(Runnable runnable) {
        if (System.getProperty(RUNNING_AS_OUT_PROCESS) != null) {
            runnable.run();
        } else if (runnable instanceof SerializableFutureTask) {
            try {
                toProcess.put((SerializableFutureTask) runnable);
            } catch (InterruptedException ex) {
                throw new RejectedExecutionException(ex);
            }
        } else {
            try {
                toProcess.put(new SerializableFutureTask(runnable, (Serializable) null));
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

        PipeServer(String classpath, String... javaOptions) throws IOException {
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

            List<String> commandList = new ArrayList<>();

            commandList.add(System.getProperty("java.home") + "/bin/java");
            commandList.addAll(Arrays.asList(javaOptions));
            commandList.add("-cp");
            commandList.add(classpath);
            commandList.add(RemoteMain.class.getName());
            commandList.add(String.valueOf(server.getLocalPort()));
            commandList.add(secret);

            ProcessBuilder builder = new ProcessBuilder(commandList);
            builder.inheritIO();
            process = builder.start();
            start();
        }

        @Override
        public void run() {
            while (!isInterrupted()) {
                try {
                    Socket s = server.accept();
                    if (s != null) {

                        ObjectInputStream objStream = new ObjectInputStream(s.getInputStream());
                        String clientSecret = objStream.readUTF();
                        if (clientSecret.equals(secret)) {

                            SerializableFutureTask task;

                            while (!shutdown) {
                                task = toProcess.poll(1, TimeUnit.SECONDS);
                                if (task != null) {
                                    try {
                                        ObjectOutputStream objOut = new ObjectOutputStream(s.getOutputStream());

                                        objOut.writeObject(task.callable);
                                        objOut.flush();

                                        if (objStream.readBoolean()) {
                                            task.result = (Serializable) objStream.readObject();
                                        } else {
                                            task.executionException = new ExecutionException((Throwable) objStream.readObject());
                                        }
                                    } catch (Throwable e) {
                                        task.executionException = new ExecutionException(e);
                                    } finally {
                                        task.done = true;
                                        synchronized (task) {
                                            task.notifyAll();
                                        }
                                    }
                                }
                            }
                        } else {
                            s.close();
                        }
                    }
                } catch (Exception e) {
                }
            }
        }

        public void close() {
            try {
                interrupt();
                server.close();
            } catch (Exception e) {
            }

            try {
                join();
            } catch (Exception e) {
            }

            try {
                process.destroy();
            } catch (Exception e) {
            }
        }

    }

    private static class SerializableFutureTask implements RunnableFuture<Serializable>, Serializable {

        private final Callable<Serializable> callable;
        private boolean done = false;
        private Serializable result;
        private ExecutionException executionException;

        public SerializableFutureTask(final Runnable runnable, final Serializable value) {
            if (!(runnable instanceof Serializable)) {
                throw new RejectedExecutionException(new NotSerializableException());
            }

            this.callable = new SerializableCall(runnable, value);
        }

        public SerializableFutureTask(Callable<Serializable> callable) {
            if (!(callable instanceof Serializable)) {
                throw new RejectedExecutionException(new NotSerializableException());
            }

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
            synchronized (this) {
                wait();
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

        private final class SerializableCall implements CallableSerializable {

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
}
