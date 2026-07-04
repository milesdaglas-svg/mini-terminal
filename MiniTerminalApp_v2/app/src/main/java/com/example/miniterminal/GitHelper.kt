package com.example.miniterminal

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.errors.GitAPIException
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import java.io.File

/**
 * Thin wrapper around JGit — a pure-Java git implementation, no native
 * binary, no bootstrap download. Covers the handful of git commands
 * people actually type day to day.
 *
 * Auth note: for GitHub/GitLab, "password" here should be a Personal
 * Access Token, not your real account password — those platforms don't
 * accept plain passwords for git operations anymore anyway.
 */
object GitHelper {

    var username: String? = null
    var token: String? = null

    private fun creds() =
        if (username != null && token != null)
            UsernamePasswordCredentialsProvider(username, token)
        else null

    fun run(args: List<String>, cwd: File): String {
        if (args.isEmpty()) return "usage: git <command> [args]"
        return try {
            when (args[0]) {
                "clone" -> clone(args, cwd)
                "status" -> status(cwd)
                "add" -> add(args, cwd)
                "commit" -> commit(args, cwd)
                "pull" -> pull(cwd)
                "push" -> push(cwd)
                "log" -> log(cwd)
                "init" -> init(cwd)
                "config" -> config(args)
                else -> "git: unsupported command '${args[0]}' in this lightweight client.\n" +
                    "Supported: clone, status, add, commit, pull, push, log, init, config"
            }
        } catch (e: GitAPIException) {
            "git error: ${e.message}"
        } catch (e: Exception) {
            "error: ${e.message}"
        }
    }

    private fun config(args: List<String>): String {
        if (args.size < 3) return "usage: git config user.name <name> | git config credential.token <token>"
        return when (args[1]) {
            "user.name" -> { username = args[2]; "username set" }
            "credential.token" -> { token = args[2]; "token set (kept in memory only, cleared when app closes)" }
            else -> "unsupported config key"
        }
    }

    private fun init(cwd: File): String {
        Git.init().setDirectory(cwd).call().close()
        return "Initialized empty Git repository in ${cwd.absolutePath}"
    }

    private fun clone(args: List<String>, cwd: File): String {
        if (args.size < 2) return "usage: git clone <url> [folder]"
        val url = args[1]
        val folderName = if (args.size > 2) args[2] else url.substringAfterLast("/").removeSuffix(".git")
        val target = File(cwd, folderName)
        val cmd = Git.cloneRepository().setURI(url).setDirectory(target)
        creds()?.let { cmd.setCredentialsProvider(it) }
        cmd.call().close()
        return "Cloned into '$folderName'"
    }

    private fun status(cwd: File): String {
        Git.open(cwd).use { git ->
            val s = git.status().call()
            val sb = StringBuilder()
            if (s.added.isNotEmpty()) sb.append("Added: ${s.added}\n")
            if (s.changed.isNotEmpty()) sb.append("Changed: ${s.changed}\n")
            if (s.modified.isNotEmpty()) sb.append("Modified: ${s.modified}\n")
            if (s.untracked.isNotEmpty()) sb.append("Untracked: ${s.untracked}\n")
            if (s.missing.isNotEmpty()) sb.append("Missing: ${s.missing}\n")
            if (sb.isEmpty()) sb.append("Nothing to commit, working tree clean.\n")
            return sb.toString()
        }
    }

    private fun add(args: List<String>, cwd: File): String {
        val pattern = if (args.size > 1) args[1] else "."
        Git.open(cwd).use { git -> git.add().addFilepattern(pattern).call() }
        return "Added: $pattern"
    }

    private fun commit(args: List<String>, cwd: File): String {
        val mIndex = args.indexOf("-m")
        val message = if (mIndex != -1 && args.size > mIndex + 1) args[mIndex + 1] else "commit via mini terminal"
        Git.open(cwd).use { git ->
            val result = git.commit().setMessage(message).call()
            return "Committed: ${result.shortMessage} (${result.id.abbreviate(7).name()})"
        }
    }

    private fun pull(cwd: File): String {
        Git.open(cwd).use { git ->
            val cmd = git.pull()
            creds()?.let { cmd.setCredentialsProvider(it) }
            val result = cmd.call()
            return if (result.isSuccessful) "Up to date / merged cleanly." else "Pull had conflicts — check status."
        }
    }

    private fun push(cwd: File): String {
        Git.open(cwd).use { git ->
            val cmd = git.push()
            creds()?.let { cmd.setCredentialsProvider(it) }
            cmd.call()
            return "Pushed."
        }
    }

    private fun log(cwd: File): String {
        Git.open(cwd).use { git ->
            val sb = StringBuilder()
            var count = 0
            for (commit in git.log().call()) {
                sb.append("${commit.id.abbreviate(7).name()}  ${commit.shortMessage}\n")
                if (++count >= 10) break
            }
            if (count == 0) sb.append("No commits yet.\n")
            return sb.toString()
        }
    }
}
