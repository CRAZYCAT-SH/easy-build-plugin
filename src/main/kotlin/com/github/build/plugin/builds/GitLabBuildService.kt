package com.github.build.plugin.builds

import PluginConfiguration
import com.intellij.execution.ui.ConsoleView
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.openapi.project.Project
import java.util.concurrent.*

/**
 * GitLab构建工具
 */
class GitLabBuildService(
    private val project: Project,
    private val console: ConsoleView,
    private val gitlabUrl: String,
    private val gitlabToken: String
) {
    private val executor = ThreadPoolExecutor(
        16, 16, 30, TimeUnit.SECONDS,
        LinkedBlockingQueue(2000),
        Executors.defaultThreadFactory(),
        ThreadPoolExecutor.CallerRunsPolicy()
    )

    private val gitLabTrigger = GitLabPipelineTrigger(gitlabUrl, gitlabToken, console)
    private val gitUtil = GitOperations(console)

    /**
     * 手动构建分支
     */
    fun buildByManual(
        branch: String,
        apiServices: List<Service>,
        services: List<Service>,
        vararg env: String
    ) {
        console.print("============ 构建任务开始 ===========================\n", ConsoleViewContentType.SYSTEM_OUTPUT)
        console.print("构建分支: $branch\n", ConsoleViewContentType.NORMAL_OUTPUT)
        console.print("API服务: ${apiServices.map { it.value }}\n", ConsoleViewContentType.NORMAL_OUTPUT)
        console.print("普通服务: ${services.map { it.value }}\n", ConsoleViewContentType.NORMAL_OUTPUT)
        console.print("目标环境: ${env.joinToString(", ")}\n", ConsoleViewContentType.NORMAL_OUTPUT)
        console.print("===================================================\n", ConsoleViewContentType.SYSTEM_OUTPUT)

        // 构建API服务
        if (apiServices.isNotEmpty()) {
            console.print("\n[步骤1] 开始构建 API 服务...\n", ConsoleViewContentType.SYSTEM_OUTPUT)
            for (apiService in apiServices) {
                val projectId = apiService.projectId
                if (projectId == null) {
                    console.print("  ✗ ${apiService.value}: 缺少 projectId\n", ConsoleViewContentType.ERROR_OUTPUT)
                    throw RuntimeException("API服务 ${apiService.value} 缺少 projectId")
                }
                
                console.print("  → 构建 ${apiService.value}...\n", ConsoleViewContentType.NORMAL_OUTPUT)
                if (!gitLabTrigger.build(branch, projectId, null)) {
                    console.print("  ✗ ${apiService.value} 构建失败\n", ConsoleViewContentType.ERROR_OUTPUT)
                    throw RuntimeException("API服务 ${apiService.value} 构建失败")
                }
                console.print("  ✓ ${apiService.value} 构建成功\n", ConsoleViewContentType.NORMAL_OUTPUT)
            }
        }

        // 并行构建普通服务
        if (services.isNotEmpty()) {
            console.print("\n[步骤2] 开始并行构建普通服务...\n", ConsoleViewContentType.SYSTEM_OUTPUT)
            val result = mutableMapOf<String, Future<Boolean>>()

            for (service in services) {
                val projectId = service.projectId
                if (projectId == null) {
                    console.print("  ✗ ${service.value}: 缺少 projectId，跳过\n", ConsoleViewContentType.ERROR_OUTPUT)
                    continue
                }

                for (e in env) {
                    val taskName = "${service.value} >>> $e"
                    console.print("  → 提交任务: $taskName\n", ConsoleViewContentType.NORMAL_OUTPUT)
                    val extraVars = resolveEnvVariables(e)
                    val future = executor.submit<Boolean> {
                        gitLabTrigger.build(branch, projectId, e, extraVars)
                    }
                    result[taskName] = future
                }
            }

            // 等待所有任务完成
            console.print("\n[步骤3] 等待所有构建任务完成...\n", ConsoleViewContentType.SYSTEM_OUTPUT)
            result.forEach { (taskName, future) ->
                try {
                    if (future.get()) {
                        console.print("  ✓ $taskName 构建成功\n", ConsoleViewContentType.NORMAL_OUTPUT)
                    } else {
                        console.print("  ✗ $taskName 构建失败\n", ConsoleViewContentType.ERROR_OUTPUT)
                    }
                } catch (e: Exception) {
                    console.print("  ✗ $taskName 执行异常: ${e.message}\n", ConsoleViewContentType.ERROR_OUTPUT)
                    e.printStackTrace()
                }
            }
        }

        console.print("\n============ 构建任务结束 ===========================\n", ConsoleViewContentType.SYSTEM_OUTPUT)
    }

    /**
     * 自动合并Git代码并构建
     */
    fun buildByGit(
        featBranch: String,
        buildBranch: String,
        apiServices: List<Service>,
        services: List<Service>,
        vararg env: String
    ) {
        try {
            console.print("============ Git 合并构建开始 ======================\n", ConsoleViewContentType.SYSTEM_OUTPUT)
            console.print("功能分支: $featBranch\n", ConsoleViewContentType.NORMAL_OUTPUT)
            console.print("构建分支: $buildBranch\n", ConsoleViewContentType.NORMAL_OUTPUT)

            val mergedApiServices = mergeAndConfirmService(featBranch, buildBranch, apiServices)
            val mergedServices = mergeAndConfirmService(featBranch, buildBranch, services)

            buildByManual(buildBranch, mergedApiServices, mergedServices, *env)
        } catch (e: Exception) {
            console.print("Git 合并构建失败: ${e.message}\n", ConsoleViewContentType.ERROR_OUTPUT)
            e.printStackTrace()
        }
    }

    /**
     * 自动合并Git代码，确认实际需构建的服务
     */
    private fun mergeAndConfirmService(
        featBranch: String?,
        buildBranch: String,
        services: List<Service>
    ): List<Service> {
        val realNeed = mutableListOf<Service>()

        for (service in services) {
            val projectId = service.projectId
            if (projectId == null) {
                console.print("  ! ${service.value}: 缺少 projectId，跳过合并检查\n", ConsoleViewContentType.ERROR_OUTPUT)
                continue
            }

            // 如果有功能分支，执行合并
            if (!featBranch.isNullOrEmpty()) {
                console.print("  → 合并 ${service.value}: $featBranch -> $buildBranch\n", ConsoleViewContentType.NORMAL_OUTPUT)
                gitUtil.mergeAndPush(service, featBranch, buildBranch)
            }

            // 检查是否需要构建
            val lastUpdateTime = gitUtil.getLastUpdateTime(service, buildBranch)
            val lastBuildTime = try {
                gitLabTrigger.getLastBuildTime(service, buildBranch)
            } catch (e: Exception) {
                console.print("  ✗ ${service.value}: 获取构建时间失败，跳过构建\n", ConsoleViewContentType.ERROR_OUTPUT)
                continue
            }

            if (lastBuildTime == null || (lastUpdateTime != null && lastUpdateTime.after(lastBuildTime))) {
                realNeed.add(service)
                console.print("  ✓ ${service.value}: 需要构建（代码已更新）\n", ConsoleViewContentType.NORMAL_OUTPUT)
            } else {
                console.print("  - ${service.value}: 无需构建（代码未更新）\n", ConsoleViewContentType.NORMAL_OUTPUT)
            }
        }

        return realNeed
    }

    private fun resolveEnvVariables(env: String): Map<String, String> {
        val config = PluginConfiguration.getInstance(project)
        val result = mutableMapOf<String, String>()
        config.envVariables.forEach { (key, value) ->
            val prefix = "$env."
            if (key.startsWith(prefix) && key.length > prefix.length) {
                val varName = key.substring(prefix.length)
                if (varName.isNotBlank()) {
                    result[varName] = value
                }
            }
        }
        return result
    }

    fun shutdown() {
        executor.shutdown()
    }
}
