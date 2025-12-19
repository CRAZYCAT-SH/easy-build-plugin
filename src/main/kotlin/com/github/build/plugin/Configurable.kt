package com.github.build.plugin

import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBTextField
import PluginConfiguration
import com.intellij.ui.components.JBScrollPane
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import java.awt.BorderLayout
import java.awt.Component
import java.awt.GridLayout
import javax.swing.*

class Configurable(private val project: Project) : SearchableConfigurable {
    private val config = PluginConfiguration.getInstance(project)

    private val projects: MutableList<PluginConfiguration.ProjectItem> = mutableListOf()
    private val listModel = DefaultListModel<PluginConfiguration.ProjectItem>()
    private lateinit var projectList: JList<PluginConfiguration.ProjectItem>

    private lateinit var nameField: JBTextField
    private lateinit var gitRepoField: JBTextField
    private lateinit var gitRepoFieldWithBrowse: TextFieldWithBrowseButton
    private lateinit var prefixField: JBTextField
    private lateinit var branchServiceArea: JTextArea
    private lateinit var envServiceArea: JTextArea
    private lateinit var projectIdField: JBTextField
    private lateinit var apiCheckBox: JCheckBox
    
    // 构建环境配置：使用列表 + 添加/删除
    private val environments: MutableList<String> = mutableListOf()
    private val envListModel = DefaultListModel<String>()
    private lateinit var envList: JList<String>
    private lateinit var envInputField: JBTextField
    private lateinit var envVariablesArea: JTextArea
    private val envVariablesByEnv: MutableMap<String, MutableMap<String, String>> = mutableMapOf()
    private var currentEnvForVars: String? = null
    
    // GitLab配置字段
    private lateinit var gitlabUrlField: JBTextField
    private lateinit var gitlabTokenField: JBTextField

    private var currentIndex: Int = -1

    override fun createComponent(): JComponent {
        projects.clear()
        projects.addAll(config.projects.map { it.copy() })

        listModel.removeAllElements()
        projects.forEach { listModel.addElement(it) }

        projectList = JList(listModel).apply {
            selectionMode = ListSelectionModel.SINGLE_SELECTION
            cellRenderer = object : DefaultListCellRenderer() {
                override fun getListCellRendererComponent(
                    list: JList<*>,
                    value: Any?,
                    index: Int,
                    isSelected: Boolean,
                    cellHasFocus: Boolean
                ): Component {
                    val comp = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
                    if (value is PluginConfiguration.ProjectItem) {
                        text = if (value.value.isNotBlank()) value.value else "<未命名项目>"
                    }
                    return comp
                }
            }
            addListSelectionListener { e ->
                if (!e.valueIsAdjusting) {
                    updateCurrentProjectFromFields()
                    currentIndex = selectedIndex
                    updateFieldsFromCurrentProject()
                }
            }
        }

        nameField = JBTextField()
        gitRepoField = JBTextField()
        gitRepoFieldWithBrowse = TextFieldWithBrowseButton(gitRepoField).apply {
            addBrowseFolderListener(
                "选择 Git 仓库目录",
                "请选择本地 Git 仓库所在的文件夹",
                project,
                FileChooserDescriptorFactory.createSingleFolderDescriptor()
            )
        }
        prefixField = JBTextField()
        branchServiceArea = JTextArea(5, 30)
        envServiceArea = JTextArea(5, 30)
        projectIdField = JBTextField()
        apiCheckBox = JCheckBox("API 项目")
        
        // 构建环境配置初始化（使用列表）
        environments.clear()
        if (config.environments.isNotEmpty()) {
            environments.addAll(config.environments)
        } else {
            environments.add("dev")
        }
        envListModel.removeAllElements()
        environments.forEach { envListModel.addElement(it) }
        envList = JList(envListModel).apply {
            selectionMode = ListSelectionModel.SINGLE_SELECTION
            visibleRowCount = 4
        }
        envInputField = JBTextField()
        envVariablesArea = JTextArea(4, 20)
        
        // 按环境拆分变量配置
        envVariablesByEnv.clear()
        config.envVariables.forEach { (fullKey, value) ->
            val dotIndex = fullKey.indexOf('.')
            if (dotIndex > 0 && dotIndex < fullKey.length - 1) {
                val env = fullKey.substring(0, dotIndex)
                val varKey = fullKey.substring(dotIndex + 1)
                if (env.isNotBlank() && varKey.isNotBlank()) {
                    val map = envVariablesByEnv.getOrPut(env) { mutableMapOf() }
                    map[varKey] = value
                }
            }
        }
        
        envList.addListSelectionListener { e ->
            if (e.valueIsAdjusting) return@addListSelectionListener
            saveCurrentEnvVariables()
            currentEnvForVars = envList.selectedValue
            val vars = envVariablesByEnv[currentEnvForVars] ?: mutableMapOf()
            envVariablesArea.text = mapToMultiline(vars)
        }
        if (environments.isNotEmpty()) {
            envList.selectedIndex = 0
        }
        
        // GitLab配置字段初始化
        gitlabUrlField = JBTextField()
        gitlabUrlField.text = config.gitlabUrl
        gitlabTokenField = JBTextField()
        gitlabTokenField.text = config.gitlabToken

        val addButton = JButton("添加")
        val removeButton = JButton("删除")

        addButton.addActionListener {
            updateCurrentProjectFromFields()
            val newItem = PluginConfiguration.ProjectItem(value = "新项目")
            projects.add(newItem)
            listModel.addElement(newItem)
            val newIndex = listModel.size() - 1
            projectList.selectedIndex = newIndex
        }

        removeButton.addActionListener {
            val index = projectList.selectedIndex
            if (index >= 0) {
                projects.removeAt(index)
                listModel.remove(index)
                currentIndex = -1
                clearFields()
            }
        }

        // 左侧项目列表面板
        val listPanel = JPanel(BorderLayout()).apply {
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createTitledBorder("项目列表"),
                BorderFactory.createEmptyBorder(5, 5, 5, 5)
            )
            preferredSize = java.awt.Dimension(250, 300)
            
            add(JBScrollPane(projectList), BorderLayout.CENTER)
            
            val buttonPanel = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.X_AXIS)
                border = BorderFactory.createEmptyBorder(5, 0, 0, 0)
                add(addButton)
                add(Box.createHorizontalStrut(5))
                add(removeButton)
                add(Box.createHorizontalGlue())
            }
            add(buttonPanel, BorderLayout.SOUTH)
        }

        // 右侧详情面板
        val detailPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = BorderFactory.createEmptyBorder(0, 10, 0, 0)
            
            // 项目信息区域
            val projectInfoPanel = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                border = BorderFactory.createCompoundBorder(
                    BorderFactory.createTitledBorder("项目信息"),
                    BorderFactory.createEmptyBorder(10, 10, 10, 10)
                )
                alignmentX = Component.LEFT_ALIGNMENT
                
                add(createFieldPanel("项目名称", nameField))
                add(Box.createVerticalStrut(8))
                add(createFieldPanel("Git 仓库地址（本地路径）", gitRepoFieldWithBrowse))
                add(Box.createVerticalStrut(8))
                add(createFieldPanel("项目 ID", projectIdField))
                add(Box.createVerticalStrut(8))
                add(apiCheckBox)
            }
            add(projectInfoPanel)
        }

        // 顶部 GitLab + 环境 配置区域
        val gitlabPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createTitledBorder("GitLab 配置"),
                BorderFactory.createEmptyBorder(10, 10, 10, 10)
            )

            add(createFieldPanel("GitLab URL", gitlabUrlField))
            add(Box.createHorizontalStrut(16))
            add(createFieldPanel("GitLab Token", gitlabTokenField))
        }.apply {
            alignmentX = Component.LEFT_ALIGNMENT
            maximumSize = java.awt.Dimension(Int.MAX_VALUE, preferredSize.height)
        }

        val envConfigPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createTitledBorder("构建环境配置"),
                BorderFactory.createEmptyBorder(10, 10, 10, 10)
            )

            add(JLabel("构建环境列表（例如 dev / test / prod）").apply {
                alignmentX = Component.LEFT_ALIGNMENT
            })
            add(Box.createVerticalStrut(5))

            add(JPanel().apply {
                layout = BoxLayout(this, BoxLayout.X_AXIS)
                alignmentX = Component.LEFT_ALIGNMENT
                add(envInputField.apply {
                    maximumSize = java.awt.Dimension(Int.MAX_VALUE, preferredSize.height)
                    alignmentX = Component.LEFT_ALIGNMENT
                })
                add(Box.createHorizontalStrut(5))
                val addEnvButton = JButton("添加").apply {
                    addActionListener {
                        val value = envInputField.text.trim()
                        if (value.isNotEmpty() && !environments.contains(value)) {
                            environments.add(value)
                            envListModel.addElement(value)
                            envInputField.text = ""
                        }
                    }
                }
                val removeEnvButton = JButton("删除选中").apply {
                    addActionListener {
                        val index = envList.selectedIndex
                        if (index >= 0 && index < environments.size) {
                            environments.removeAt(index)
                            envListModel.remove(index)
                        }
                    }
                }
                add(addEnvButton)
                add(Box.createHorizontalStrut(5))
                add(removeEnvButton)
            })
            add(Box.createVerticalStrut(8))
            add(JBScrollPane(envList).apply {
                maximumSize = java.awt.Dimension(Int.MAX_VALUE, 80)
                alignmentX = Component.LEFT_ALIGNMENT
            })
            add(Box.createVerticalStrut(8))
            add(JLabel("环境变量（当前环境的 KEY=VALUE，每行一条；默认会传递 ENV=<当前环境>，如未配置则使用选中的环境名）").apply {
                alignmentX = Component.LEFT_ALIGNMENT
            })
            add(Box.createVerticalStrut(5))
            add(JBScrollPane(envVariablesArea).apply {
                maximumSize = java.awt.Dimension(Int.MAX_VALUE, 80)
                alignmentX = Component.LEFT_ALIGNMENT
            })
        }.apply {
            alignmentX = Component.LEFT_ALIGNMENT
            maximumSize = java.awt.Dimension(Int.MAX_VALUE, preferredSize.height)
        }

        val gitlabConfigPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = BorderFactory.createEmptyBorder(0, 0, 10, 0)
            add(gitlabPanel)
            add(Box.createVerticalStrut(8))
            add(envConfigPanel)
        }

        val rootPanel = JPanel(BorderLayout()).apply {
            border = BorderFactory.createEmptyBorder(10, 10, 10, 10)
            add(gitlabConfigPanel, BorderLayout.NORTH)
            add(JPanel(BorderLayout()).apply {
                add(listPanel, BorderLayout.WEST)
                val detailScrollPane = JBScrollPane(detailPanel).apply {
                    border = BorderFactory.createEmptyBorder()
                    minimumSize = java.awt.Dimension(0, listPanel.preferredSize.height)
                    preferredSize = java.awt.Dimension(0, listPanel.preferredSize.height)
                }
                add(detailScrollPane, BorderLayout.CENTER)
            }, BorderLayout.CENTER)
        }

        if (projects.isNotEmpty()) {
            projectList.selectedIndex = 0
        }

        return rootPanel
    }
    
    // 辅助方法：创建带标签的输入组件
    private fun createFieldPanel(label: String, field: JComponent): JPanel {
        return JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            alignmentX = Component.LEFT_ALIGNMENT
            
            add(JLabel(label).apply {
                alignmentX = Component.LEFT_ALIGNMENT
            })
            add(Box.createVerticalStrut(3))
            add(field.apply {
                maximumSize = java.awt.Dimension(Int.MAX_VALUE, preferredSize.height)
                alignmentX = Component.LEFT_ALIGNMENT
            })
        }
    }
    
    // 辅助方法：创建带标签的文本区域
    private fun createTextAreaPanel(label: String, textArea: JTextArea): JPanel {
        return JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            alignmentX = Component.LEFT_ALIGNMENT
            
            add(JLabel(label).apply {
                alignmentX = Component.LEFT_ALIGNMENT
            })
            add(Box.createVerticalStrut(3))
            add(JBScrollPane(textArea).apply {
                maximumSize = java.awt.Dimension(Int.MAX_VALUE, 120)
                alignmentX = Component.LEFT_ALIGNMENT
            })
        }
    }

    private fun updateCurrentProjectFromFields() {
        if (currentIndex < 0 || currentIndex >= projects.size) return
        val item = projects[currentIndex]
        item.value = nameField.text
        item.gitRepo = gitRepoField.text
        // 服务映射相关配置已废弃，这里统一置空
        item.branchServicePrefix = ""
        item.branchService = mutableMapOf()
        item.envService = mutableMapOf()
        item.projectId = projectIdField.text.toLongOrNull()
        item.apiProject = apiCheckBox.isSelected
        listModel.set(currentIndex, item)
    }

    private fun updateFieldsFromCurrentProject() {
        if (currentIndex < 0 || currentIndex >= projects.size) {
            clearFields()
            return
        }
        val item = projects[currentIndex]
        nameField.text = item.value
        gitRepoField.text = item.gitRepo
        // 服务映射相关字段不再展示
        projectIdField.text = item.projectId?.toString() ?: ""
        apiCheckBox.isSelected = item.apiProject
    }

    private fun clearFields() {
        nameField.text = ""
        gitRepoField.text = ""
        // 服务映射相关字段不再使用
        projectIdField.text = ""
        apiCheckBox.isSelected = false
    }

    private fun mapToMultiline(map: Map<String, String>): String {
        return map.entries.joinToString("\n") { "${it.key}=${it.value}" }
    }

    private fun textToMap(text: String): MutableMap<String, String> {
        return text.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && it.contains('=') }
            .associate {
                val index = it.indexOf('=')
                val key = it.substring(0, index).trim()
                val value = it.substring(index + 1).trim()
                key to value
            }
            .toMutableMap()
    }

    private fun normalizedEnvironments(list: List<String>): MutableList<String> {
        val envs = list.map { it.trim() }
            .filter { it.isNotEmpty() }
            .toMutableList()
        if (envs.isEmpty()) {
            envs.add("dev")
        }
        return envs
    }

    private fun saveCurrentEnvVariables() {
        val env = currentEnvForVars ?: return
        if (!environments.contains(env)) return
        envVariablesByEnv[env] = textToMap(envVariablesArea.text)
    }

    private fun mergeEnvVariables(): MutableMap<String, String> {
        val result = mutableMapOf<String, String>()
        envVariablesByEnv.forEach { (env, vars) ->
            vars.forEach { (key, value) ->
                if (env.isNotBlank() && key.isNotBlank()) {
                    result["$env.$key"] = value
                }
            }
        }
        return result
    }

    override fun isModified(): Boolean {
        updateCurrentProjectFromFields()
        saveCurrentEnvVariables()
        return projects != config.projects ||
                gitlabUrlField.text != config.gitlabUrl ||
                gitlabTokenField.text != config.gitlabToken ||
                normalizedEnvironments(environments) != normalizedEnvironments(config.environments) ||
                mergeEnvVariables() != config.envVariables
    }

    override fun apply() {
        updateCurrentProjectFromFields()
        saveCurrentEnvVariables()
        config.projects = projects.map { it.copy() }.toMutableList()
        config.gitlabUrl = gitlabUrlField.text
        config.gitlabToken = gitlabTokenField.text
        config.environments = normalizedEnvironments(environments)
        config.envVariables = mergeEnvVariables()
        ToolWindow.refreshProjectList(config)
    }

    override fun getId() = "preferences.BuildTool"
    override fun getDisplayName() = "Build Tool Settings"
}
