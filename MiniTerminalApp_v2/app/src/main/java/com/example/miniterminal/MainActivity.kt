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
 * Mini Terminal — starts on Android's built-in toybox shell. Typing
 * "setup" downloads a real proot + Alpine Linux environment (one-time,
 * ~40-80MB) and switches to it, giving real bash/apk/python — not just
 * toybox commands. `git` is handled separately via JGit either way.
 *
 * See ProotSetup.kt for the honest Android-version caveats before relying
 * on this for anything important.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var outputText: TextView
    private lateinit var outputScroll: ScrollView
    private lateinit var commandInput: EditText

    private var shellProcess: Process? = null
    private var shellWriter: OutputStreamWriter? = null
    private var readerThread: Thread? = null
    @Volatile private var running = true
    @Volatile private var usingProot = false

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

        if (ProotSetup.isReady(this)) {
            currentDir = ProotSetup.homeDir(this)
            startProotShell()
        } else {
            currentDir = filesDir
            startToyboxShell()
            appendOutput(
                "[toybox shell — basic commands only]\n" +
                "[type 'setup' to download a real Linux environment (bash, apk, python) — one-time ~40-80MB]\n"
            )
        }
    }

    // ---------- shell startup (two modes) ----------

    private fun startToyboxShell() {
        try {
            val pb = ProcessBuilder("/system/bin/sh")
            pb.redirectErrorStream(true)
            pb.directory(currentDir)
            shellProcess = pb.start()
            shellWriter = OutputStreamWriter(shellProcess!!.outputStream)
            usingProot = false
            startReaderThread()
        } catch (e: Exception) {
            appendOutput("[failed to start shell: ${e.message}]\n")
        }
    }

    private fun startProotShell() {
        try {
            val cmd = ProotSetup.buildLaunchCommand(this)
            val pb = ProcessBuilder(cmd)
            pb.redirectErrorStream(true)
            pb.environment().putAll(ProotSetup.launchEnv(this))
            shellProcess = pb.start()
            shellWriter = OutputStreamWriter(shellProcess!!.outputStream)
            usingProot = true
            startReaderThread()
            appendOutput("[Alpine Linux shell ready — try: apk add python3]\n")
        } catch (e: Exception) {
            appendOutput("[failed to start proot shell: ${e.message}]\n")
        }
    }

    private fun startReaderThread() {
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
                if (running) runOnUiThread { appendOutput("\n[shell stream closed: ${e.message}]\n") }
            }
        }
        readerThread!!.isDaemon = true
        readerThread!!.start()
    }

    // ---------- command routing ----------

    private fun sendCommand() {
        val cmd = commandInput.text.toString()
        if (cmd.isBlank()) return

        appendOutput("$ $cmd\n")
        commandInput.text.clear()

        val tokens = tokenize(cmd)
        when {
            tokens.isEmpty() -> return
            tokens[0] == "setup" -> runSetup()
            tokens[0] == "git" && !usingProot -> runGit(tokens.drop(1))
            tokens[0] == "cd" && !usingProot -> runCd(tokens.drop(1), cmd)
            else -> forwardToShell(cmd)
        }
    }

    private fun runSetup() {
        if (ProotSetup.isReady(this)) {
            appendOutput("Already set up — Alpine Linux is active.\n")
            return
        }
        appendOutput("Starting setup, this needs internet and can take a few minutes...\n")
        Thread {
            try {
                ProotSetup.performSetup(this) { line ->
                    runOnUiThread { appendOutput(line) }
                }
                runOnUiThread {
                    running = false
                    shellProcess?.destroy()
                    running = true
                    currentDir = ProotSetup.homeDir(this)
                    startProotShell()
                }
            } catch (e: Exception) {
                runOnUiThread { appendOutput("[setup failed: ${e.message}]\n") }
            }
        }.start()
    }

    /** git runs off the main thread since clone/pull/push do real network I/O. */
    private fun runGit(args: List<String>) {
        val dirAtCallTime = currentDir
        Thread {
            val result = GitHelper.run(args, dirAtCallTime)
            runOnUiThread { appendOutput(result.trimEnd() + "\n") }
        }.start()
    }

    /** Only used in toybox mode — proot mode tracks cwd inside its own persistent shell naturally. */
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
