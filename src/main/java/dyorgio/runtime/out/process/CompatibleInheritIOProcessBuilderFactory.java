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

import static dyorgio.runtime.out.process.OutProcessUtils.compatibleInheritIO;
import java.util.List;

/**
 * Complatible implementation, just calls <code>ProcessBuilder</code>
 * constructor, <b>don't use</b> <code>inheritIO()</code>. Create auxiliar
 * threads to transfer System.out and System.err from child process to this JVM
 * process.
 *
 * @author dyorgio
 * @see ProcessBuilderFactory
 * @see ProcessBuilder#ProcessBuilder(java.util.List)
 * @see ProcessBuilder#inheritIO()
 */
public class CompatibleInheritIOProcessBuilderFactory implements ProcessBuilderFactory {

    @Override
    public ProcessBuilder create(List<String> commands) throws Exception {
        return new ProcessBuilder(commands);
    }

    @Override
    public void consume(Process startedProcess) {
        String processThreadName = generateProcessThreadName(startedProcess);
        if (processThreadName == null || processThreadName.trim().isEmpty()) {
            processThreadName = "OutProcess";
        }
        compatibleInheritIO(processThreadName + "-System.out", startedProcess.getInputStream(), System.out);
        compatibleInheritIO(processThreadName + "-System.err", startedProcess.getErrorStream(), System.err);
    }

    protected String generateProcessThreadName(Process startedProcess) {
        return null;
    }
}
