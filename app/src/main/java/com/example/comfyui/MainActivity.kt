package com.example.comfyui

import android.os.Bundle
import android.text.InputType
import android.view.Menu
import android.view.MenuItem
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GravityCompat
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.navigateUp
import androidx.navigation.ui.setupActionBarWithNavController
import androidx.navigation.ui.setupWithNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.comfyui.databinding.ActivityMainBinding
import com.example.comfyui.network.RewriteClient
import com.example.comfyui.network.RewriteSettings
import com.google.android.material.textfield.TextInputLayout
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.navigation.NavigationView
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var appBarConfiguration: AppBarConfiguration
    private lateinit var binding: ActivityMainBinding
    private val devices = mutableListOf<String>()

    private val viewModel: MainViewModel by viewModels()
    private val workflows = mutableListOf<WorkflowItem>()
    private val paramConfigs = mutableListOf<ParamConfig>()
    private lateinit var workflowAdapter: NamedItemAdapter<WorkflowItem>
    private lateinit var paramConfigAdapter: NamedItemAdapter<ParamConfig>

    private val pickJsonLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { handleSelectedJson(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)

        val drawerLayout = binding.drawerLayout
        val navView: NavigationView = binding.navView
        val navHostFragment =
            supportFragmentManager.findFragmentById(R.id.nav_host_fragment_content_main) as NavHostFragment
        val navController = navHostFragment.navController

        appBarConfiguration = AppBarConfiguration(
            setOf(
                R.id.FirstFragment, R.id.SecondFragment
            ), drawerLayout
        )
        setupActionBarWithNavController(navController, appBarConfiguration)
        navView.setupWithNavController(navController)

        navView.setNavigationItemSelectedListener { menuItem ->
            if (menuItem.groupId == R.id.nav_devices_group) {
                selectDevice(menuItem.title.toString())
                drawerLayout.closeDrawer(GravityCompat.START)
                true
            } else {
                when (menuItem.itemId) {
                    R.id.nav_add_device -> {
                        showAddDeviceDialog()
                        drawerLayout.closeDrawer(GravityCompat.START)
                        true
                    }
                    R.id.nav_manage_devices -> {
                        showDeleteDevicesList()
                        drawerLayout.closeDrawer(GravityCompat.START)
                        true
                    }
                    else -> false
                }
            }
        }

        setupDrawer()
        loadDevices()
        updateDeviceMenu()
        loadWorkflows()
        loadParamConfigs()

        viewModel.requestSaveWorkflow.observe(this) { pair ->
            if (pair != null) {
                showSaveWorkflowDialog(pair.first, pair.second.toString())
                viewModel.clearSaveRequest()
            }
        }
    }

    private fun setupDrawer() {
        workflowAdapter = NamedItemAdapter(workflows,
            nameOf = { it.name },
            onSelect = { item ->
                viewModel.setWorkflow(JsonParser.parseString(item.json).asJsonObject, item.name)
                persistLastWorkflow(item)
                binding.drawerLayout.closeDrawer(GravityCompat.END)
                Toast.makeText(this, "已加载工作流: ${item.name}", Toast.LENGTH_SHORT).show()
            },
            onRename = { item -> showRenameWorkflowDialog(item) },
            onDelete = { item -> showDeleteWorkflowDialog(item) }
        )
        binding.rvWorkflows.layoutManager = LinearLayoutManager(this)
        binding.rvWorkflows.adapter = workflowAdapter

        paramConfigAdapter = NamedItemAdapter(paramConfigs,
            nameOf = { it.name },
            onSelect = { cfg ->
                applyParamConfig(cfg)
                binding.drawerLayout.closeDrawer(GravityCompat.END)
                Toast.makeText(this, "已加载参数配置: ${cfg.name}", Toast.LENGTH_SHORT).show()
            },
            onRename = { cfg -> showRenameParamConfigDialog(cfg) },
            onDelete = { cfg -> showDeleteParamConfigDialog(cfg) }
        )
        binding.rvParamConfigs.layoutManager = LinearLayoutManager(this)
        binding.rvParamConfigs.adapter = paramConfigAdapter

        binding.btnImportJson.setOnClickListener { pickJsonLauncher.launch("application/json") }
        binding.btnSyncLast.setOnClickListener { loadLastWorkflowFromServer() }
        binding.btnSaveParams.setOnClickListener { showSaveParamConfigDialog() }
    }

    // ---- 工作流（节点结构） ----

    private fun performSaveWorkflow(name: String, jsonContent: String) {
        val existingIndex = workflows.indexOfFirst { it.name == name }
        if (existingIndex != -1) {
            workflows[existingIndex] = WorkflowItem(name, jsonContent)
            workflowAdapter.notifyItemChanged(existingIndex)
            Toast.makeText(this, "已更新工作流: $name", Toast.LENGTH_SHORT).show()
        } else {
            workflows.add(WorkflowItem(name, jsonContent))
            workflowAdapter.notifyItemInserted(workflows.size - 1)
            Toast.makeText(this, "已保存工作流: $name", Toast.LENGTH_SHORT).show()
        }
        val item = WorkflowItem(name, jsonContent)
        viewModel.setWorkflow(JsonParser.parseString(jsonContent).asJsonObject, name)
        persistLastWorkflow(item)
        saveWorkflows()
    }

    private fun showSaveWorkflowDialog(defaultName: String, jsonContent: String) {
        val editText = EditText(this)
        editText.setText(defaultName)
        editText.hint = "工作流名称"
        AlertDialog.Builder(this)
            .setTitle("保存工作流")
            .setView(editText)
            .setPositiveButton("保存") { _, _ ->
                val name = editText.text.toString().trim()
                if (name.isNotEmpty()) performSaveWorkflow(name, jsonContent)
            }
            .setNegativeButton("取消", null).show()
    }

    private fun showRenameWorkflowDialog(item: WorkflowItem) {
        val editText = EditText(this)
        editText.setText(item.name)
        AlertDialog.Builder(this).setTitle("重命名").setView(editText)
            .setPositiveButton("确定") { _, _ ->
                val newName = editText.text.toString().trim()
                if (newName.isNotEmpty()) {
                    val pos = workflows.indexOf(item)
                    if (pos != -1) {
                        val oldName = item.name
                        workflows[pos] = item.copy(name = newName)
                        if (viewModel.workflowName.value == oldName) {
                            viewModel.setWorkflow(viewModel.currentWorkflow.value ?: JsonObject(), newName)
                            persistLastWorkflow(workflows[pos])
                        }
                        saveWorkflows()
                        workflowAdapter.notifyItemChanged(pos)
                    }
                }
            }
            .setNegativeButton("取消", null).show()
    }

    private fun showDeleteWorkflowDialog(item: WorkflowItem) {
        AlertDialog.Builder(this).setTitle("删除").setMessage("确定删除 '${item.name}' 吗？")
            .setPositiveButton("删除") { _, _ ->
                val pos = workflows.indexOf(item)
                if (pos != -1) {
                    workflows.removeAt(pos)
                    saveWorkflows()
                    workflowAdapter.notifyItemRemoved(pos)
                }
            }
            .setNegativeButton("取消", null).show()
    }

    // ---- 参数配置 ----

    fun showRewriteSettingsDialog() {
        val store = RewriteSettingsStore(this)
        val saved = try { store.load() } catch (e: Exception) {
            Toast.makeText(this, "密钥无法读取，请重新保存", Toast.LENGTH_LONG).show()
            RewriteSettings("", "", "")
        }
        val form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 16, 40, 0) }
        fun field(label: String, value: String, password: Boolean = false): TextInputEditText {
            val box = TextInputLayout(this).apply {
                hint = label
                if (password) endIconMode = TextInputLayout.END_ICON_PASSWORD_TOGGLE
            }
            val input = TextInputEditText(box.context).apply {
                setSingleLine(true)
                inputType = if (password) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_CLASS_TEXT
                setText(value)
            }
            box.addView(input); form.addView(box)
            return input
        }
        val address = field("API 地址（/v1 或完整 /chat/completions）", saved.address)
        val model = field("模型名称", saved.model)
        val secret = field("API 密钥（本地无鉴权接口可留空）", saved.apiKey, true)
        val dialog = AlertDialog.Builder(this).setTitle("改写模型设置 · OpenAI 兼容")
            .setView(form).setPositiveButton("保存", null).setNegativeButton("取消", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                try {
                    val settings = RewriteSettings(address.text.toString().trim(), secret.text.toString().trim(), model.text.toString().trim())
                    RewriteClient.endpoint(settings.address)
                    require(settings.model.isNotEmpty()) { "请填写模型名称" }
                    store.save(settings)
                    Toast.makeText(this, "改写模型设置已保存", Toast.LENGTH_SHORT).show()
                    dialog.dismiss()
                } catch (e: Exception) {
                    Toast.makeText(this, "保存失败，请检查 API 地址和模型名称", Toast.LENGTH_LONG).show()
                }
            }
        }
        dialog.show()
    }

    private fun performSaveParamConfig(name: String, config: ParamConfig) {
        val existingIndex = paramConfigs.indexOfFirst { it.name == name }
        if (existingIndex != -1) {
            paramConfigs[existingIndex] = config
            paramConfigAdapter.notifyItemChanged(existingIndex)
            Toast.makeText(this, "已更新参数配置: $name", Toast.LENGTH_SHORT).show()
        } else {
            paramConfigs.add(config)
            paramConfigAdapter.notifyItemInserted(paramConfigs.size - 1)
            Toast.makeText(this, "已保存参数配置: $name", Toast.LENGTH_SHORT).show()
        }
        applyParamConfig(config)
        saveParamConfigs()
    }

    fun showSaveParamConfigDialog() {
        val current = viewModel.currentParams.value
        if (current == null) {
            Toast.makeText(this, "请先在主界面编辑参数", Toast.LENGTH_SHORT).show()
            return
        }
        val currentName = current.name.takeIf { it.isNotEmpty() }
        val editText = EditText(this)
        editText.setText(currentName ?: "")
        editText.hint = "参数配置名称"
        val builder = AlertDialog.Builder(this)
            .setTitle("保存参数配置")
            .setView(editText)
            .setPositiveButton("保存为新") { _, _ ->
                val name = editText.text.toString().trim()
                if (name.isNotEmpty()) performSaveParamConfig(name, current.copy(name = name))
            }
        if (currentName != null) {
            builder.setNeutralButton("覆盖当前 ($currentName)") { _, _ ->
                performSaveParamConfig(currentName, current.copy(name = currentName))
            }
        }
        builder.setNegativeButton("取消", null).show()
    }

    fun showLoadParamConfigDialog() {
        if (paramConfigs.isEmpty()) {
            Toast.makeText(this, "还没有保存的参数配置", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this).setTitle("读取参数配置")
            .setItems(paramConfigs.map { it.name }.toTypedArray()) { _, index ->
                applyParamConfig(paramConfigs[index])
                Toast.makeText(this, "已加载参数配置: ${paramConfigs[index].name}", Toast.LENGTH_SHORT).show()
            }.setNegativeButton("取消", null).show()
    }

    private fun applyParamConfig(config: ParamConfig) {
        viewModel.setParamConfig(config)
        getSharedPreferences("comfyui_prefs", MODE_PRIVATE).edit()
            .putString("last_param_config", Gson().toJson(config)).apply()
    }

    private fun showRenameParamConfigDialog(cfg: ParamConfig) {
        val editText = EditText(this)
        editText.setText(cfg.name)
        AlertDialog.Builder(this).setTitle("重命名").setView(editText)
            .setPositiveButton("确定") { _, _ ->
                val newName = editText.text.toString().trim()
                if (newName.isNotEmpty()) {
                    val pos = paramConfigs.indexOf(cfg)
                    if (pos != -1) {
                        val oldName = cfg.name
                        paramConfigs[pos] = cfg.copy(name = newName)
                        if (viewModel.currentParams.value?.name == oldName) {
                            applyParamConfig(cfg.copy(name = newName))
                        }
                        saveParamConfigs()
                        paramConfigAdapter.notifyItemChanged(pos)
                    }
                }
            }
            .setNegativeButton("取消", null).show()
    }

    private fun showDeleteParamConfigDialog(cfg: ParamConfig) {
        AlertDialog.Builder(this).setTitle("删除").setMessage("确定删除 '${cfg.name}' 吗？")
            .setPositiveButton("删除") { _, _ ->
                val pos = paramConfigs.indexOf(cfg)
                if (pos != -1) {
                    paramConfigs.removeAt(pos)
                    saveParamConfigs()
                    paramConfigAdapter.notifyItemRemoved(pos)
                }
            }
            .setNegativeButton("取消", null).show()
    }

    // ---- 导入 / 同步 ----

    private fun handleSelectedJson(uri: android.net.Uri) {
        try {
            val content = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            if (content != null) {
                val name = uri.lastPathSegment?.removeSuffix(".json") ?: "未命名工作流"
                showSaveWorkflowDialog(name, content)
            }
        } catch (e: Exception) {
            Toast.makeText(this, "导入失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun loadLastWorkflowFromServer() {
        val api = viewModel.comfyApi ?: run { Toast.makeText(this, "未连接", Toast.LENGTH_SHORT).show(); return }
        api.getHistory().enqueue(object : retrofit2.Callback<JsonObject> {
            override fun onResponse(call: retrofit2.Call<JsonObject>, response: retrofit2.Response<JsonObject>) {
                if (response.isSuccessful) {
                    val body = response.body() ?: return
                    val keys = body.keySet().toList()
                    if (keys.isNotEmpty()) {
                        try {
                            val lastKey = keys.last()
                            val historyObj = body.getAsJsonObject(lastKey)
                            val promptElement = historyObj.get("prompt")
                            val promptJson = when {
                                promptElement == null -> null
                                promptElement.isJsonArray -> promptElement.asJsonArray[2].asJsonObject
                                promptElement.isJsonObject -> promptElement.asJsonObject
                                else -> null
                            }
                            if (promptJson != null) {
                                showSaveWorkflowDialog("云端同步任务", promptJson.toString())
                                return
                            }
                        } catch (e: Exception) {}
                    }
                }
                Toast.makeText(this@MainActivity, "同步失败", Toast.LENGTH_SHORT).show()
            }
            override fun onFailure(call: retrofit2.Call<JsonObject>, t: Throwable) {
                Toast.makeText(this@MainActivity, "网络错误", Toast.LENGTH_SHORT).show()
            }
        })
    }

    // ---- 持久化 ----

    private fun loadWorkflows() {
        val prefs = getSharedPreferences("comfyui_prefs", MODE_PRIVATE)
        val json = prefs.getString("saved_workflows", null)
        if (json != null) {
            val type = object : TypeToken<List<WorkflowItem>>() {}.type
            workflows.clear(); workflows.addAll(Gson().fromJson(json, type))
            workflowAdapter.notifyDataSetChanged()
        }
    }

    private fun saveWorkflows() {
        getSharedPreferences("comfyui_prefs", MODE_PRIVATE).edit().putString("saved_workflows", Gson().toJson(workflows)).apply()
    }

    private fun loadParamConfigs() {
        val prefs = getSharedPreferences("comfyui_prefs", MODE_PRIVATE)
        val json = prefs.getString("saved_param_configs", null)
        if (json != null) {
            val type = object : TypeToken<List<ParamConfig>>() {}.type
            paramConfigs.clear(); paramConfigs.addAll(Gson().fromJson(json, type))
            paramConfigAdapter.notifyDataSetChanged()
        }
    }

    private fun saveParamConfigs() {
        getSharedPreferences("comfyui_prefs", MODE_PRIVATE).edit().putString("saved_param_configs", Gson().toJson(paramConfigs)).apply()
    }

    private fun persistLastWorkflow(item: WorkflowItem) {
        try {
            File(filesDir, "last_workflow.json").writeText(item.json)
            getSharedPreferences("comfyui_prefs", MODE_PRIVATE).edit().putString("last_workflow_name", item.name).apply()
        } catch (e: Exception) {}
    }

    // ---- 菜单 ----

    override fun onCreateOptionsMenu(menu: Menu): Boolean { menuInflater.inflate(R.menu.menu_main, menu); return true }
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_refresh -> {
                val navHostFragment = supportFragmentManager.findFragmentById(R.id.nav_host_fragment_content_main) as NavHostFragment
                val fragment = navHostFragment.childFragmentManager.fragments.firstOrNull { it is FirstFragment } as? FirstFragment
                fragment?.refreshAll(); true
            }
            R.id.action_workflows -> { binding.drawerLayout.openDrawer(GravityCompat.END); true }
            else -> super.onOptionsItemSelected(item)
        }
    }

    // ---- 设备管理 ----

    private fun loadDevices() {
        val prefs = getSharedPreferences("comfyui_prefs", MODE_PRIVATE)
        val saved = prefs.getStringSet("devices", setOf("http://127.0.0.1:8188"))
        devices.clear(); saved?.forEach { devices.add(if (!it.startsWith("http")) "http://$it" else it) }
    }

    private fun saveDevices() { getSharedPreferences("comfyui_prefs", MODE_PRIVATE).edit().putStringSet("devices", devices.toSet()).apply() }

    private fun updateDeviceMenu() {
        val subMenu = binding.navView.menu.findItem(R.id.nav_devices_parent)?.subMenu
        subMenu?.clear()
        devices.forEach { subMenu?.add(R.id.nav_devices_group, Menu.NONE, Menu.NONE, it)?.setIcon(android.R.drawable.ic_menu_slideshow)?.setCheckable(true) }
    }

    private fun showDeleteDevicesList() {
        val options = devices.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("选择要移除的设备")
            .setItems(options) { _, which ->
                val url = options[which]
                devices.remove(url)
                saveDevices()
                updateDeviceMenu()
                Toast.makeText(this, "已移除 $url", Toast.LENGTH_SHORT).show()
                val prefs = getSharedPreferences("comfyui_prefs", MODE_PRIVATE)
                if (prefs.getString("selected_device", "") == url) {
                    val next = devices.firstOrNull()
                    if (next != null) selectDevice(next) else prefs.edit().remove("selected_device").apply()
                }
            }
            .setNegativeButton("返回", null)
            .show()
    }

    private fun showAddDeviceDialog() {
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(60, 40, 60, 0) }
        val ipEt = EditText(this).apply { hint = "IP 地址" }; layout.addView(ipEt)
        val portEt = EditText(this).apply { hint = "端口"; inputType = InputType.TYPE_CLASS_NUMBER; setText("8188") }; layout.addView(portEt)
        AlertDialog.Builder(this).setTitle("添加设备").setView(layout)
            .setPositiveButton("添加") { _, _ ->
                val ip = ipEt.text.toString().trim(); var port = portEt.text.toString().trim()
                if (ip.isNotEmpty()) {
                    if (port.isEmpty()) port = "8188"
                    val url = "http://$ip:$port"
                    if (!devices.contains(url)) { devices.add(url); saveDevices(); updateDeviceMenu() }
                    selectDevice(url)
                }
            }
            .setNegativeButton("取消", null).show()
    }

    private fun selectDevice(url: String) {
        val finalUrl = if (!url.startsWith("http")) "http://$url" else url
        getSharedPreferences("comfyui_prefs", MODE_PRIVATE).edit().putString("selected_device", finalUrl).apply()
        Toast.makeText(this, "已切换: $finalUrl", Toast.LENGTH_SHORT).show()
    }

    override fun onSupportNavigateUp(): Boolean {
        val navHostFragment = supportFragmentManager.findFragmentById(R.id.nav_host_fragment_content_main) as NavHostFragment
        return navHostFragment.navController.navigateUp(appBarConfiguration) || super.onSupportNavigateUp()
    }
}
