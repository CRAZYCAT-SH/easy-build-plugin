package com.github.build.plugin.builds

/**
 * Service数据模型
 */
data class Service(
    var value: String = "",
    var gitRepo: String = "",
    var branchServicePrefix: String = "",
    var branchService: MutableMap<String, String> = mutableMapOf(),
    var envService: MutableMap<String, String> = mutableMapOf(),
    var projectId: Long? = null
) {
    fun setBranch(branchName: String, serviceName: String): Service {
        branchService[branchName] = serviceName
        return this
    }

    fun setEnv(envName: String, serviceName: String): Service {
        envService[envName] = serviceName
        return this
    }

    fun setGit(path: String): Service {
        gitRepo = path
        return this
    }

    fun setProjectId(id: Long): Service {
        projectId = id
        return this
    }

    fun setBranchServicePrefix(prefix: String): Service {
        branchServicePrefix = prefix
        return this
    }

    fun getJobNameByBranch(type: String): String {
        val jobName = branchService[type]
        return if (jobName.isNullOrEmpty()) {
            (branchServicePrefix.ifEmpty { value }) + type
        } else {
            jobName
        }
    }

    fun getJobRefreshPath(): String {
        return "/job/" + branchServicePrefix.replaceFirst("/", "/job/") + "build?delay=0"
    }

    fun getJobNameByEnv(type: String): String? {
        return envService[type]
    }
}
