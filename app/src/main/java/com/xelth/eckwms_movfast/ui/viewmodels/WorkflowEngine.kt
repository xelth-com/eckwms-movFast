package com.xelth.eckwms_movfast.ui.viewmodels

import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.xelth.eckwms_movfast.ui.data.Workflow
import com.xelth.eckwms_movfast.ui.data.WorkflowStep

data class WorkflowState(
    val isActive: Boolean = false,
    val currentStep: WorkflowStep? = null,
    val instruction: String = "",
    val title: String = "",
    val showEndSessionButton: Boolean = false,
    val endSessionButtonLabel: String = "End"
)

class WorkflowEngine(private val workflow: Workflow, private val onLog: (String) -> Unit) {
    private val TAG = "WorkflowEngine"
    private var currentStepIndex = -1
    private val variables = mutableMapOf<String, Any>()

    private val _state = MutableLiveData(WorkflowState())
    val state: LiveData<WorkflowState> = _state

    fun start() {
        onLog("Workflow '${workflow.workflowName}' started.")
        currentStepIndex = 0
        processCurrentStep()
    }

    fun onBarcodeScanned(barcode: String) {
        val step = state.value?.currentStep ?: return
        val varName = step.variable ?: return
        onLog("[WorkflowEngine] Barcode received: '$barcode' for step ${step.stepId} ('${step.action}')")

        if (step.loop?.condition == "user_ends_session") {
            val currentList = (variables[varName] as? MutableList<String>) ?: mutableListOf()
            currentList.add(barcode)
            variables[varName] = currentList
            onLog("Added '$barcode' to variable list '$varName'. New count: ${currentList.size}")
        } else {
            variables[varName] = barcode
            onLog("[WorkflowEngine] Variable '${varName}' set to '$barcode'.")
            proceedToNextStep()
        }
    }

    fun onImageCaptured(relatedData: Any? = null) {
        val step = state.value?.currentStep ?: return
        onLog("Image captured for step ${step.stepId}")
        // Here you would typically add upload logic
        // For now, we just proceed
        proceedToNextStep()
    }

    fun endLoop() {
        onLog("User ended loop for step ${state.value?.currentStep?.stepId}")
        proceedToNextStep()
    }

    private fun proceedToNextStep() {
        currentStepIndex++
        if (currentStepIndex < workflow.steps.size) {
            processCurrentStep()
        } else {
            onLog("Workflow finished.")
            _state.postValue(WorkflowState(isActive = false))
        }
    }

    private fun processCurrentStep() {
        val step = workflow.steps[currentStepIndex]
        onLog("[WorkflowEngine] ==> Processing step ${step.stepId}: ACTION=${step.action}, VAR=${step.variable ?: "N/A"}")

        var instruction = step.ui.instruction
        // Variable substitution
        variables.forEach { (key, value) ->
            // Replace simple variable reference
            instruction = instruction.replace("{{$key}}", value.toString())

            // Replace .size for lists
            if (value is List<*>) {
                instruction = instruction.replace("{{$key.size}}", value.size.toString())
            }
        }

        _state.postValue(
            WorkflowState(
                isActive = true,
                currentStep = step,
                title = step.ui.title,
                instruction = instruction,
                showEndSessionButton = step.loop?.condition == "user_ends_session",
                endSessionButtonLabel = step.loop?.endButtonLabel ?: "End"
            )
        )
    }
}
