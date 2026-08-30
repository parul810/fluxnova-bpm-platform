/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH
 * under one or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information regarding copyright
 * ownership. Camunda licenses this file to you under the Apache License,
 * Version 2.0; you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.finos.fluxnova.bpm.engine.plugin.agentic;

import org.finos.fluxnova.bpm.engine.ProcessEngineException;
import org.finos.fluxnova.bpm.engine.delegate.DelegateExecution;
import org.finos.fluxnova.bpm.engine.delegate.ExecutionListener;

/**
 * Execution listener installed on activities carrying
 * {@code fluxnova:agentic evidenceRequired="true"}. Fires on the activity's
 * {@code end} event and enforces that an {@code agentEvidence} variable has
 * been set before the activity is allowed to complete.
 */
public class EvidenceRequiredExecutionListener implements ExecutionListener {

  @Override
  public void notify(DelegateExecution execution) throws Exception {
    Object agentEvidence = execution.getVariable("agentEvidence");

    boolean missing = agentEvidence == null
        || (agentEvidence instanceof String && ((String) agentEvidence).trim().isEmpty());

    if (missing) {
      throw new ProcessEngineException(
          "fluxnova:agentic evidenceRequired=true but no 'agentEvidence' variable is set on activity "
              + execution.getCurrentActivityId());
    }
  }

}
