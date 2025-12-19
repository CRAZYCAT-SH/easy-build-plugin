package com.github.build.plugin

import PluginConfiguration
import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.execution.ui.ConsoleView
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.content.ContentFactory
import com.github.build.plugin.builds.Service
import com.github.build.plugin.builds.GitLabBuildService
import java.awt.BorderLayout
import java.lang.ref.WeakReference
import javax.swing.*

class ToolWindow : ToolWindowFactory {

    companion object {
        private var projectListPanelRef: WeakReference<JPanel>? = null

        fun refreshProjectList(config: PluginConfiguration) {
            val projectPanel = projectListPanelRef?.get() ?: return

            projectPanel.removeAll()

            val projectItems: List<PluginConfiguration.ProjectItem> = config.projects
            projectItems.forEach { item ->
                val baseName = if (item.value.isNotBlank()) item.value else "<未命名项目>"
                val labelText = if (item.apiProject) "$baseName [API]" else baseName
                val checkBox = JCheckBox(labelText)
                if (item.value == config.selectedProject) {
                    checkBox.isSelected = true
                }
                projectPanel.add(checkBox)
            }

            projectPanel.revalidate()
            projectPanel.repaint()
        }
    }

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val config = PluginConfiguration.getInstance(project)
        val panel = JPanel(BorderLayout()).apply {
            border = BorderFactory.createEmptyBorder(8, 8, 8, 8)
        }

        // 控制台视图
        val consoleView = project.consoleView().apply {
            clear()
            print("Ready for build...\n", ConsoleViewContentType.SYSTEM_OUTPUT)
        }

        // 主控制面板
        val controlPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
        }

        // 项目与环境多选（复选框）
        val projectItems: List<PluginConfiguration.ProjectItem> = config.projects

        val projectCheckBoxes = mutableListOf<JCheckBox>()
        projectItems.forEach { item ->
            val baseName = if (item.value.isNotBlank()) item.value else "<未命名项目>"
            val labelText = if (item.apiProject) "$baseName [API]" else baseName
            val checkBox = JCheckBox(labelText)
            // 默认选中原来的单选项目
            if (item.value == config.selectedProject) {
                checkBox.isSelected = true
            }
            projectCheckBoxes.add(checkBox)
        }

        // 环境列表从配置中读取，构建时通过弹窗选择，不在主界面展示

        val projectSelectAllCheckBox = JCheckBox("全选").apply {
            addActionListener {
                val selected = isSelected
                val panel = projectListPanelRef?.get() ?: return@addActionListener
                for (i in 0 until panel.componentCount) {
                    (panel.getComponent(i) as? JCheckBox)?.isSelected = selected
                }
            }
        }

        val projectListPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            projectCheckBoxes.forEach { checkBox ->
                add(checkBox)
            }
        }
        projectListPanelRef = WeakReference(projectListPanel)

        // 环境选择改为构建时弹窗，不在主界面展示


        val selectionPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createTitledBorder("项目选择"),
                BorderFactory.createEmptyBorder(5, 5, 5, 5)
            )
            
            // 项目选择
            add(JPanel(BorderLayout()).apply {
                add(JLabel("项目"), BorderLayout.WEST)
                add(projectSelectAllCheckBox, BorderLayout.EAST)
            })
            add(Box.createVerticalStrut(3))
            add(JBScrollPane(projectListPanel).apply {
                preferredSize = java.awt.Dimension(200, 120)
            })
        }

        // 功能分支 & 构建分支（支持历史记录）
        val featureBranchHistory = config.featureBranchHistory
        val buildBranchHistory = config.buildBranchHistory

        val featureDeleteItemText = "<删除当前记录>"
        val buildDeleteItemText = "<删除当前记录>"

        fun createFeatureBranchModel(): DefaultComboBoxModel<String> {
            val items = mutableListOf<String>()
            items.addAll(featureBranchHistory)
            if (featureBranchHistory.isNotEmpty()) {
                items.add(featureDeleteItemText)
            }
            return DefaultComboBoxModel(items.toTypedArray())
        }

        fun createBuildBranchModel(): DefaultComboBoxModel<String> {
            val items = mutableListOf<String>()
            items.addAll(buildBranchHistory)
            if (buildBranchHistory.isNotEmpty()) {
                items.add(buildDeleteItemText)
            }
            return DefaultComboBoxModel(items.toTypedArray())
        }

        val featureBranchField = ComboBox(createFeatureBranchModel()).apply {
            isEditable = true
        }

        val buildBranchField = ComboBox(createBuildBranchModel()).apply {
            isEditable = true
        }

        val branchPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createTitledBorder("分支配置"),
                BorderFactory.createEmptyBorder(5, 8, 5, 8)
            )
            
            add(JPanel().apply {
                layout = BoxLayout(this, BoxLayout.X_AXIS)
                add(JLabel("功能分支:"))
                add(Box.createHorizontalStrut(5))
                add(featureBranchField)
            })
            add(Box.createVerticalStrut(8))
            add(JPanel().apply {
                layout = BoxLayout(this, BoxLayout.X_AXIS)
                add(JLabel("构建分支:"))
                add(Box.createHorizontalStrut(5))
                add(buildBranchField)
            })
        }

        // 构建按钮：直接构建 / 合并动态构建
        val directBuildButton = JButton("直接构建")
        val mergeDynamicBuildButton = JButton("合并动态构建")

        fun selectedProjects(): List<String> {
            val currentConfig = PluginConfiguration.getInstance(project)
            val items: List<PluginConfiguration.ProjectItem> = currentConfig.projects
            val panel = projectListPanelRef?.get()

            if (panel == null || items.isEmpty()) {
                return if (items.isNotEmpty()) listOf(items[0].value) else emptyList()
            }

            val max = items.size.coerceAtMost(panel.componentCount)
            val selected = mutableListOf<String>()
            for (i in 0 until max) {
                val checkBox = panel.getComponent(i) as? JCheckBox ?: continue
                if (checkBox.isSelected) {
                    selected.add(items[i].value)
                }
            }

            return if (selected.isNotEmpty()) {
                selected
            } else {
                listOf(items[0].value)
            }
        }

        fun selectedEnvs(): List<String>? {
            val currentConfig = PluginConfiguration.getInstance(project)
            val envs: List<String> = if (currentConfig.environments.isNotEmpty()) currentConfig.environments else listOf("dev")

            val dialogPanel = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
            }

            val checkBoxes = mutableListOf<JCheckBox>()
            val selectAllCheckBox = JCheckBox("全选")
            dialogPanel.add(selectAllCheckBox)
            dialogPanel.add(Box.createVerticalStrut(5))

            val defaultEnv = currentConfig.targetEnvironment.ifEmpty { "dev" }
            envs.forEach { env ->
                val cb = JCheckBox(env)
                if (env == defaultEnv) {
                    cb.isSelected = true
                }
                checkBoxes.add(cb)
                dialogPanel.add(cb)
            }

            selectAllCheckBox.addActionListener {
                val selected = selectAllCheckBox.isSelected
                checkBoxes.forEach { it.isSelected = selected }
            }

            val result = JOptionPane.showConfirmDialog(
                null,
                dialogPanel,
                "选择构建环境",
                JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE
            )

            if (result != JOptionPane.OK_OPTION) {
                return null
            }

            val selected = checkBoxes.filter { it.isSelected }.map { it.text }
            return if (selected.isNotEmpty()) {
                selected
            } else {
                listOf(defaultEnv)
            }
        }

        fun updateBranchHistory(history: MutableList<String>, value: String, maxSize: Int = 20) {
            if (value.isBlank()) return
            history.remove(value)
            history.add(0, value)
            if (history.size > maxSize) {
                history.subList(maxSize, history.size).clear()
            }
        }

        fun runBuild(buildAction: String) {
            val projectsSelected = selectedProjects()
            val envsSelected = selectedEnvs() ?: return

            if (projectsSelected.isEmpty() || envsSelected.isEmpty()) {
                consoleView.clear()
                consoleView.print("No projects or environments selected\n", ConsoleViewContentType.ERROR_OUTPUT)
                return
            }

            // 记住第一个选中的项目和环境，兼容原有单选配置
            config.selectedProject = projectsSelected.first()
            config.targetEnvironment = envsSelected.first()

            val featureBranch = (featureBranchField.editor.item as? String)?.trim().orEmpty()
            val buildBranch = (buildBranchField.editor.item as? String)?.trim().orEmpty()

            // 构建分支必填校验
            if (buildAction == "direct") {
                if (buildBranch.isBlank()) {
                    consoleView.clear()
                    consoleView.print("构建分支不能为空（直接构建）\n", ConsoleViewContentType.ERROR_OUTPUT)
                    return
                }
            } else if (buildAction == "merge") {
                if (featureBranch.isBlank() || buildBranch.isBlank()) {
                    consoleView.clear()
                    consoleView.print("功能分支和构建分支均不能为空（合并动态构建）\n", ConsoleViewContentType.ERROR_OUTPUT)
                    return
                }
            }

            // 更新分支历史记录（最近使用优先）
            updateBranchHistory(featureBranchHistory, featureBranch)
            updateBranchHistory(buildBranchHistory, buildBranch)

            featureBranchField.model = createFeatureBranchModel()
            buildBranchField.model = createBuildBranchModel()

            consoleView.clear()
            
            // 禁用构建按钮
            directBuildButton.isEnabled = false
            mergeDynamicBuildButton.isEnabled = false
            
            executeBuildTask(
                project,
                projectsSelected,
                envsSelected,
                featureBranch,
                buildBranch,
                buildAction,
                consoleView,
                directBuildButton,
                mergeDynamicBuildButton
            )
        }

        directBuildButton.addActionListener { runBuild("direct") }
        mergeDynamicBuildButton.addActionListener { runBuild("merge") }

        var lastSelectedFeatureBranch: String? = featureBranchHistory.firstOrNull()
        var lastSelectedBuildBranch: String? = buildBranchHistory.firstOrNull()

        featureBranchField.addItemListener { e ->
            if (e.stateChange == java.awt.event.ItemEvent.SELECTED) {
                val item = e.item as? String ?: return@addItemListener
                if (item == featureDeleteItemText) {
                    val target = lastSelectedFeatureBranch?.trim().orEmpty()
                    if (target.isNotBlank() && featureBranchHistory.remove(target)) {
                        featureBranchField.model = createFeatureBranchModel()
                        featureBranchField.editor.item = ""
                        lastSelectedFeatureBranch = featureBranchHistory.firstOrNull()
                    }
                } else {
                    lastSelectedFeatureBranch = item
                }
            }
        }

        buildBranchField.addItemListener { e ->
            if (e.stateChange == java.awt.event.ItemEvent.SELECTED) {
                val item = e.item as? String ?: return@addItemListener
                if (item == buildDeleteItemText) {
                    val target = lastSelectedBuildBranch?.trim().orEmpty()
                    if (target.isNotBlank() && buildBranchHistory.remove(target)) {
                        buildBranchField.model = createBuildBranchModel()
                        buildBranchField.editor.item = ""
                        lastSelectedBuildBranch = buildBranchHistory.firstOrNull()
                    }
                } else {
                    lastSelectedBuildBranch = item
                }
            }
        }

        val buttonPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            border = BorderFactory.createEmptyBorder(5, 0, 5, 0)
            add(Box.createHorizontalGlue())
            add(directBuildButton)
            add(Box.createHorizontalStrut(10))
            add(mergeDynamicBuildButton)
            add(Box.createHorizontalGlue())
        }

        controlPanel.add(selectionPanel)
        controlPanel.add(Box.createVerticalStrut(8))
        controlPanel.add(branchPanel)
        controlPanel.add(Box.createVerticalStrut(5))
        controlPanel.add(buttonPanel)
        controlPanel.add(Box.createVerticalStrut(5))

        // 控制台区域（包含控制按钮和控制台视图）
        val consolePanel = JPanel(BorderLayout()).apply {
            border = BorderFactory.createTitledBorder("构建输出")
        }
        
        // 控制台滚动面板
        val consoleScrollPane = JBScrollPane(consoleView.component)
        
        // 控制台工具按钮
        val consoleToolbar = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            border = BorderFactory.createEmptyBorder(5, 8, 5, 8)
            background = UIManager.getColor("Panel.background")
            
            // 清空按钮
            val clearButton = JButton("清空").apply {
                toolTipText = "清空控制台内容"
                addActionListener {
                    consoleView.clear()
                }
            }
            add(clearButton)
            add(Box.createHorizontalStrut(5))
            
            // 滚动到最新位置按钮
            val scrollToEndButton = JButton("滚动到底部").apply {
                toolTipText = "滚动到控制台最新内容"
                addActionListener {
                    val bar = consoleScrollPane.verticalScrollBar
                    bar.value = bar.maximum
                }
            }
            add(scrollToEndButton)
            
            add(Box.createHorizontalGlue())
        }
        
        consolePanel.add(consoleToolbar, BorderLayout.NORTH)
        consolePanel.add(consoleScrollPane, BorderLayout.CENTER)

        panel.add(controlPanel, BorderLayout.NORTH)
        panel.add(consolePanel, BorderLayout.CENTER)

        val content = ContentFactory.getInstance().createContent(panel, "", false)
        toolWindow.contentManager.addContent(content)
    }

    private fun Project.consoleView(): ConsoleView {
        return TextConsoleBuilderFactory.getInstance()
            .createBuilder(this)
            .console
    }


    private fun executeBuildTask(
        project: Project,
        projectNames: List<String>,
        envs: List<String>,
        featureBranch: String,
        buildBranch: String,
        buildAction: String,
        console: ConsoleView,
        directBuildButton: JButton,
        mergeDynamicBuildButton: JButton
    ) {
        Thread {
            try {
                val config = PluginConfiguration.getInstance(project)

                    // 从配置构造Service对象（只处理选中的项目）
                    val apiServices = mutableListOf<Service>()
                    val services = mutableListOf<Service>()

                    config.projects.forEach { item ->
                        // 只处理用户选中的项目
                        if (!projectNames.contains(item.value)) return@forEach
                        if (item.value.isNullOrBlank()) return@forEach

                        val service = Service(
                            value = item.value,
                            gitRepo = item.gitRepo,
                            branchServicePrefix = item.branchServicePrefix,
                            branchService = item.branchService.toMutableMap(),
                            envService = item.envService.toMutableMap(),
                            projectId = item.projectId
                        )

                        if (item.apiProject) {
                            apiServices.add(service)
                        } else {
                            services.add(service)
                        }
                    }

                    if (apiServices.isEmpty() && services.isEmpty()) {
                        console.print("No mapped services found for selected projects\n", ConsoleViewContentType.ERROR_OUTPUT)
                        return@Thread
                    }

                    // 从配置读取GitLab参数
                    val gitlabUrl = config.gitlabUrl.ifEmpty { "" }
                    val gitlabToken = config.gitlabToken

                    // 创建构建服务
                    val buildService = GitLabBuildService(
                        project,
                        console,
                        gitlabUrl,
                        gitlabToken
                    )

                    console.print(
                        "\n>>> 开始构建: action=$buildAction, envs=${envs.joinToString(", ")}\n",
                        ConsoleViewContentType.SYSTEM_OUTPUT
                    )

                    if (buildAction == "direct") {
                        // 直接构建，envs 整体传入，由构建服务内部按环境分发
                        buildService.buildByManual(
                            buildBranch,
                            apiServices,
                            services,
                            *envs.toTypedArray()
                        )
                    } else {
                        // 合并动态构建，envs 整体传入
                        buildService.buildByGit(
                            featureBranch,
                            buildBranch,
                            apiServices,
                            services,
                            *envs.toTypedArray()
                        )
                    }

                    buildService.shutdown()
                } catch (e: Exception) {
                    console.print("GitLab构建失败: ${e.message}\n", ConsoleViewContentType.ERROR_OUTPUT)
                    e.printStackTrace()
                } finally {
                    // 构建完成后，在UI线程重新启用按钮
                    SwingUtilities.invokeLater {
                        directBuildButton.isEnabled = true
                        mergeDynamicBuildButton.isEnabled = true
                    }
                }
        }.start()
    }
}

