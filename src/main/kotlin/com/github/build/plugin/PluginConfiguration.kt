import com.intellij.openapi.components.*
import com.intellij.openapi.project.Project
import org.jdom.Element

@Service(Service.Level.PROJECT)
@State(name = "BuildToolConfig", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class PluginConfiguration : PersistentStateComponent<Element> {
    data class ProjectItem(
        var value: String = "",
        var gitRepo: String = "",
        var branchServicePrefix: String = "",
        var branchService: MutableMap<String, String> = mutableMapOf(),
        var envService: MutableMap<String, String> = mutableMapOf(),
        var projectId: Long? = null,
        var apiProject: Boolean = false
    )

    var projects: MutableList<ProjectItem> = mutableListOf()
    var selectedProject: String = ""
    var targetEnvironment: String = "dev"
    
    // 构建环境列表（用于主界面环境多选），默认包含一条 dev
    var environments: MutableList<String> = mutableListOf("dev")

    // 构建环境变量配置（键格式：env.key，例如 dev.ENV=pre）
    var envVariables: MutableMap<String, String> = mutableMapOf()
    
    // GitLab配置
    var gitlabUrl: String = ""
    var gitlabToken: String = ""

    // 分支历史记录：按最近使用排序，索引 0 为最近使用
    var featureBranchHistory: MutableList<String> = mutableListOf()
    var buildBranchHistory: MutableList<String> = mutableListOf()

    override fun getState(): Element {
        return Element("config").apply {
            setAttribute("project", selectedProject)
            setAttribute("env", targetEnvironment)
            setAttribute("gitlabUrl", gitlabUrl)
            setAttribute("gitlabToken", gitlabToken)

            val envsElement = Element("environments")
            environments.forEach { env ->
                envsElement.addContent(Element("env").apply {
                    setAttribute("value", env)
                })
            }
            addContent(envsElement)

            addContent(mapToElement("envVariables", envVariables))

            val featureBranchesElement = Element("featureBranchHistory")
            featureBranchHistory.forEach { branch ->
                featureBranchesElement.addContent(Element("branch").apply {
                    setAttribute("value", branch)
                })
            }
            addContent(featureBranchesElement)

            val buildBranchesElement = Element("buildBranchHistory")
            buildBranchHistory.forEach { branch ->
                buildBranchesElement.addContent(Element("branch").apply {
                    setAttribute("value", branch)
                })
            }
            addContent(buildBranchesElement)

            val projectsElement = Element("projects")
            projects.forEach { project ->
                val projectElement = Element("project").apply {
                    setAttribute("value", project.value)
                    setAttribute("gitRepo", project.gitRepo)
                    setAttribute("branchServicePrefix", project.branchServicePrefix)
                    project.projectId?.let { setAttribute("projectId", it.toString()) }
                    setAttribute("apiProject", project.apiProject.toString())

                    addContent(mapToElement("branchService", project.branchService))
                    addContent(mapToElement("envService", project.envService))
                }
                projectsElement.addContent(projectElement)
            }
            addContent(projectsElement)
        }
    }

    private fun mapToElement(name: String, data: Map<String, String>): Element {
        val element = Element(name)
        data.forEach { (key, value) ->
            element.addContent(Element("entry").apply {
                setAttribute("key", key)
                setAttribute("value", value)
            })
        }
        return element
    }

    override fun loadState(state: Element) {
        selectedProject = state.getAttributeValue("project") ?: ""
        targetEnvironment = state.getAttributeValue("env") ?: "dev"
        gitlabUrl = state.getAttributeValue("gitlabUrl") ?: ""
        gitlabToken = state.getAttributeValue("gitlabToken") ?: ""

        environments.clear()
        val envsElement = state.getChild("environments")
        if (envsElement != null) {
            for (envElement in envsElement.getChildren("env")) {
                val value = envElement.getAttributeValue("value") ?: ""
                if (value.isNotBlank()) {
                    environments.add(value)
                }
            }
        }
        if (environments.isEmpty()) {
            environments.add("dev")
        }

        envVariables.clear()
        envVariables.putAll(elementToMap(state.getChild("envVariables")))

        featureBranchHistory.clear()
        buildBranchHistory.clear()

        val featureBranchesElement = state.getChild("featureBranchHistory")
        if (featureBranchesElement != null) {
            for (branchElement in featureBranchesElement.getChildren("branch")) {
                val value = branchElement.getAttributeValue("value") ?: ""
                if (value.isNotBlank()) {
                    featureBranchHistory.add(value)
                }
            }
        }

        val buildBranchesElement = state.getChild("buildBranchHistory")
        if (buildBranchesElement != null) {
            for (branchElement in buildBranchesElement.getChildren("branch")) {
                val value = branchElement.getAttributeValue("value") ?: ""
                if (value.isNotBlank()) {
                    buildBranchHistory.add(value)
                }
            }
        }

        projects.clear()
        val projectsElement = state.getChild("projects")
        if (projectsElement != null) {
            for (projectElement in projectsElement.getChildren("project")) {
                val branchService = elementToMap(projectElement.getChild("branchService"))
                val envService = elementToMap(projectElement.getChild("envService"))

                val item = ProjectItem(
                    value = projectElement.getAttributeValue("value") ?: "",
                    gitRepo = projectElement.getAttributeValue("gitRepo") ?: "",
                    branchServicePrefix = projectElement.getAttributeValue("branchServicePrefix") ?: "",
                    branchService = branchService.toMutableMap(),
                    envService = envService.toMutableMap(),
                    projectId = projectElement.getAttributeValue("projectId")?.toLongOrNull(),
                    apiProject = projectElement.getAttributeValue("apiProject")?.toBoolean() ?: false
                )
                projects.add(item)
            }
        }
    }

    private fun elementToMap(element: Element?): Map<String, String> {
        if (element == null) return emptyMap()
        return element.getChildren("entry").associate {
            val key = it.getAttributeValue("key") ?: ""
            val value = it.getAttributeValue("value") ?: ""
            key to value
        }.filterKeys { it.isNotEmpty() }
    }

    companion object {
        fun getInstance(project: Project): PluginConfiguration {
            return project.service()
        }
    }
}
