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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.NotSerializableException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.PrintStream;
import java.io.Serializable;
import java.io.UnsupportedEncodingException;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Locale;
import java.util.Scanner;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Constants and utility methods used in an out process execution.
 *
 * @author dyorgio
 */
public class OutProcessUtils {

    /**
     * System property flag to identify an out process code at runtime.
     */
    public static final String RUNNING_AS_OUT_PROCESS = "$RunnningAsOutProcess";
    public static final String SHUTDOWN_OUT_PROCESS_REQUESTED = "$ShutdownOutProcessRequested";

    private static final ThreadLocal<CachedBuffer> BUFFER_CACHE = new ThreadLocal() {
        @Override
        protected Object initialValue() {
            return new CachedBuffer();
        }
    };

    private static boolean IS_MAC, IS_WINDOWS, IS_LINUX;
    private static Pattern WINDOWS_DRIVER_LETTER_PATTERN;

    static {
        String OS = System.getProperty("os.name", "generic").toLowerCase(Locale.ENGLISH);
        if ((OS.contains("mac")) || (OS.contains("darwin"))) {
            IS_MAC = true;
        } else if (OS.contains("win")) {
            IS_WINDOWS = true;
            WINDOWS_DRIVER_LETTER_PATTERN = Pattern.compile("\\w:\\\\(.*)");
        } else if (OS.contains("nux")) {
            IS_LINUX = true;
        } else {
            throw new RuntimeException("Unsupported OS:" + OS);
        }
    }

    public static void compatibleInheritIO(String srcIdentifier, final InputStream src, final PrintStream dest) {
        Thread thread = new Thread(srcIdentifier) {
            @Override
            public void run() {
                Scanner sc = new Scanner(src);
                while (sc.hasNextLine()) {
                    dest.println(sc.nextLine());
                }
            }
        };
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * Get current Thread classpath.
     *
     * @return A string of current classpath elements splited by
     * <code>File.pathSeparatorChar</code>
     */
    public static String getCurrentClasspath() {
        StringBuilder buffer = new StringBuilder();
        String[] urls;
        if (Thread.currentThread().getContextClassLoader() instanceof URLClassLoader) {
            URL[] urlsArray = ((URLClassLoader) Thread.currentThread().getContextClassLoader()).getURLs();
            urls = new String[urlsArray.length];
            for (int i = 0; i < urlsArray.length; i++) {
                urls[i] = urlsArray[i].getPath();
            }
        } else {
            urls = System.getProperty("java.class.path").split(File.pathSeparator);
        }
        for (String urlStr : urls) {
            urlStr = urlStr.replaceFirst("jar:", "");
            if (IS_WINDOWS) {
                urlStr = urlStr.replaceFirst("file:\\/", "");
            }
            urlStr = urlStr.replaceFirst("file:", "");

            try {
                urlStr = URLDecoder.decode(urlStr, StandardCharsets.UTF_8.name());
            } catch (UnsupportedEncodingException e) {
                // UTF-8 is always available
            }
            buffer.append((IS_WINDOWS ? toUNCForm(new File(urlStr)) : new File(urlStr)).getAbsolutePath());
            buffer.append(File.pathSeparatorChar);
        }
        String classpath = buffer.toString();
        classpath = classpath.substring(0, classpath.lastIndexOf(File.pathSeparatorChar));
        return classpath;
    }

    /**
     * Generate classpath from classes.
     *
     * @param classes Classes to be added to classpath (if class is inside a
     * jar, it will be added instead), <code>null</code> elements are ignored.
     * @return A string of all classpath entries concatenation.
     */
    public static String generateClassPath(Class... classes) {
        Set<String> urls = new HashSet();
        for (Class clazz : classes) {
            if (clazz != null) {
                String url = clazz.getResource('/' + clazz.getName().replace('.', '/') + ".class").toExternalForm();
                int index = url.lastIndexOf('!');
                if (index != -1) {
                    url = url.substring(0, index);
                } else {
                    try {
                        url = new File(clazz.getProtectionDomain().getCodeSource().getLocation().toURI()).getAbsolutePath();
                    } catch (URISyntaxException ex) {
                        throw new RuntimeException(ex);
                    }
                }
                url = url.replaceFirst("jar:", "");
                if (IS_WINDOWS) {
                    url = url.replaceFirst("file:\\/", "");
                }
                url = url.replaceFirst("file:", "");

                try {
                    url = URLDecoder.decode(url, StandardCharsets.UTF_8.name());
                } catch (UnsupportedEncodingException e) {
                    // UTF-8 is always available
                }
                urls.add((IS_WINDOWS ? toUNCForm(new File(url)) : new File(url)).getAbsolutePath());
            }

        }

        StringBuilder builder = new StringBuilder();
        for (String url : urls) {
            builder.append(url).append(File.pathSeparatorChar);
        }
        if (!urls.isEmpty()) {
            builder.delete(builder.length() - 1, builder.length());
        }

        return builder.toString();
    }

    @SuppressWarnings("UseSpecificCatch")
    private static File toUNCForm(File file) {
        Matcher matcher = WINDOWS_DRIVER_LETTER_PATTERN.matcher(file.getAbsolutePath());
        if (matcher.matches()) {
            try {
                Class shellFolderClass = Class.forName("sun.awt.shell.ShellFolder");
                Object shellFolder = shellFolderClass.getDeclaredMethod("getShellFolder", File.class).invoke(null, file.toPath().getRoot().toFile());
                Object uncPath = shellFolderClass.getDeclaredMethod("getFolderColumnValue", int.class).invoke(shellFolder, 6);
                return uncPath != null && uncPath.toString().startsWith("\\\\") ? new File(uncPath + "\\" + matcher.group(1)) : file;
            } catch (Exception ex) {
                // just ignore
            }
        }
        return file;
    }

    public static void killOutProcess(String message, Throwable cause) {
        if (System.getProperty(RUNNING_AS_OUT_PROCESS) != null) {
            throw new OutProcessDiedException(message, cause);
        } else {
            throw new RuntimeException("Current JVM is not a OutProcess JVM, cannot kill it.");
        }
    }

    /**
     * Creates a new <code>ObjectInputStream</code> from
     * <code>inputStream</code> parameter, reads a <code>Callable</code> command
     * from it, executes call, and write results on <code>objOut</code>.
     * <br>
     * After executing <code>Callable.call()</code> a primitive boolean is wrote
     * in <code>objOut</code> to sinalize the execution state:
     * <br>
     * <code>true</code>: OK execution. Result is wrote on
     * <code>objOut</code><br>
     * <code>false</code>: An <code>Exception</code> occurred.
     * <code>Exception</code> is wrote on <code>objOut</code><br>
     *
     * @param input
     * @param output
     * @param length Buffer to receive count of bytes wrote to output.
     * @throws Exception
     */
    public static void readCommandExecuteAndRespond(DataInputStream input, DataOutputStream output, int[] length) throws Exception {
        try {
            // Read current command
            Callable<Serializable> callable = (Callable) readObject(input, Callable.class);

            Serializable result = callable.call();
            // Reply with result
            output.writeBoolean(true);
            writeObject(output, result, length);
        } catch (Throwable e) {
            // Reply with error
            output.writeBoolean(false);
            try {
                writeObject(output, e, length);
            } catch (NotSerializableException ex) {
                // Reply with safe error (without not-serializable objects).
                writeObject(output, new RuntimeException(ex.getMessage()), length);
            }
        }
    }

    /**
     * Read object from stream.
     *
     * @param <T> Object type.
     * @param input Source InputStream.
     * @param clazz Object class.
     * @return Object instance.
     * @throws IOException
     */
    public static <T> T readObject(DataInputStream input, Class<T> clazz) throws IOException, ClassNotFoundException {
        int originalLen, len = originalLen = input.readInt();
        byte[] buffer;
        CachedBuffer cachedBuffer = BUFFER_CACHE.get();
        byte[] lastBuffer = cachedBuffer.buffer;
        if (lastBuffer.length < len) {
            buffer = cachedBuffer.allocate(len);
        } else {
            buffer = lastBuffer;
            cachedBuffer.incrementUsage(len);

            // optimize (don't call everytime). 
            if (cachedBuffer.cacheCount > 10) {
                // Verify if average is high (+50%), then mark buffer to reduce (adaptative baby ;)).
                if (cachedBuffer.average() * 1.5f > len) {
                    // Reduce buffer in 5%
                    cachedBuffer.reduce(5f, len);
                }
            }
        }

        int readed;
        while (len > 0) {
            readed = input.read(buffer, originalLen - len, len);
            if (readed != -1) {
                len -= readed;
            } else {
                break;
            }
        }
        return (T) unserialize(buffer, clazz);
    }

    /**
     * Write object to stream.
     *
     * @param output Destiny OutputStream.
     * @param obj Object instance.
     * @param length Buffer to receive count of bytes wrote to output.
     * @throws IOException
     */
    public static void writeObject(DataOutputStream output, Object obj, int[] length) throws IOException {
        byte[] data = serialize(obj, length);
        output.writeInt(length[0]);
        output.write(data, 0, length[0]);
        output.flush();
    }

    /**
     * Converts an object to byte array.
     *
     * @param obj Object to be serialized.
     * @param length Buffer to receive count of bytes wrote to output.
     * @return Binary representation of object parameter.
     */
    public static byte[] serialize(Object obj, int[] length) throws IOException {
        ByteArrayOutputStream outputBuffer = new ByteArrayOutputStream();
        ObjectOutputStream output = new ObjectOutputStream(outputBuffer);
        try {
            output.writeObject(obj);
            output.flush();
            byte[] data = outputBuffer.toByteArray();
            length[0] = data.length;
            return data;
        } finally {
            outputBuffer.close();
            output.close();
        }
    }

    /**
     * Converts a byte array to object.
     *
     * @param <T>
     * @param data Byte array to be unserialized.
     * @param clazz
     * @return A java object.
     */
    public static <T> T unserialize(byte[] data, Class<T> clazz) throws IOException, ClassNotFoundException {
        ObjectInputStream input = new ObjectInputStream(new ByteArrayInputStream(data));
        try {
            return (T) input.readObject();
        } finally {
            input.close();
        }
    }

    /**
     * A compatible Process alive check
     * @param process
     * @return 
     */
    public static boolean isRunning(Process process) {
        try {
            process.exitValue();
            return false;
        } catch (IllegalThreadStateException e) {
            return true;
        }
    }

    private static class CachedBuffer {

        private byte[] buffer = new byte[32];
        private float cacheCount = 0;
        private float count = 0;
        private float sum = 0f;

        private byte[] allocate(int len) {
            // DEBUG
            // System.out.println("Allocating by 15% " + buffer.length + " -> " + (int) (len * 1.15f));
            buffer = new byte[(int) (len * 1.15f)];
            count = 1f;
            cacheCount = 1;
            sum = len;
            // DEBUG
            // System.out.println(Thread.currentThread().getName() + ": " + new Date() + " - Requested: " + len + ",  allocated:" + buffer.length);
            return buffer;
        }

        private void reduce(float percent, int minSize) {
            cacheCount = 1;
            int newSize = (int) (buffer.length * (1f - (percent / 100f)));
            if (newSize <= minSize) {
                if (buffer.length != minSize) {
                    newSize = minSize;
                } else {
                    return;
                }
            }
            byte[] buffertmp = new byte[newSize];
            System.arraycopy(buffer, 0, buffertmp, 0, buffertmp.length);
            // DEBUG
            // System.out.println("reduced by " + percent + "% " + buffer.length + " -> " + buffertmp.length);
            buffer = buffertmp;
        }

        private float average() {
            return sum / count;
        }

        private void incrementUsage(int len) {
            sum += len;
            count++;
            cacheCount++;
        }
    }
}
