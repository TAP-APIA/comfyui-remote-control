package com.example.comfyui

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import com.example.comfyui.network.ComfyUiApi
import com.google.gson.JsonObject

class MainViewModel : ViewModel() {
    // 当前工作流（节点结构）
    private val _currentWorkflow = MutableLiveData<JsonObject?>()
    val currentWorkflow: LiveData<JsonObject?> = _currentWorkflow

    private val _workflowName = MutableLiveData<String?>()
    val workflowName: LiveData<String?> = _workflowName

    // 当前参数配置
    private val _currentParams = MutableLiveData<ParamConfig?>()
    val currentParams: LiveData<ParamConfig?> = _currentParams

    var comfyApi: ComfyUiApi? = null

    // Request to show save dialog for a specific JSON (导出为工作流)
    private val _requestSaveWorkflow = MutableLiveData<Pair<String, JsonObject>?>()
    val requestSaveWorkflow: LiveData<Pair<String, JsonObject>?> = _requestSaveWorkflow

    fun setWorkflow(json: JsonObject, name: String?) {
        _currentWorkflow.value = json
        _workflowName.value = name
    }

    fun setParamConfig(config: ParamConfig) {
        _currentParams.value = config
    }

    fun updateParams(promptSections: List<String>, duration: String, steps: String, width: String, height: String) {
        val existing = _currentParams.value
        _currentParams.value = ParamConfig(existing?.name ?: "", promptSections.joinToString("\n\n"), duration, steps, width, height, promptSections.toList())
    }

    fun requestSave(name: String, json: JsonObject) {
        _requestSaveWorkflow.value = name to json
    }

    fun clearSaveRequest() {
        _requestSaveWorkflow.value = null
    }
}
