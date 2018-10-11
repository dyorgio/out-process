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
package dyorgio.runtime.out.process.entrypoint;

import dyorgio.runtime.out.process.OneRunOutProcess;
import static dyorgio.runtime.out.process.OutProcessUtils.RUNNING_AS_OUT_PROCESS;
import static dyorgio.runtime.out.process.OutProcessUtils.serialize;
import static dyorgio.runtime.out.process.OutProcessUtils.unserialize;
import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.io.RandomAccessFile;
import java.io.Serializable;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.concurrent.Callable;

/**
 * The entry point of an out process created by an <code>OneRunOutProcess</code>
 * instance.
 *
 * @author dyorgio
 * @see OneRunOutProcess
 */
public class OneRunRemoteMain {

    public static void main(String[] args) throws Exception {
        // Identify as an out process execution
        System.setProperty(RUNNING_AS_OUT_PROCESS, "true");

        try (RandomAccessFile ipcRaf = new RandomAccessFile(new File(args[0]), "rw");
                PrintStream output = args.length == 1 ? null : new PrintStream(new FileOutputStream(args[1]), true);
                PrintStream err = args.length == 1 ? null : new PrintStream(new FileOutputStream(args[2]), true)) {
            if (output != null) {
                System.setOut(output);
                System.setErr(err);
            }
            MappedByteBuffer ipcBuffer = ipcRaf.getChannel().map(FileChannel.MapMode.READ_WRITE, 0, ipcRaf.length());
            byte[] data;
            try {
                data = new byte[ipcBuffer.getInt()];
                ipcBuffer.get(data);

                Serializable result = (Serializable) unserialize(data, Callable.class).call();
                data = serialize(result);
                ipcBuffer.put((byte) 1);
                ipcBuffer.putInt(data.length);
                ipcBuffer.put(data);
            } catch (Throwable e) {
                try {
                    data = serialize(e);
                    ipcBuffer.put((byte) 0);
                    ipcBuffer.putInt(data.length);
                    ipcBuffer.put(data);
                } catch (Throwable ex) {
                    // Reply with safe error (without not-serializable objects).
                    data = serialize(new RuntimeException(ex.getMessage()));
                    ipcBuffer.put((byte) 0);
                    ipcBuffer.putInt(data.length);
                    ipcBuffer.put(data);
                }
            }
        }
    }
}
