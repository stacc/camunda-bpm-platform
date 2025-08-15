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
package org.camunda.bpm.engine.impl.bpmn.helper;

import org.camunda.bpm.engine.ProcessEngineException;
import org.camunda.bpm.engine.impl.ProcessEngineLogger;
import org.camunda.bpm.engine.impl.bpmn.behavior.BoundaryEventActivityBehavior;
import org.camunda.bpm.engine.impl.bpmn.behavior.BpmnBehaviorLogger;
import org.camunda.bpm.engine.impl.bpmn.parser.EscalationEventDefinition;
import org.camunda.bpm.engine.impl.pvm.PvmActivity;
import org.camunda.bpm.engine.impl.pvm.PvmScope;
import org.camunda.bpm.engine.impl.pvm.delegate.ActivityExecution;
import org.camunda.bpm.engine.impl.pvm.process.ActivityImpl;
import org.camunda.bpm.engine.impl.tree.ActivityExecutionHierarchyWalker;
import org.camunda.bpm.engine.impl.tree.ActivityExecutionMappingCollector;
import org.camunda.bpm.engine.impl.tree.ActivityExecutionTuple;
import org.camunda.bpm.engine.impl.tree.OutputVariablesPropagator;
import org.camunda.bpm.engine.impl.tree.ReferenceWalker;
import org.camunda.bpm.engine.variable.value.TypedValue;

import java.util.ArrayList;

/**
 * Helper class handling the propagation of escalation.
 */
public class EscalationHandler {

  private final static BpmnBehaviorLogger LOG = ProcessEngineLogger.BPMN_BEHAVIOR_LOGGER;

  public final static String ESCALATION_DATA_VARIABLE = "__stacc_escalationData";

  public static void propagateEscalation(ActivityExecution execution, String escalationCode, TypedValue escalationData) {
    EscalationEventDefinition escalationEventDefinition = executeEscalation(execution, escalationCode, escalationData);

    if (escalationEventDefinition == null ) {
      throw LOG.missingBoundaryCatchEventEscalation(execution.getActivity().getId(), escalationCode);
    }
  }

  /**
   * Walks through the activity execution hierarchy, fetches and executes matching escalation catch event
   * @return the escalation event definition if found matching escalation catch event
   */
  public static EscalationEventDefinition executeEscalation(ActivityExecution execution,
      String escalationCode, TypedValue escalationData) {
    final PvmActivity currentActivity = execution.getActivity();

    final EscalationEventDefinitionFinder escalationEventDefinitionFinder = new EscalationEventDefinitionFinder(escalationCode, currentActivity);
    ActivityExecutionMappingCollector activityExecutionMappingCollector = new ActivityExecutionMappingCollector(execution);

    ActivityExecutionHierarchyWalker walker = new ActivityExecutionHierarchyWalker(execution);
    walker.addScopePreVisitor(escalationEventDefinitionFinder);
    walker.addExecutionPreVisitor(activityExecutionMappingCollector);
    walker.addExecutionPreVisitor(new OutputVariablesPropagator());

    walker.walkUntil(new ReferenceWalker.WalkCondition<ActivityExecutionTuple>() {

      @Override
      public boolean isFulfilled(ActivityExecutionTuple element) {
        return escalationEventDefinitionFinder.getEscalationEventDefinition() != null || element == null;
      }
    });

    EscalationEventDefinition escalationEventDefinition = escalationEventDefinitionFinder.getEscalationEventDefinition();
    if (escalationEventDefinition != null) {
      executeEscalationHandler(escalationEventDefinition, activityExecutionMappingCollector, escalationCode, escalationData);
    }
    return escalationEventDefinition;
  }


  protected static void executeEscalationHandler(EscalationEventDefinition escalationEventDefinition, ActivityExecutionMappingCollector activityExecutionMappingCollector, String escalationCode, TypedValue escalationData) {

    PvmActivity escalationHandler = escalationEventDefinition.getEscalationHandler();
    PvmScope escalationScope = getScopeForEscalation(escalationEventDefinition);
    ActivityExecution escalationExecution = activityExecutionMappingCollector.getExecutionForScope(escalationScope);

    if (escalationEventDefinition.getEscalationCodeVariable() != null) {
      escalationExecution.setVariable(escalationEventDefinition.getEscalationCodeVariable(), escalationCode);
    }

    // Activity ID used as postfix for the escalation data variable
    // Event subprocesses require special handling to ensure the correct activity ID is used
    var activityId = escalationHandler.getId();
    var initialActivity = escalationHandler.getProperty("initial");
    if (initialActivity != null) {
      activityId = ((ActivityImpl) initialActivity).getActivityId();
    }

    Object escalationDataValue = escalationData.getValue();
    String escalationDataString = (String) (escalationDataValue != null ? escalationDataValue : "");

    // Extract the data embedded in the escalation event and set it as a variable
    // to make it available in the surrounding execution for an execution listener.
    //
    // For interrupting boundary events, we need special handling to ensure the data is not lost
    if (escalationEventDefinition.isCancelActivity() &&
        escalationHandler.getActivityBehavior() instanceof BoundaryEventActivityBehavior) {

      // Find an execution that won't be destroyed during interruption
      PvmScope flowScope = escalationHandler.getFlowScope();
      ActivityExecution flowScopeExecution = activityExecutionMappingCollector.getExecutionForScope(flowScope);
      if (flowScopeExecution == null) {
        throw new ProcessEngineException("No flow scope execution found for boundary event " + escalationHandler.getId()
            + ". Cannot properly propagate escalation data for interrupting boundary event.");
      }

      TypedValue value = flowScopeExecution.getVariableLocalTyped(ESCALATION_DATA_VARIABLE + "_" + activityId);
      ArrayList<String> escalationDataList = null;
      if (value == null) {
        escalationDataList = new ArrayList<>();
      } else {
        escalationDataList = (ArrayList<String>) value.getValue();
      }

      escalationDataList.add(escalationDataString);
      flowScopeExecution.setVariableLocal(ESCALATION_DATA_VARIABLE + "_" + activityId, escalationDataList);
    } else {
      TypedValue value = escalationExecution.getVariableLocalTyped(ESCALATION_DATA_VARIABLE + "_" + activityId);
      ArrayList<String> escalationDataList = null;
      if (value == null) {
        escalationDataList = new ArrayList<>();
      } else {
        escalationDataList = (ArrayList<String>) value.getValue();
      }


      escalationDataList.add(escalationDataString);
      escalationExecution.setVariableLocal(ESCALATION_DATA_VARIABLE + "_" + activityId, escalationDataList);
    }

    escalationExecution.executeActivity(escalationHandler);
  }

  protected static PvmScope getScopeForEscalation(EscalationEventDefinition escalationEventDefinition) {
    PvmActivity escalationHandler = escalationEventDefinition.getEscalationHandler();
    if (escalationEventDefinition.isCancelActivity()) {
      return escalationHandler.getEventScope();
    } else {
      return escalationHandler.getFlowScope();
    }
  }

}
