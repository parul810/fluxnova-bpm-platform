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

import org.finos.fluxnova.bpm.engine.delegate.ExecutionListener;
import org.finos.fluxnova.bpm.engine.impl.bpmn.parser.AbstractBpmnParseListener;
import org.finos.fluxnova.bpm.engine.impl.pvm.process.ActivityImpl;
import org.finos.fluxnova.bpm.engine.impl.pvm.process.ScopeImpl;
import org.finos.fluxnova.bpm.engine.impl.util.xml.Element;
import org.finos.fluxnova.bpm.engine.impl.util.xml.Namespace;

/**
 * Recognizes the {@code fluxnova:agentic} BPMN extension element on service
 * tasks:
 *
 * <pre>{@code
 * <bpmn:serviceTask id="..." camunda:type="external" camunda:topic="...">
 *   <bpmn:extensionElements>
 *     <fluxnova:agentic xmlns:fluxnova="http://fluxnova.finos.org/schema/1.0/agentic"
 *                        maxAutonomySeconds="60" evidenceRequired="true" />
 *   </bpmn:extensionElements>
 * </bpmn:serviceTask>
 * }</pre>
 *
 * <p>Note: this uses its own namespace, distinct from the engine's built-in
 * {@code BpmnParse.FLUXNOVA_BPMN_EXTENSIONS_NS}
 * ({@code http://fluxnova.finos.org/schema/1.0/bpmn}).</p>
 */
public class AgenticBpmnParseListener extends AbstractBpmnParseListener {

  public static final String AGENTIC_NS = "http://fluxnova.finos.org/schema/1.0/agentic";

  public static final String PROPERTYNAME_AGENTIC_MAX_AUTONOMY_SECONDS = "fluxnovaAgenticMaxAutonomySeconds";
  public static final String PROPERTYNAME_AGENTIC_EVIDENCE_REQUIRED = "fluxnovaAgenticEvidenceRequired";

  @Override
  public void parseServiceTask(Element serviceTaskElement, ScopeImpl scope, ActivityImpl activity) {
    Element agenticElement = findAgenticExtensionElement(serviceTaskElement);
    if (agenticElement == null) {
      return;
    }

    String maxAutonomySecondsText = agenticElement.attribute("maxAutonomySeconds");
    if (maxAutonomySecondsText != null && !maxAutonomySecondsText.trim().isEmpty()) {
      int maxAutonomySeconds = Integer.parseInt(maxAutonomySecondsText.trim());
      activity.setProperty(PROPERTYNAME_AGENTIC_MAX_AUTONOMY_SECONDS, maxAutonomySeconds);
    }

    String evidenceRequiredText = agenticElement.attribute("evidenceRequired");
    boolean evidenceRequired = evidenceRequiredText != null && Boolean.parseBoolean(evidenceRequiredText.trim());
    activity.setProperty(PROPERTYNAME_AGENTIC_EVIDENCE_REQUIRED, evidenceRequired);

    if (evidenceRequired) {
      activity.addBuiltInListener(ExecutionListener.EVENTNAME_END, new EvidenceRequiredExecutionListener());
    }
  }

  protected Element findAgenticExtensionElement(Element element) {
    Element extensionElements = element.element("extensionElements");
    if (extensionElements == null) {
      return null;
    }
    return extensionElements.elementNS(new Namespace(AGENTIC_NS), "agentic");
  }

}
