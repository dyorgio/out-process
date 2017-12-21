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
package dyorgio.runtime.out.process.entrypoint;

import dyorgio.runtime.out.process.OutProcessExecutorService;
import static dyorgio.runtime.out.process.OutProcessUtils.RUNNING_AS_OUT_PROCESS;
import static dyorgio.runtime.out.process.OutProcessUtils.readCommandExecuteAndRespond;
import java.io.ObjectOutputStream;
import java.net.Socket;

/**
 * The entry point of an out process created by an <code>OutProcessExecutorService</code> instance.
 * @author dyorgio
 * @see OutProcessExecutorService
 */
public class RemoteMain {

    public static void main(String[] args) throws Exception {
        // Identify as an out process execution
        System.setProperty(RUNNING_AS_OUT_PROCESS, "true");
        // Open socket with the port received as parameter
        try (Socket socket = new Socket("localhost", Integer.valueOf(args[0]))) {
            // Reply with secret
            ObjectOutputStream objOut = new ObjectOutputStream(socket.getOutputStream());
            objOut.writeUTF(args[1]);
            objOut.flush();

            // Read input stream while is connected
            while (socket.isConnected() && !socket.isClosed() && !socket.isInputShutdown()) {
                readCommandExecuteAndRespond(socket.getInputStream(), objOut);
            }
        }
    }
}
