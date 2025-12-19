package com.github.build.plugin.builds

import com.intellij.execution.ui.ConsoleView
import com.intellij.execution.ui.ConsoleViewContentType
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * Git 操作工具
 * 使用IntelliJ平台Git API
 */
class GitOperations(
    private val console: ConsoleView
) {
    /**
     * 合并并推送
     */
    fun mergeAndPush(service: Service, featBranch: String, buildBranch: String) {
        try {
            val gitRepo = service.gitRepo
            if (gitRepo.isBlank()) {
                console.print("      ! ${service.value}: 缺少Git仓库地址\n", ConsoleViewContentType.ERROR_OUTPUT)
                return
            }

            val repoDir = File(gitRepo)
            if (!repoDir.exists() || !File(repoDir, ".git").exists()) {
                console.print("      ! ${service.value}: 无效的Git仓库路径: $gitRepo\n", ConsoleViewContentType.ERROR_OUTPUT)
                return
            }

            console.print("      → Git操作: $gitRepo\n", ConsoleViewContentType.NORMAL_OUTPUT)

            // 1. Fetch最新代码
            console.print("        Fetching...", ConsoleViewContentType.NORMAL_OUTPUT)
            executeGitCommand(repoDir, "fetch", "origin")
            console.print(" ✓\n", ConsoleViewContentType.NORMAL_OUTPUT)

            // 2. 切换到目标分支
            console.print("        切换到分支: $buildBranch...", ConsoleViewContentType.NORMAL_OUTPUT)
            try {
                executeGitCommand(repoDir, "checkout", buildBranch)
            } catch (e: Exception) {
                // 分支不存在，创建本地分支
                executeGitCommand(repoDir, "checkout", "-b", buildBranch, "origin/$buildBranch")
            }
            console.print(" ✓\n", ConsoleViewContentType.NORMAL_OUTPUT)

            // 3. Pull最新代码
            console.print("        Pulling...", ConsoleViewContentType.NORMAL_OUTPUT)
            executeGitCommand(repoDir, "pull", "origin", buildBranch)
            console.print(" ✓\n", ConsoleViewContentType.NORMAL_OUTPUT)

            // 4. 合并功能分支
            console.print("        合并分支: $featBranch...", ConsoleViewContentType.NORMAL_OUTPUT)
            try {
                executeGitCommand(
                    repoDir,
                    "merge",
                    "origin/$featBranch",
                    "--no-ff",
                    "-m",
                    "Merge branch $featBranch into $buildBranch"
                )
                console.print(" ✓\n", ConsoleViewContentType.NORMAL_OUTPUT)
            } catch (e: Exception) {
                console.print(" ✗\n", ConsoleViewContentType.ERROR_OUTPUT)
                console.print("        ✗ 合并失败: ${e.message}\n", ConsoleViewContentType.ERROR_OUTPUT)
                
                // 清理合并状态
                try {
                    console.print("        清理合并状态...\n", ConsoleViewContentType.NORMAL_OUTPUT)
                    executeGitCommand(repoDir, "merge", "--abort")
                    executeGitCommand(repoDir, "reset", "--hard", "HEAD")
                } catch (cleanEx: Exception) {
                    console.print("        ! 清理失败: ${cleanEx.message}\n", ConsoleViewContentType.ERROR_OUTPUT)
                }
                throw RuntimeException("合并失败")
            }

            // 5. 推送到远程
            console.print("        Pushing...", ConsoleViewContentType.NORMAL_OUTPUT)
            executeGitCommand(repoDir, "push", "origin", buildBranch)
            console.print(" ✓\n", ConsoleViewContentType.NORMAL_OUTPUT)

            console.print("        ✓ 合并推送成功\n", ConsoleViewContentType.NORMAL_OUTPUT)
        } catch (e: Exception) {
            console.print("      ✗ Git操作失败: ${e.message}\n", ConsoleViewContentType.ERROR_OUTPUT)
            throw e
        }
    }

    /**
     * 获取最后更新时间
     */
    fun getLastUpdateTime(service: Service, branch: String): Date? {
        return try {
            val gitRepo = service.gitRepo
            if (gitRepo.isBlank()) {
                return null
            }

            val repoDir = File(gitRepo)
            if (!repoDir.exists()) {
                return null
            }

            // 获取远程分支最后一次commit时间
            val output = executeGitCommand(
                repoDir,
                "log",
                "origin/$branch",
                "-1",
                "--format=%ct" // Unix timestamp
            )

            val timestamp = output.trim().toLongOrNull()
            if (timestamp != null) {
                val date = Date(timestamp * 1000)
                val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
                console.print("        $gitRepo:$branch:最后提交时间:${sdf.format(date)}\n", ConsoleViewContentType.NORMAL_OUTPUT)
                date
            } else {
                null
            }
        } catch (e: Exception) {
            console.print("      ! 获取更新时间失败: ${e.message}\n", ConsoleViewContentType.ERROR_OUTPUT)
            null
        }
    }

    /**
     * 执行Git命令
     */
    private fun executeGitCommand(workDir: File, vararg args: String): String {
        val command = mutableListOf("git").apply { addAll(args) }
        val processBuilder = ProcessBuilder(command)
        processBuilder.directory(workDir)
        processBuilder.redirectErrorStream(true)

        val process = processBuilder.start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        val exitCode = process.waitFor()

        if (exitCode != 0) {
            throw RuntimeException("Git命令执行失败: ${args.joinToString(" ")}\n$output")
        }

        return output
    }
}
