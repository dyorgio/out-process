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

import static dyorgio.runtime.out.process.OutProcessUtils.readCommandExecuteAndRespond;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A runnable that 'runs' remote code received from a socket.
 *
 * @author dyorgio
 */
public class RemoteRunnable implements Runnable {

    private final Socket socket;
    private final AtomicReference<Throwable> throwableReference;

    public RemoteRunnable(final Socket socket, final AtomicReference<Throwable> throwableReference) {
        this.socket = socket;
        this.throwableReference = throwableReference;
    }

    @Override
    public void run() {
        try {
            // In/Out streams.
            DataInputStream input = new DataInputStream(socket.getInputStream());
            DataOutputStream output = new DataOutputStream(socket.getOutputStream());
            // Read input stream while is connected
            while (socket.isConnected() && !socket.isClosed() && !socket.isInputShutdown()) {
                readCommandExecuteAndRespond(input, output);
            }
        } catch (Throwable ex) {
            // Feed error reference
            throwableReference.set(ex);
        }
    }
}
