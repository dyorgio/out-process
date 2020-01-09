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

import java.io.Serializable;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.List;

/**
 * A safe serializable exception with cause of out process shutdown request.
 *
 * @author dyorgio
 */
public class OutProcessDiedException extends RuntimeException implements Serializable {

    private final CauseInfo[] causeData;

    OutProcessDiedException(final String message, Throwable cause) {
        super(message);
        List<CauseInfo> causeDataList = new ArrayList();
        while (cause != null && cause != this) {
            StackTraceElement[] stackTraceOriginal = cause.getStackTrace();
            CauseStackTraceElement[] causeStackTrace = null;

            if (stackTraceOriginal != null) {
                causeStackTrace = new CauseStackTraceElement[stackTraceOriginal.length];
                StackTraceElement stackTraceElement;
                for (int i = 0; i < stackTraceOriginal.length; i++) {
                    stackTraceElement = stackTraceOriginal[i];
                    causeStackTrace[i] = new CauseStackTraceElement(//
                            stackTraceElement.getClassName(), //
                            stackTraceElement.getMethodName(), //
                            stackTraceElement.getFileName(), //
                            stackTraceElement.getLineNumber());
                }
            }
            causeDataList.add(new CauseInfo(cause.getClass().getName(), cause.getMessage(), causeStackTrace));
            cause = cause.getCause();
        }

        this.causeData = causeDataList.toArray(new CauseInfo[causeDataList.size()]);
    }

    public Throwable getOriginalCause() throws ClassNotFoundException, InstantiationException, IllegalAccessException, IllegalArgumentException, InvocationTargetException, NoSuchMethodException {
        if (causeData != null && causeData.length > 0) {
            CauseInfo causeInfo;
            Throwable lastCause = null;
            for (int i = causeData.length - 1; i >= 0; i--) {
                causeInfo = causeData[i];
                try {
                    lastCause = (Throwable) Class.forName(causeInfo.causeClass).getConstructor(Throwable.class, String.class).newInstance(lastCause, causeInfo.causeMessage);
                } catch (NoSuchMethodException nsme1) {
                    try {
                        lastCause = (Throwable) Class.forName(causeInfo.causeClass).getConstructor(String.class, Throwable.class).newInstance(causeInfo.causeMessage, lastCause);
                    } catch (NoSuchMethodException nsme2) {
                        lastCause = (Throwable) Class.forName(causeInfo.causeClass).getConstructor(String.class).newInstance(causeInfo.causeMessage);
                    }
                }
                CauseStackTraceElement[] causeStackTrace = causeInfo.causeStackTrace;
                StackTraceElement[] stackTraceOriginal = null;

                if (causeStackTrace != null) {
                    stackTraceOriginal = new StackTraceElement[causeStackTrace.length];
                    CauseStackTraceElement stackTraceElement;
                    for (int j = 0; j < causeStackTrace.length; j++) {
                        stackTraceElement = causeStackTrace[j];
                        stackTraceOriginal[j] = new StackTraceElement(//
                                stackTraceElement.declaringClass, //
                                stackTraceElement.methodName, //
                                stackTraceElement.fileName, //
                                stackTraceElement.lineNumber);
                    }
                }
                lastCause.setStackTrace(stackTraceOriginal);
            }
            return lastCause;
        } else {
            return null;
        }
    }

    private class CauseInfo implements Serializable {

        private final String causeClass;
        private final String causeMessage;
        private final CauseStackTraceElement[] causeStackTrace;

        private CauseInfo(String causeClass, String causeMessage, CauseStackTraceElement[] causeStackTrace) {
            this.causeClass = causeClass;
            this.causeMessage = causeMessage;
            this.causeStackTrace = causeStackTrace;
        }
    }

    private final class CauseStackTraceElement implements Serializable {

        private final String declaringClass;
        private final String methodName;
        private final String fileName;
        private final int lineNumber;

        public CauseStackTraceElement(String declaringClass, String methodName, String fileName, int lineNumber) {
            this.declaringClass = declaringClass;
            this.methodName = methodName;
            this.fileName = fileName;
            this.lineNumber = lineNumber;
        }
    }
}
