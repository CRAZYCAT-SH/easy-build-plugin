package com.github.build.plugin.builds

import com.intellij.execution.ui.ConsoleView
import com.intellij.execution.ui.ConsoleViewContentType
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.json.JSONArray

/**
 * GitLab Pipeline 触发器
 * 使用HTTP API直接调用GitLab
 */
class GitLabPipelineTrigger(
    private val gitlabUrl: String,
    private val gitlabToken: String,
    private val console: ConsoleView
) {
    private val baseApiUrl = gitlabUrl.trimEnd('/') + "/api/v4"

    /**
     * 触发构建
     */
    fun build(branch: String, projectId: Long, env: String?, extraVariables: Map<String, String> = emptyMap()): Boolean {
        return try {
            val envStr = env?.let { "环境=$it" } ?: "仅构建"
            console.print("    → 触发GitLab Pipeline: projectId=$projectId, branch=$branch, $envStr\n", ConsoleViewContentType.NORMAL_OUTPUT)

            val variables = mutableMapOf<String, String>()
            env?.let { variables["ENV"] = it }
            variables.putAll(extraVariables)

            // 触发pipeline
            val pipeline = triggerPipeline(projectId, branch, variables)
            if (pipeline != null) {
                console.print("      流水线触发成功! ID: ${pipeline.id}\n", ConsoleViewContentType.NORMAL_OUTPUT)
                // 监控pipeline状态
                monitorPipeline(projectId, pipeline.id)
            } else {
                console.print("      ✗ 流水线触发失败\n", ConsoleViewContentType.ERROR_OUTPUT)
                false
            }
        } catch (e: Exception) {
            console.print("    ✗ Pipeline触发失败: ${e.message}\n", ConsoleViewContentType.ERROR_OUTPUT)
            e.printStackTrace()
            false
        }
    }

    /**
     * 触发Pipeline
     */
    private fun triggerPipeline(projectId: Long, ref: String, variables: Map<String, String>): PipelineInfo? {
        val url = URL("$baseApiUrl/projects/$projectId/pipeline")
        val connection = url.openConnection() as HttpURLConnection

        return try {
            connection.requestMethod = "POST"
            connection.setRequestProperty("PRIVATE-TOKEN", gitlabToken)
            connection.setRequestProperty("Content-Type", "application/json")
            connection.doOutput = true

            // 构建请求体
            val requestBody = JSONObject()
            requestBody.put("ref", ref)
            if (variables.isNotEmpty()) {
                val vars = JSONArray()
                variables.forEach { (key, value) ->
                    val varObj = JSONObject()
                    varObj.put("key", key)
                    varObj.put("value", value)
                    varObj.put("variable_type", "env_var")
                    vars.put(varObj)
                }
                requestBody.put("variables", vars)
            }

            // 发送请求
            connection.outputStream.use { os ->
                val input = requestBody.toString().toByteArray(Charsets.UTF_8)
                os.write(input, 0, input.size)
            }

            // 读取响应
            if (connection.responseCode == 201) {
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(response)
                PipelineInfo(
                    id = json.getLong("id"),
                    status = json.getString("status"),
                    ref = json.getString("ref")
                )
            } else {
                val error = connection.errorStream?.bufferedReader()?.use { it.readText() } ?: "Unknown error"
                console.print("      创建Pipeline失败: ${connection.responseCode} - $error\n", ConsoleViewContentType.ERROR_OUTPUT)
                null
            }
        } finally {
            connection.disconnect()
        }
    }

    /**
     * 监控Pipeline状态
     */
    private fun monitorPipeline(projectId: Long, pipelineId: Long): Boolean {
        console.print("      开始监控流水线状态...\n", ConsoleViewContentType.NORMAL_OUTPUT)

        val maxAttempts = 120 // 最大20分钟
        var attempt = 0
        val jobTraceLengths = mutableMapOf<Long, Int>()

        while (attempt < maxAttempts) {
            attempt++

            try {
                val pipeline = getPipeline(projectId, pipelineId)
                if (pipeline == null) {
                    console.print("      ! 获取Pipeline状态失败\n", ConsoleViewContentType.ERROR_OUTPUT)
                    TimeUnit.SECONDS.sleep(10)
                    continue
                }

                console.print("      === 检查 $attempt: 状态: ${pipeline.status} ===\n", ConsoleViewContentType.NORMAL_OUTPUT)

                // 获取并输出Job日志
                val jobs = getJobs(projectId, pipelineId)
                jobs.forEach { job ->
                    printNewJobLogs(projectId, job, jobTraceLengths)
                }

                // 检查是否完成
                when (pipeline.status.lowercase()) {
                    "success" -> {
                        console.print("      ✓ 流水线执行成功!\n", ConsoleViewContentType.NORMAL_OUTPUT)
                        return true
                    }
                    "failed", "canceled" -> {
                        console.print("      ✗ 流水线执行失败或已取消!\n", ConsoleViewContentType.ERROR_OUTPUT)
                        return false
                    }
                    else -> {
                        // 继续等待
                        TimeUnit.SECONDS.sleep(10)
                    }
                }
            } catch (e: InterruptedException) {
                console.print("      ! 监控被中断\n", ConsoleViewContentType.ERROR_OUTPUT)
                Thread.currentThread().interrupt()
                return false
            } catch (e: Exception) {
                console.print("      ! 监控异常: ${e.message}\n", ConsoleViewContentType.ERROR_OUTPUT)
                TimeUnit.SECONDS.sleep(10)
            }
        }

        console.print("      ! 监控超时\n", ConsoleViewContentType.ERROR_OUTPUT)
        return false
    }

    /**
     * 获取Pipeline状态
     */
    private fun getPipeline(projectId: Long, pipelineId: Long): PipelineInfo? {
        val url = URL("$baseApiUrl/projects/$projectId/pipelines/$pipelineId")
        val connection = url.openConnection() as HttpURLConnection

        return try {
            connection.requestMethod = "GET"
            connection.setRequestProperty("PRIVATE-TOKEN", gitlabToken)

            if (connection.responseCode == 200) {
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(response)
                PipelineInfo(
                    id = json.getLong("id"),
                    status = json.getString("status"),
                    ref = json.getString("ref"),
                    updatedAt = json.optString("updated_at")
                )
            } else {
                null
            }
        } finally {
            connection.disconnect()
        }
    }

    /**
     * 获取Pipeline的所有Jobs
     */
    private fun getJobs(projectId: Long, pipelineId: Long): List<JobInfo> {
        val url = URL("$baseApiUrl/projects/$projectId/pipelines/$pipelineId/jobs")
        val connection = url.openConnection() as HttpURLConnection

        return try {
            connection.requestMethod = "GET"
            connection.setRequestProperty("PRIVATE-TOKEN", gitlabToken)

            if (connection.responseCode == 200) {
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                val jsonArray = JSONArray(response)
                (0 until jsonArray.length()).map { i ->
                    val job = jsonArray.getJSONObject(i)
                    JobInfo(
                        id = job.getLong("id"),
                        name = job.getString("name"),
                        status = job.getString("status"),
                        startedAt = job.optString("started_at")
                    )
                }
            } else {
                emptyList()
            }
        } finally {
            connection.disconnect()
        }
    }

    /**
     * 打印Job新增的日志
     */
    private fun printNewJobLogs(projectId: Long, job: JobInfo, jobTraceLengths: MutableMap<Long, Int>) {
        if (job.startedAt.isEmpty()) return

        console.print("        [任务 ${job.name} | 状态: ${job.status}]\n", ConsoleViewContentType.NORMAL_OUTPUT)

        try {
            val trace = getJobTrace(projectId, job.id)
            val lastLength = jobTraceLengths.getOrDefault(job.id, 0)

            if (trace.length > lastLength) {
                val newLogs = trace.substring(lastLength)
                console.print(newLogs, ConsoleViewContentType.NORMAL_OUTPUT)
                jobTraceLengths[job.id] = trace.length
            }
        } catch (e: Exception) {
            // 忽略日志获取失败
        }
    }

    /**
     * 获取Job日志
     */
    private fun getJobTrace(projectId: Long, jobId: Long): String {
        val url = URL("$baseApiUrl/projects/$projectId/jobs/$jobId/trace")
        val connection = url.openConnection() as HttpURLConnection

        return try {
            connection.requestMethod = "GET"
            connection.setRequestProperty("PRIVATE-TOKEN", gitlabToken)

            if (connection.responseCode == 200) {
                connection.inputStream.bufferedReader().use { it.readText() }
            } else {
                ""
            }
        } finally {
            connection.disconnect()
        }
    }

    /**
     * 获取最后构建时间
     */
    fun getLastBuildTime(service: Service, branch: String): Date? {
        val projectId = service.projectId ?: return null
        val url = URL("$baseApiUrl/projects/$projectId/pipelines?ref=$branch&status=success&per_page=1")
        val connection = url.openConnection() as HttpURLConnection

        try {
            connection.requestMethod = "GET"
            connection.setRequestProperty("PRIVATE-TOKEN", gitlabToken)

            if (connection.responseCode == 200) {
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                val jsonArray = JSONArray(response)
                if (jsonArray.length() > 0) {
                    val pipeline = jsonArray.getJSONObject(0)
                    val updatedAt = pipeline.getString("updated_at")
                    
                    // 解析ISO 8601格式的日期，支持时区
                    val date = try {
                        // 格式: 2025-12-17T17:49:02.891+08:00
                        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX")
                        sdf.parse(updatedAt)
                    } catch (e: Exception) {
                        // 尝试不带毫秒的格式
                        try {
                            val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX")
                            sdf.parse(updatedAt)
                        } catch (e2: Exception) {
                            // 尝试UTC格式
                            val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
                            sdf.timeZone = TimeZone.getTimeZone("UTC")
                            sdf.parse(updatedAt)
                        }
                    }
                    
                    if (date == null) {
                        throw RuntimeException("日期解析失败: $updatedAt")
                    }
                    
                    console.print("        gitLab pipeline:$branch:最后构建时间:${SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(date)}\n", ConsoleViewContentType.NORMAL_OUTPUT)
                    return date
                } else {
                    console.print("        gitLab pipeline:$branch:未找到成功的构建记录\n", ConsoleViewContentType.NORMAL_OUTPUT)
                    return null
                }
            } else {
                val error = connection.errorStream?.bufferedReader()?.use { it.readText() } ?: "HTTP ${connection.responseCode}"
                throw RuntimeException("获取Pipeline失败: $error")
            }
        } catch (e: Exception) {
            console.print("        ! 获取构建时间失败: ${e.message}\n", ConsoleViewContentType.ERROR_OUTPUT)
            throw e
        } finally {
            connection.disconnect()
        }
    }

    data class PipelineInfo(
        val id: Long,
        val status: String,
        val ref: String,
        val updatedAt: String = ""
    )

    data class JobInfo(
        val id: Long,
        val name: String,
        val status: String,
        val startedAt: String
    )
}
