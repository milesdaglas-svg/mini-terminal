package com.example.miniterminal

import android.os.Bundle
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter

/**
 * Mini Terminal — a persistent /system/bin/sh session on-device, plus a
 * lightweight `git` (via JGit, pure Java — no native binary) layered on
 * top for clone/status/add/commit/pull/push.
 *
 * Honest limitation: Android's built-in shell only has toybox commands
 * (ls, cd, cat, echo, ps, etc) — no bash, no python, no apt. `git` is
 * specifically handled separately via JGit since Android has no native
 * git binary at all.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var outputText: TextView
    private lateinit var outputScroll: ScrollView
    private lateinit var commandInput: EditText

    private var shellProcess: Process? = null
    private var shellWriter: OutputStreamWriter? = null
    private var readerThread: Thread? = null
    @Volatile private var running = true

    // Mirrors the shell's working directory so `git` commands (which
    // don't go through the real shell) operate in the right folder.
    private lateinit var currentDir: File

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        outputText = findViewById(R.id.outputText)
        outputScroll = findViewById(R.id.outputScroll)
        commandInput = findViewById(R.id.commandInput)

        val runButton: Button = findViewById(R.id.runButton)
        val clearButton: Button = findViewById(R.id.clearButton)

        runButton.setOnClickListener { sendCommand() }
        clearButton.setOnClickListener { outputText.text = "" }

        commandInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendCommand()
                true
            } else {
                false
            }
        }

        // App-private storage — writable with no special permissions needed.
        currentDir = filesDir
        startShell()
    }

    private fun startShell() {
        try {
            val pb = ProcessBuilder("/system/bin/sh")
            pb.redirectErrorStream(true)
            pb.directory(currentDir)
            shellProcess = pb.start()

            shellWriter = OutputStreamWriter(shellProcess!!.outputStream)

            readerThread = Thread {
                try {
                    val reader = BufferedReader(InputStreamReader(shellProcess!!.inputStream))
                    val buffer = CharArray(1024)
                    while (running) {
                        val read = reader.read(buffer)
                        if (read == -1) break
                        val chunk = String(buffer, 0, read)
                        runOnUiThread { appendOutput(chunk) }
                    }
                } catch (e: IOException) {
                    if (running) {
                        runOnUiThread { appendOutput("\n[shell stream closed: ${e.message}]\n") }
                    }
                }
            }
            readerThread!!.isDaemon = true
            readerThread!!.start()

            appendOutput("[shell session started in ${currentDir.absolutePath}]\n")
        } catch (e: Exception) {
            appendOutput("[failed to start shell: ${e.message}]\n")
        }
    }

    private fun sendCommand() {
        val cmd = commandInput.text.toString()
        if (cmd.isBlank()) return

        appendOutput("$ $cmd\n")
        commandInput.text.clear()

        val tokens = tokenize(cmd)
        when {
            tokens.isEmpty() -> return
            tokens[0] == "git" -> runGit(tokens.drop(1))
            tokens[0] == "cd" -> runCd(tokens.drop(1), cmd)
            else -> forwardToShell(cmd)
        }
    }

    /** git runs off the main thread since clone/pull/push do real network I/O. */
    private fun runGit(args: List<String>) {
        val dirAtCallTime = currentDir
        Thread {
            val result = GitHelper.run(args, dirAtCallTime)
            runOnUiThread { appendOutput(result.trimEnd() + "\n") }
        }.start()
    }

    /** Tracks our own cwd mirror, and still forwards to the shell so ls/pwd/etc stay in sync. */
    private fun runCd(args: List<String>, rawCmd: String) {
        val target = if (args.isEmpty()) filesDir else File(args[0])
        val resolved = if (target.isAbsolute) target else File(currentDir, args[0])
        val normalized = resolved.canonicalFile

        if (!normalized.exists() || !normalized.isDirectory) {
            appendOutput("cd: no such directory: ${args.getOrElse(0) { "" }}\n")
            return
        }

        currentDir = normalized
        forwardToShell(rawCmd)
    }

    private fun forwardToShell(cmd: String) {
        try {
            shellWriter?.write(cmd + "\n")
            shellWriter?.flush()
        } catch (e: IOException) {
            appendOutput("[couldn't send command: ${e.message}]\n")
        }
    }

    /** Simple tokenizer that respects "double" and 'single' quoted args, e.g. git commit -m "message here". */
    private fun tokenize(s: String): List<String> {
        val regex = Regex("\"([^\"]*)\"|'([^']*)'|(\\S+)")
        return regex.findAll(s).map { m ->
            m.groups[1]?.value ?: m.groups[2]?.value ?: m.groups[3]?.value ?: ""
        }.toList()
    }

    private fun appendOutput(text: String) {
        outputText.append(text)
        outputScroll.post { outputScroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    override fun onDestroy() {
        super.onDestroy()
        running = false
        try {
            shellWriter?.write("exit\n")
            shellWriter?.flush()
        } catch (ignored: IOException) {}
        shellProcess?.destroy()
    }
}
