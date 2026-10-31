package com.example.comfyui

import android.content.Context
import android.content.SharedPreferences
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import coil.ImageLoader
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import coil.decode.VideoFrameDecoder
import coil.load
import com.example.comfyui.databinding.FragmentFirstBinding
import com.example.comfyui.network.ComfyUiApi
import com.example.comfyui.network.ComfyUiClient
import com.example.comfyui.network.RewriteClient
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.File
import java.util.Random

class FirstFragment : Fragment() {

    private var _binding: FragmentFirstBinding? = null
    private val binding get() = _binding

    private val viewModel: MainViewModel by activityViewModels()
    private var comfyClient: ComfyUiClient? = null
    private var currentUrl: String? = null

    private lateinit var imageLoader: ImageLoader
    private var totalNodesInWorkflow = 0
    private var executedNodeIds = mutableSetOf<String>()

    private val random = Random()
    private var isUpdatingUiFromModel = false
    private val promptEditors = mutableListOf<TextInputEditText>()
    private val promptActions = mutableListOf<Button>()
    private val undoTexts = mutableMapOf<TextInputEditText, String>()
    private val undoButtons = mutableMapOf<TextInputEditText, Button>()
    private var rewriteCall: okhttp3.Call? = null
    private var rewriteProgress: AlertDialog? = null

    data class HistoryItem(val id: String, val url: String, val type: String, val workflow: JsonObject?)

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "selected_device") setupFromPrefs()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentFirstBinding.inflate(inflater, container, false)
        imageLoader = ImageLoader.Builder(requireContext())
            .components {
                if (Build.VERSION.SDK_INT >= 28) add(ImageDecoderDecoder.Factory()) else add(GifDecoder.Factory())
                add(VideoFrameDecoder.Factory())
            }.build()
        return _binding!!.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding?.logText?.visibility = View.GONE
        binding?.submitButton?.setOnClickListener { submitTask() }
        binding?.saveParamsButton?.setOnClickListener {
            syncUiToParams()
            (activity as? MainActivity)?.showSaveParamConfigDialog()
        }
        binding?.loadParamsButton?.setOnClickListener { (activity as? MainActivity)?.showLoadParamConfigDialog() }
        binding?.rewriteSettingsButton?.setOnClickListener { (activity as? MainActivity)?.showRewriteSettingsDialog() }
        binding?.addPromptSectionButton?.setOnClickListener {
            renderPromptSections(readPromptSections() + "")
            syncUiToParams()
            promptEditors.lastOrNull()?.requestFocus()
        }
        renderPromptSections(listOf(""))
        setupTextWatchers()

        viewModel.currentParams.observe(viewLifecycleOwner) { config ->
            if (config != null) applyParamsToUi(config)
        }

        loadLastWorkflow()
        loadLastParams()
        viewModel.currentParams.value?.let { applyParamsToUi(it) }
        setupFromPrefs()
    }

    private fun setupTextWatchers() {
        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (!isUpdatingUiFromModel) {
                    syncUiToParams()
                }
            }
        }
        binding?.stepsEditText?.addTextChangedListener(watcher)
        binding?.widthEditText?.addTextChangedListener(watcher)
        binding?.heightEditText?.addTextChangedListener(watcher)
        binding?.durationEditText?.addTextChangedListener(watcher)
    }

    private fun syncUiToParams() {
        val b = binding ?: return
        viewModel.updateParams(
            readPromptSections(),
            b.durationEditText.text.toString(),
            b.stepsEditText.text.toString(),
            b.widthEditText.text.toString(),
            b.heightEditText.text.toString()
        )
    }

    private fun applyParamsToUi(config: ParamConfig) {
        if (isUpdatingUiFromModel) return
        isUpdatingUiFromModel = true
        binding?.let { b ->
            if (readPromptSections() != config.sections()) renderPromptSections(config.sections())
            if (b.durationEditText.text.toString() != config.duration) b.durationEditText.setText(config.duration)
            if (b.stepsEditText.text.toString() != config.steps) b.stepsEditText.setText(config.steps)
            if (b.widthEditText.text.toString() != config.width) b.widthEditText.setText(config.width)
            if (b.heightEditText.text.toString() != config.height) b.heightEditText.setText(config.height)
        }
        isUpdatingUiFromModel = false
    }

    private fun readPromptSections(): List<String> = promptEditors.map { it.text.toString() }

    private fun renderPromptSections(sections: List<String>) {
        val container = binding?.promptSectionsContainer ?: return
        container.removeAllViews()
        promptEditors.clear()
        promptActions.clear()
        undoTexts.clear()
        undoButtons.clear()
        val values = sections.ifEmpty { listOf("") }
        values.forEachIndexed { index, text ->
            val inputLayout = TextInputLayout(requireContext()).apply {
                hint = "提示词 · 第 ${index + 1} 段"
                layoutParams = LinearLayout.LayoutParams(-1, -2)
            }
            val editor = TextInputEditText(inputLayout.context).apply {
                id = View.generateViewId()
                isSaveEnabled = false
                inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
                gravity = android.view.Gravity.TOP or android.view.Gravity.START
                minLines = 2
                maxLines = 10
                setText(text)
                contentDescription = "提示词第${index + 1}段"
                addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                    override fun afterTextChanged(s: Editable?) {
                        if (!isUpdatingUiFromModel) syncUiToParams()
                    }
                })
            }
            promptEditors.add(editor)
            inputLayout.addView(editor)
            container.addView(inputLayout)
            val actions = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.END
            }
            fun action(label: String, enabled: Boolean, onClick: () -> Unit): Button {
                val button = Button(requireContext(), null, android.R.attr.borderlessButtonStyle).apply {
                    this.text = label
                    tag = enabled
                    isEnabled = enabled && rewriteCall == null
                    layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                    setOnClickListener { onClick() }
                }
                promptActions.add(button)
                actions.addView(button)
                return button
            }
            fun move(offset: Int) {
                val reordered = readPromptSections().toMutableList()
                java.util.Collections.swap(reordered, index, index + offset)
                renderPromptSections(reordered)
                syncUiToParams()
            }
            action("上移", index > 0) { move(-1) }
            action("下移", index < values.lastIndex) { move(1) }
            action("删除", values.size > 1) {
                val remaining = readPromptSections().toMutableList()
                remaining.removeAt(index)
                renderPromptSections(remaining)
                syncUiToParams()
            }
            action("改写", true) { showRewriteDialog(editor) }.contentDescription = "改写第${index + 1}段"
            undoButtons[editor] = action("撤销", false) {
                undoTexts.remove(editor)?.let { editor.setText(it) }
                undoButtons[editor]?.apply { tag = false; isEnabled = false }
            }
            undoButtons[editor]?.contentDescription = "撤销第${index + 1}段改写"
            container.addView(actions)
        }
    }

    private fun showRewriteDialog(editor: TextInputEditText) {
        if (rewriteCall != null) return
        val original = editor.text.toString()
        if (original.isBlank()) {
            Toast.makeText(context, "请先输入这一段的原文", Toast.LENGTH_SHORT).show()
            return
        }
        val settings = try { RewriteSettingsStore(requireContext()).load() } catch (e: Exception) {
            (activity as? MainActivity)?.showRewriteSettingsDialog()
            return
        }
        if (settings.address.isBlank() || settings.model.isBlank()) {
            (activity as? MainActivity)?.showRewriteSettingsDialog()
            return
        }
        val instruction = TextInputEditText(requireContext()).apply {
            hint = "例如：保持原意，写得更简洁"
            minLines = 2
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            setPadding(40, 24, 40, 24)
        }
        val dialog = AlertDialog.Builder(requireContext()).setTitle("改写这一段")
            .setView(instruction).setPositiveButton("开始改写", null).setNegativeButton("取消", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val call = try { RewriteClient.createCall(settings, original, instruction.text.toString()) } catch (e: Exception) {
                    instruction.error = if (instruction.text.isNullOrBlank()) "请输入改写要求" else "请检查模型设置"
                    return@setOnClickListener
                }
                dialog.dismiss()
                rewriteCall = call
                setRewriteBusy(true, editor)
                rewriteProgress = AlertDialog.Builder(requireContext()).setTitle("正在改写")
                    .setMessage("正在发送这一段原文与改写要求，请稍候…")
                    .setCancelable(false).setNegativeButton("取消") { _, _ -> call.cancel() }.show()
                fun finish(text: String?, error: String?) {
                    activity?.runOnUiThread {
                        if (rewriteCall !== call || binding == null) return@runOnUiThread
                        rewriteCall = null
                        rewriteProgress?.dismiss()
                        rewriteProgress = null
                        setRewriteBusy(false, editor)
                        when {
                            text != null && promptEditors.contains(editor) -> {
                                undoTexts[editor] = original
                                editor.setText(text)
                                undoButtons[editor]?.apply { tag = true; isEnabled = true }
                                Toast.makeText(context, "已替换这一段，可点击撤销恢复原文", Toast.LENGTH_LONG).show()
                            }
                            call.isCanceled() -> Toast.makeText(context, "已取消，原文保留", Toast.LENGTH_SHORT).show()
                            else -> AlertDialog.Builder(requireContext()).setTitle("改写未完成")
                                .setMessage((error ?: "输入框已切换，未替换文本") + "\n原文未被覆盖。")
                                .setPositiveButton("确定", null).show()
                        }
                    }
                }
                call.enqueue(object : okhttp3.Callback {
                    override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                        finish(null, "连接失败或请求超时，请检查模型地址和网络")
                    }
                    override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                        response.use {
                            if (!it.isSuccessful) {
                                finish(null, "模型接口返回 HTTP ${it.code}，请检查密钥、模型名和服务状态")
                                return
                            }
                            try {
                                finish(RewriteClient.rewrittenText(it.body?.string().orEmpty()), null)
                            } catch (e: Exception) {
                                finish(null, "接口没有返回可用的改写正文，请确认兼容 Chat Completions")
                            }
                        }
                    }
                })
            }
        }
        dialog.show()
    }

    private fun setRewriteBusy(busy: Boolean, editor: TextInputEditText) {
        editor.isEnabled = !busy
        binding?.addPromptSectionButton?.isEnabled = !busy
        binding?.loadParamsButton?.isEnabled = !busy
        binding?.saveParamsButton?.isEnabled = !busy
        binding?.submitButton?.isEnabled = !busy
        promptActions.forEach { it.isEnabled = !busy && it.tag == true }
    }

    fun refreshAll() {
        updateQueue()
        updateHistory()
        Toast.makeText(context, "同步中...", Toast.LENGTH_SHORT).show()
    }

    private fun setupFromPrefs() {
        val prefs = context?.getSharedPreferences("comfyui_prefs", Context.MODE_PRIVATE) ?: return
        val url = prefs.getString("selected_device", null) ?: return
        if (url != currentUrl || comfyClient == null || !comfyClient!!.isConnected()) {
            setupClient(url)
        } else {
            updateQueue(); updateHistory()
        }
    }

    private fun forceRandomizeAllSeeds(json: JsonObject) {
        try {
            for (entry in json.entrySet()) {
                val inputs = entry.value.asJsonObject.getAsJsonObject("inputs") ?: continue
                val newSeed = Math.abs(random.nextLong())
                arrayOf("seed", "noise_seed", "rand_seed", "seed_number", "manual_seed").forEach { key ->
                    if (inputs.has(key)) inputs.addProperty(key, newSeed)
                }
            }
        } catch (e: Exception) {}
    }

    private fun setupClient(url: String) {
        currentUrl = url
        comfyClient?.disconnect()
        comfyClient = ComfyUiClient(url).apply {
            listener = object : ComfyUiClient.ProgressListener {
                override fun onProgress(node: String?, value: Int, max: Int) {
                    activity?.runOnUiThread {
                        binding?.let { b ->
                            b.progressIndicator.max = max
                            b.progressIndicator.progress = value
                            val percent = if (max > 0) (value * 100) / max else 0
                            b.statusText.text = "采样进度: $percent% ($value/$max)"
                        }
                    }
                }

                override fun onStatusUpdate(status: ComfyUiClient.TaskStatus, message: String?, nodeId: String?) {
                    activity?.runOnUiThread {
                        when (status) {
                            ComfyUiClient.TaskStatus.EXECUTING -> {
                                nodeId?.let { if (it != "null") executedNodeIds.add(it) }
                                updateOverallUI(); updateQueue()
                            }
                            ComfyUiClient.TaskStatus.FINISHED -> {
                                binding?.overallStatusText?.text = "任务已完成"
                                binding?.progressIndicator?.progress = 0
                                updateHistory(); executedNodeIds.clear()
                            }
                            ComfyUiClient.TaskStatus.CONNECTED -> {
                                binding?.overallStatusText?.text = "在线"
                                updateHistory(); updateQueue()
                            }
                            else -> {
                                if (message != null) binding?.overallStatusText?.text = message
                                updateQueue()
                            }
                        }
                    }
                }

                override fun onImageReady(filename: String, subfolder: String, type: String) {
                    activity?.runOnUiThread {
                        val imageUrl = "${currentUrl}/view?filename=${Uri.encode(filename)}&subfolder=${Uri.encode(subfolder)}&type=${Uri.encode(type)}"
                        binding?.resultImage?.visibility = View.VISIBLE
                        binding?.resultImage?.load(imageUrl, imageLoader) {
                            diskCachePolicy(coil.request.CachePolicy.DISABLED)
                            memoryCachePolicy(coil.request.CachePolicy.DISABLED)
                        }
                        binding?.previewCard?.visibility = View.GONE
                        view?.postDelayed({ updateHistory() }, 1500)
                    }
                }

                override fun onPreviewReady(bytes: ByteArray) {
                    activity?.runOnUiThread {
                        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        if (bitmap != null) {
                            binding?.previewCard?.visibility = View.VISIBLE
                            binding?.previewImage?.setImageBitmap(bitmap)
                        }
                    }
                }
            }
            connect()
        }
        val rf = Retrofit.Builder().baseUrl(if (url.endsWith("/")) url else "$url/").addConverterFactory(GsonConverterFactory.create()).build()
        viewModel.comfyApi = rf.create(ComfyUiApi::class.java)
    }

    private fun updateOverallUI() {
        binding?.let { b ->
            if (totalNodesInWorkflow > 0) {
                val count = executedNodeIds.size
                b.overallProgressIndicator.max = totalNodesInWorkflow
                b.overallProgressIndicator.progress = count
                val percent = if (totalNodesInWorkflow > 0) (count * 100 / totalNodesInWorkflow) else 0
                b.overallStatusText.text = "工作流进度: $count/$totalNodesInWorkflow ($percent%)"
            }
        }
    }

    private fun updateQueue() {
        viewModel.comfyApi?.getQueue()?.enqueue(object : Callback<JsonObject> {
            override fun onResponse(call: Call<JsonObject>, response: Response<JsonObject>) {
                if (response.isSuccessful) {
                    val body = response.body() ?: return
                    activity?.runOnUiThread { displayQueue(body.getAsJsonArray("queue_running"), body.getAsJsonArray("queue_pending")) }
                }
            }
            override fun onFailure(call: Call<JsonObject>, t: Throwable) {}
        })
    }

    private fun displayQueue(running: JsonArray, pending: JsonArray) {
        val container = binding?.queueContainer ?: return
        container.removeAllViews()
        fun add(item: JsonArray, status: String, isRun: Boolean) {
            val id = item[1].asString
            val graph = item[2].asJsonObject
            val params = extractParamsFromGraph(graph)

            val itemView = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL; setPadding(20, 10, 20, 10); gravity = android.view.Gravity.CENTER_VERTICAL
                isClickable = true; isFocusable = true
                val out = android.util.TypedValue(); context.theme.resolveAttribute(android.R.attr.selectableItemBackground, out, true)
                setBackgroundResource(out.resourceId)
                setOnClickListener { showTaskDetails(id, params, graph) }
            }
            val textInfo = TextView(requireContext()).apply {
                text = "[$status] ${id.take(8)}..."; layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            }
            val btnCancel = Button(requireContext(), null, android.R.attr.borderlessButtonStyle).apply {
                text = if (isRun) "中断" else "取消"; setTextColor(0xFFFF0000.toInt())
                setOnClickListener { if (isRun) interrupt() else deleteItem(id) }
            }
            itemView.addView(textInfo); itemView.addView(btnCancel)
            container.addView(itemView)
        }
        running.forEach { add(it.asJsonArray, "运行中", true) }
        pending.forEach { add(it.asJsonArray, "排队", false) }
        binding?.queueText?.visibility = if (running.size() + pending.size() == 0) View.VISIBLE else View.GONE
    }

    private fun interrupt() { viewModel.comfyApi?.interrupt()?.enqueue(object : Callback<JsonObject> { override fun onResponse(c: Call<JsonObject>, r: Response<JsonObject>) { updateQueue() }; override fun onFailure(c: Call<JsonObject>, t: Throwable) {} }) }
    private fun deleteItem(id: String) { 
        val body = JsonObject().apply { add("delete", JsonArray().apply { add(id) }) }
        viewModel.comfyApi?.deleteQueueItem(body)?.enqueue(object : Callback<JsonObject> { override fun onResponse(c: Call<JsonObject>, r: Response<JsonObject>) { updateQueue() }; override fun onFailure(c: Call<JsonObject>, t: Throwable) {} })
    }

    private fun updateHistory() {
        viewModel.comfyApi?.getHistory()?.enqueue(object : Callback<JsonObject> {
            override fun onResponse(call: Call<JsonObject>, response: Response<JsonObject>) {
                if (response.isSuccessful) {
                    val body = response.body() ?: return
                    val items = mutableListOf<HistoryItem>()
                    for (entry in body.entrySet()) {
                        val historyObj = entry.value.asJsonObject
                        val out = historyObj.getAsJsonObject("outputs") ?: continue
                        val promptElement = historyObj.get("prompt")
                        val workflow = when {
                            promptElement == null -> null
                            promptElement.isJsonArray -> promptElement.asJsonArray[2].asJsonObject
                            promptElement.isJsonObject -> promptElement.asJsonObject
                            else -> null
                        }
                        var found = false
                        for (key in arrayOf("videos", "gifs", "images")) {
                            if (found) break
                            for (nodeOut in out.entrySet()) {
                                val files = nodeOut.value.asJsonObject.getAsJsonArray(key)
                                if (files != null && files.size() > 0) {
                                    val f = files[0].asJsonObject
                                    val url = "${currentUrl}/view?filename=${Uri.encode(f.get("filename").asString)}&subfolder=${Uri.encode(f.get("subfolder").asString)}&type=${Uri.encode(f.get("type").asString)}"
                                    items.add(HistoryItem(entry.key, url, if (key == "images") "🖼️ 图像" else "🎬 视频", workflow))
                                    found = true; break
                                }
                            }
                        }
                    }
                    activity?.runOnUiThread { displayHistory(items.reversed().take(15)) }
                }
            }
            override fun onFailure(call: Call<JsonObject>, t: Throwable) {}
        })
    }

    private fun displayHistory(items: List<HistoryItem>) {
        val container = binding?.historyContainer ?: return
        container.removeAllViews()
        for (item in items) {
            val view = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL; setPadding(24, 24, 24, 24); isClickable = true; isFocusable = true
                val out = android.util.TypedValue(); context.theme.resolveAttribute(android.R.attr.selectableItemBackground, out, true)
                setBackgroundResource(out.resourceId)
            }
            val img = ImageView(requireContext()).apply { layoutParams = LinearLayout.LayoutParams(180, 180); scaleType = ImageView.ScaleType.CENTER_CROP }
            img.load(item.url, imageLoader) { crossfade(true); placeholder(android.R.drawable.ic_menu_gallery) }
            val info = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 0, 0, 0) }
            info.addView(TextView(requireContext()).apply { text = "任务: ${item.id.take(8)}"; textSize = 14f })
            info.addView(TextView(requireContext()).apply { text = item.type; textSize = 12f; setTextColor(0xFF666666.toInt()) })
            view.addView(img); view.addView(info)
            view.setOnClickListener {
                binding?.resultImage?.visibility = View.VISIBLE
                binding?.resultImage?.load(item.url, imageLoader)
                binding?.resultImage?.setOnClickListener { try { startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse(item.url))) } catch (e: Exception) {} }
            }
            view.setOnLongClickListener {
                val params = if (item.workflow != null) extractParamsFromGraph(item.workflow) else emptyMap()
                showTaskDetails(item.id, params, item.workflow); true
            }
            container.addView(view)
        }
    }

    private fun submitTask() {
        val wf = viewModel.currentWorkflow.value ?: run { Toast.makeText(context, "请先选择工作流", Toast.LENGTH_SHORT).show(); return }
        val api = viewModel.comfyApi ?: run { Toast.makeText(context, "未连接", Toast.LENGTH_SHORT).show(); return }
        executedNodeIds.clear()
        val mod = try {
            WorkflowPrompt.prepare(wf).also {
                forceRandomizeAllSeeds(it)
                injectParameters(it)
            }
        } catch (e: Exception) {
            showSubmitError(e.message ?: "工作流格式错误")
            return
        }
        totalNodesInWorkflow = mod.entrySet().size
        val body = JsonObject().apply { add("prompt", mod); addProperty("client_id", comfyClient?.clientId) }
        api.postPrompt(body).enqueue(object : Callback<JsonObject> {
            override fun onResponse(c: Call<JsonObject>, r: Response<JsonObject>) { 
                if (r.isSuccessful) { updateQueue(); context?.let { Toast.makeText(it, "任务已发送", Toast.LENGTH_SHORT).show() } }
                else showSubmitError("HTTP ${r.code()}: ${r.errorBody()?.string()?.take(3000) ?: r.message()}")
            }
            override fun onFailure(call: Call<JsonObject>, t: Throwable) {
                showSubmitError("连接失败：${t.message ?: "未知网络错误"}")
            }
        })
    }

    private fun showSubmitError(message: String) {
        binding?.overallStatusText?.text = "提交失败"
        context?.let {
            AlertDialog.Builder(it).setTitle("工作流提交失败").setMessage(message)
                .setPositiveButton("确定", null).show()
        }
    }

    private fun injectParameters(wf: JsonObject) {
        val p = readPromptSections().joinToString("\n\n"); val s = binding?.stepsEditText?.text.toString()
        val w = binding?.widthEditText?.text.toString(); val h = binding?.heightEditText?.text.toString()
        val len = ((binding?.durationEditText?.text.toString().toDoubleOrNull() ?: 1.0) * 24).toInt().toString()
        for (entry in wf.entrySet()) {
            val inputs = entry.value.asJsonObject.getAsJsonObject("inputs") ?: continue
            arrayOf("text", "prompt").forEach { if (inputs.has(it)) inputs.addProperty(it, p) }
            arrayOf("steps", "num_steps").forEach { if (inputs.has(it)) inputs.addProperty(it, s) }
            if (inputs.has("width")) inputs.addProperty("width", w)
            if (inputs.has("height")) inputs.addProperty("height", h)
            arrayOf("length", "frame_count").forEach { if (inputs.has(it)) inputs.addProperty(it, len) }
        }
    }

    private fun loadLastWorkflow() {
        val f = File(requireContext().filesDir, "last_workflow.json")
        if (f.exists()) {
            try {
                val content = f.readText()
                val json = JsonParser.parseString(content).asJsonObject
                val n = requireContext().getSharedPreferences("comfyui_prefs", Context.MODE_PRIVATE).getString("last_workflow_name", "workflow.json")
                viewModel.setWorkflow(json, n)
            } catch (e: Exception) {}
        }
    }

    private fun loadLastParams() {
        val prefs = requireContext().getSharedPreferences("comfyui_prefs", Context.MODE_PRIVATE)
        val json = prefs.getString("last_param_config", null)
        if (json != null) {
            try {
                val config = Gson().fromJson(json, ParamConfig::class.java)
                viewModel.setParamConfig(config)
                return
            } catch (e: Exception) {}
        }
        // 无历史参数时，用界面默认值初始化当前参数
        syncUiToParams()
    }

    private fun extractParamsFromGraph(json: JsonObject): Map<String, String> {
        val result = mutableMapOf<String, String>()
        for (entry in json.entrySet()) {
            val node = entry.value.asJsonObject
            val inputs = node.getAsJsonObject("inputs") ?: continue
            fun getVal(keys: Array<String>): String? {
                for (key in keys) {
                    val el = inputs.get(key)
                    if (el != null && el.isJsonPrimitive) return el.asString
                }
                return null
            }
            getVal(arrayOf("text", "prompt"))?.let { if (!result.containsKey("prompt") || result["prompt"]!!.length < it.length) result["prompt"] = it }
            getVal(arrayOf("steps"))?.let { if (!result.containsKey("steps")) result["steps"] = it }
            getVal(arrayOf("width"))?.let { if (!result.containsKey("width")) result["width"] = it }
            getVal(arrayOf("height"))?.let { if (!result.containsKey("height")) result["height"] = it }
            getVal(arrayOf("length", "frame_count"))?.let { if (!result.containsKey("length")) result["length"] = it }
        }
        return result
    }

    private fun showTaskDetails(id: String, params: Map<String, String>, workflow: JsonObject?) {
        val detail = StringBuilder().append("任务 ID: $id\n\n提示词:\n${params["prompt"] ?: "无"}\n\n步数: ${params["steps"] ?: "默认"}\n分辨率: ${params["width"] ?: "?"}x${params["height"] ?: "?"}")
        val builder = AlertDialog.Builder(requireContext()).setTitle("任务详情").setMessage(detail.toString()).setPositiveButton("确定", null)
        if (workflow != null) builder.setNeutralButton("保存为工作流") { _, _ -> viewModel.requestSave("从任务${id.take(4)}导出", workflow) }
        builder.show()
    }

    override fun onResume() { super.onResume(); context?.getSharedPreferences("comfyui_prefs", Context.MODE_PRIVATE)?.registerOnSharedPreferenceChangeListener(prefsListener); setupFromPrefs() }
    override fun onPause() {
        super.onPause()
        val prefs = context?.getSharedPreferences("comfyui_prefs", Context.MODE_PRIVATE)
        prefs?.unregisterOnSharedPreferenceChangeListener(prefsListener)
        viewModel.currentParams.value?.let { cfg ->
            prefs?.edit()?.putString("last_param_config", Gson().toJson(cfg))?.apply()
        }
    }
    override fun onDestroyView() {
        rewriteCall?.cancel()
        rewriteCall = null
        rewriteProgress?.dismiss()
        rewriteProgress = null
        super.onDestroyView()
        comfyClient?.disconnect()
        promptEditors.clear()
        promptActions.clear()
        undoTexts.clear()
        undoButtons.clear()
        _binding = null
    }
}
