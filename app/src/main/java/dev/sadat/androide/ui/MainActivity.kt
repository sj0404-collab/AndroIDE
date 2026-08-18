package dev.sadat.androide.ui

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.LayoutInflater
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import dev.sadat.androide.AndroApp
import dev.sadat.androide.R
import dev.sadat.androide.agent.AgentEngine
import dev.sadat.androide.ai.AiClient
import dev.sadat.androide.ai.Catalog
import dev.sadat.androide.github.GitHubClient
import dev.sadat.androide.local.LocalModels
import dev.sadat.androide.log.LogStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : AppCompatActivity() {
    private lateinit var content: android.widget.FrameLayout
    private val inflater by lazy { LayoutInflater.from(this) }
    private val agent by lazy { AgentEngine(AndroApp.instance.sessions) }
    private val ai = AiClient()
    private val gh by lazy { GitHubClient(AndroApp.instance.workspace) }
    private var streamBubble: TextView? = null
    private var instr = 0
    private var tokens = 0
    private var tick = 0
    private val started = System.currentTimeMillis()
    private val handler = Handler(Looper.getMainLooper())
    private val clock = object : Runnable {
        override fun run() {
            tick += 1
            findViewById<TextView>(R.id.timeLabel)?.text = "${tick}с"
            val used = ((System.currentTimeMillis() - started) / 60000).toInt()
            val left = (6 * 60 - used).coerceAtLeast(0)
            findViewById<TextView>(R.id.sessionLine)?.text =
                "сессия ${used}м / 6ч 0м · осталось ${left / 60}ч ${left % 60}м"
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        content = findViewById(R.id.content)
        val brand = SpannableString("Zen  Agent")
        brand.setSpan(ForegroundColorSpan(Color.parseColor("#2DD4BF")), 0, 3, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        findViewById<TextView>(R.id.brand).text = brand

        val models = Catalog.byId(AndroApp.instance.keys.provider).models
        val top = findViewById<Spinner>(R.id.topModel)
        top.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, models)
        val mi = models.indexOf(AndroApp.instance.keys.model)
        if (mi >= 0) top.setSelection(mi)
        top.setOnItemSelectedListener(simple {
            AndroApp.instance.keys.model = top.selectedItem.toString()
        })

        findViewById<View>(R.id.btnTopStop).setOnClickListener {
            agent.stop.set(true)
            setStatus("стоп", "#E11D48")
        }
        findViewById<View>(R.id.sheetClose).setOnClickListener { closeSheet() }
        findViewById<View>(R.id.fabGh).setOnClickListener { openSheet("GitHub") { showGithub() } }
        findViewById<View>(R.id.fabDev).setOnClickListener { openSheet("Dev") { showFiles() } }
        findViewById<View>(R.id.fabPrev).setOnClickListener { openSheet("Preview") { showRun() } }
        findViewById<View>(R.id.fabSet).setOnClickListener { openSheet("Settings") { showSettings() } }
        findViewById<View>(R.id.fabLog).setOnClickListener { openSheet("Logs") { showLogs() } }

        bindChat()
        handler.post(clock)
        setStatus("готов", "#2DD4BF")
    }

    override fun onDestroy() {
        handler.removeCallbacks(clock)
        super.onDestroy()
    }

    override fun onPause() {
        super.onPause()
        AndroApp.instance.sessions.save()
    }

    private fun setStatus(word: String, color: String) {
        findViewById<TextView>(R.id.statusWord).apply {
            text = word
            setTextColor(Color.parseColor(color))
        }
    }

    private fun openSheet(title: String, fill: () -> Unit) {
        findViewById<TextView>(R.id.sheetTitle).text = title
        findViewById<View>(R.id.sheet).visibility = View.VISIBLE
        fill()
    }

    private fun closeSheet() {
        findViewById<View>(R.id.sheet).visibility = View.GONE
        AndroApp.instance.sessions.save()
    }

    private fun show(v: View) {
        content.removeAllViews()
        content.addView(v)
    }

    private fun bubble(parent: LinearLayout, who: String, body: String, think: String = "", user: Boolean = false): View {
        val b = inflater.inflate(R.layout.bubble, parent, false)
        b.findViewById<TextView>(R.id.body).text = body
        val t = b.findViewById<TextView>(R.id.think)
        if (think.isNotBlank() && AndroApp.instance.keys.showReasoning) {
            t.visibility = View.VISIBLE
            t.text = think
        }
        b.findViewById<View>(R.id.bar).visibility = if (user) View.VISIBLE else View.GONE
        parent.addView(b)
        return b
    }

    private fun bindChat() {
        val box = findViewById<LinearLayout>(R.id.chatBox)
        val scroll = findViewById<ScrollView>(R.id.agentScroll)
        val input = findViewById<EditText>(R.id.agentInput)
        val sessions = AndroApp.instance.sessions
        val keys = AndroApp.instance.keys
        box.removeAllViews()
        val msgs = sessions.current.messages.filter { it.role != "system" }
        if (msgs.isEmpty()) {
            bubble(box, "AI", "Напиши — и я подскажу точнее.")
        } else {
            msgs.forEach { m ->
                bubble(box, m.role, m.content, m.reasoning, user = m.role == "user")
            }
        }
        findViewById<TextView>(R.id.roundLabel).text = "раунд ${sessions.current.rounds}/${keys.maxRounds}"
        findViewById<View>(R.id.btnSend).setOnClickListener {
            val text = input.text.toString().trim()
            if (text.isEmpty()) return@setOnClickListener
            input.setText("")
            bubble(box, "YOU", text, user = true)
            setStatus("думает", "#C9A227")
            streamBubble = null
            val t0 = System.currentTimeMillis()
            lifecycleScope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        agent.runAutonomous(text) { ev ->
                            runOnUiThread {
                                when (ev.kind) {
                                    "stream" -> {
                                        if (streamBubble == null) {
                                            val b = bubble(box, "AI", "")
                                            streamBubble = b.findViewById(R.id.body)
                                        }
                                        streamBubble?.append(ev.text)
                                        tokens += ev.text.length / 4
                                    }
                                    "think" -> bubble(box, "THINK", "", ev.text)
                                    "write", "term", "pty", "github", "read", "delete", "move", "web", "art" -> {
                                        instr += 1
                                        findViewById<TextView>(R.id.instrLabel).text = "инстр. $instr"
                                    }
                                    "round" -> findViewById<TextView>(R.id.roundLabel).text =
                                        ev.text.replace("round ", "раунд ")
                                    else -> { }
                                }
                                findViewById<TextView>(R.id.tokLabel).text = "токены $tokens"
                                findViewById<TextView>(R.id.timeLabel).text =
                                    "${((System.currentTimeMillis() - t0) / 1000)}с"
                                if (ev.kind == "think") setStatus("думает", "#C9A227")
                                else if (ev.kind == "stream") setStatus("пишет", "#2DD4BF")
                                else setStatus(ev.kind, "#8B8B97")
                                scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
                            }
                        }
                    }
                    setStatus("готов", "#2DD4BF")
                } catch (e: Exception) {
                    bubble(box, "ERR", e.message ?: "fail")
                    setStatus("ошибка", "#E11D48")
                    LogStore.add("error", e.message ?: "fail")
                }
                sessions.save()
            }
        }
    }

    private fun showFiles() {
        val v = inflater.inflate(R.layout.tab_files, content, false)
        val ws = AndroApp.instance.workspace
        val list = v.findViewById<ListView>(R.id.fileList)
        val path = v.findViewById<EditText>(R.id.filePath)
        val dest = v.findViewById<EditText>(R.id.moveTo)
        val body = v.findViewById<EditText>(R.id.fileBody)
        val proj = v.findViewById<EditText>(R.id.projectName)
        val pSpin = v.findViewById<Spinner>(R.id.projectSpin)
        fun projects() = ws.listProjects().map { it.name }
        fun refresh() {
            pSpin.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, projects())
            val i = projects().indexOf(ws.currentProject.name)
            if (i >= 0) pSpin.setSelection(i)
            list.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, ws.listFiles().map { it.relativeTo(ws.currentProject).path })
            proj.setText(ws.currentProject.name)
        }
        refresh()
        pSpin.setOnItemSelectedListener(simple {
            ws.openOrCreate(pSpin.selectedItem?.toString() ?: return@simple)
            refresh()
        })
        list.setOnItemClickListener { _, _, i, _ ->
            val rel = list.adapter.getItem(i) as String
            path.setText(rel); dest.setText(rel)
            body.setText(ws.read(rel))
        }
        v.findViewById<View>(R.id.btnOpenProject).setOnClickListener { ws.openOrCreate(proj.text.toString()); refresh() }
        v.findViewById<View>(R.id.btnRefreshFiles).setOnClickListener { refresh() }
        v.findViewById<View>(R.id.btnSaveFile).setOnClickListener { ws.write(path.text.toString(), body.text.toString()); refresh() }
        v.findViewById<View>(R.id.btnMoveFile).setOnClickListener { ws.move(path.text.toString(), dest.text.toString()); refresh() }
        v.findViewById<View>(R.id.btnCopyFile).setOnClickListener { ws.copy(path.text.toString(), dest.text.toString()); refresh() }
        v.findViewById<View>(R.id.btnDelFile).setOnClickListener { ws.delete(path.text.toString()); refresh() }
        show(v)
    }

    private fun showGithub() {
        val v = inflater.inflate(R.layout.tab_github, content, false)
        val token = v.findViewById<EditText>(R.id.ghToken)
        val log = v.findViewById<TextView>(R.id.ghLog)
        val who = v.findViewById<TextView>(R.id.ghWho)
        val repos = v.findViewById<TextView>(R.id.ghRepos)
        val repo = v.findViewById<EditText>(R.id.ghRepo)
        token.setText(AndroApp.instance.keys.githubToken)
        fun go(block: () -> String) {
            lifecycleScope.launch {
                try {
                    log.text = withContext(Dispatchers.IO) { block() }
                } catch (e: Exception) {
                    log.text = e.message
                }
            }
        }
        v.findViewById<View>(R.id.btnSaveToken).setOnClickListener {
            AndroApp.instance.keys.githubToken = token.text.toString()
            go {
                val w = gh.whoami(); runOnUiThread { who.text = w }; w
            }
        }
        v.findViewById<View>(R.id.btnListRepos).setOnClickListener {
            go {
                val t = gh.listRepos().joinToString("\n") { it.fullName }
                runOnUiThread { repos.text = t }
                t
            }
        }
        v.findViewById<View>(R.id.btnClone).setOnClickListener { go { gh.cloneRepo(repo.text.toString()).absolutePath } }
        v.findViewById<View>(R.id.btnBind).setOnClickListener { gh.bindRemote(repo.text.toString()); log.text = "bound" }
        v.findViewById<View>(R.id.btnPush).setOnClickListener {
            go { gh.commitAndPush(v.findViewById<EditText>(R.id.ghCommitMsg).text.toString()) }
        }
        v.findViewById<View>(R.id.btnRelease).setOnClickListener {
            go { gh.createRelease("v2.3.0", "AndroIDE", "ui") }
        }
        v.findViewById<View>(R.id.btnWorkflow).setOnClickListener { go { gh.dispatchWorkflow("android.yml") } }
        v.findViewById<View>(R.id.btnPty).setOnClickListener {
            val cmd = v.findViewById<EditText>(R.id.ptyCmd).text.toString()
            go {
                val d = gh.dispatchWorkflow("pty.yml", mapOf("command" to cmd, "chat" to "ui"))
                Thread.sleep(4000)
                val run = gh.latestRun("pty")
                d + "\n" + (if (run != null) gh.runLogs(run.getLong("id")) else "waiting")
            }
        }
        v.findViewById<View>(R.id.btnCreateRepo).setOnClickListener {
            go {
                val r = gh.createRepo(v.findViewById<EditText>(R.id.ghNewRepo).text.toString())
                gh.bindRemote(r.fullName)
                r.fullName
            }
        }
        show(v)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun showRun() {
        val v = inflater.inflate(R.layout.tab_run, content, false)
        val web = v.findViewById<WebView>(R.id.web)
        val hint = v.findViewById<TextView>(R.id.runHint)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.allowFileAccess = true
        web.webViewClient = WebViewClient()
        web.webChromeClient = WebChromeClient()
        v.findViewById<View>(R.id.btnRunHtml).setOnClickListener {
            val html = listOf("index.html", "game.html").map { File(AndroApp.instance.workspace.currentProject, it) }.firstOrNull { it.exists() }
            if (html == null) hint.text = "No index.html" else web.loadUrl("file://${html.absolutePath}")
        }
        show(v)
    }

    private fun showLogs() {
        val v = inflater.inflate(R.layout.tab_logs, content, false)
        val tv = v.findViewById<TextView>(R.id.logView)
        tv.text = LogStore.dump()
        v.findViewById<View>(R.id.btnRefreshLogs).setOnClickListener { tv.text = LogStore.dump() }
        show(v)
    }

    private fun showSettings() {
        val v = inflater.inflate(R.layout.tab_settings, content, false)
        val keys = AndroApp.instance.keys
        val aSpin = v.findViewById<Spinner>(R.id.accountSpin)
        val pSpin = v.findViewById<Spinner>(R.id.providerSpin)
        val mSpin = v.findViewById<Spinner>(R.id.modelSpin)
        val hint = v.findViewById<TextView>(R.id.keyHint)
        val key = v.findViewById<EditText>(R.id.apiKey)
        val slog = v.findViewById<TextView>(R.id.setLog)
        fun accFill() {
            val acc = keys.accounts()
            aSpin.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, acc)
            val i = acc.indexOf(keys.account)
            if (i >= 0) aSpin.setSelection(i)
        }
        accFill()
        v.findViewById<EditText>(R.id.localBase).setText(keys.localBase)
        v.findViewById<EditText>(R.id.maxRounds).setText(keys.maxRounds.toString())
        v.findViewById<CheckBox>(R.id.autoRotate).isChecked = keys.autoRotate
        v.findViewById<CheckBox>(R.id.showReason).isChecked = keys.showReasoning
        pSpin.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, Catalog.all.map { it.label })
        pSpin.setSelection(Catalog.all.indexOfFirst { it.id == keys.provider }.coerceAtLeast(0))
        fun spec() = Catalog.all[pSpin.selectedItemPosition]
        fun bindModels(models: List<String>) {
            mSpin.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, models)
            val mi = models.indexOf(keys.model)
            if (mi >= 0) mSpin.setSelection(mi)
        }
        bindModels(spec().models)
        hint.text = spec().keyHint
        key.setText(keys.getKey(spec().id))
        pSpin.setOnItemSelectedListener(simple {
            hint.text = spec().keyHint
            key.setText(keys.getKey(spec().id))
            bindModels(spec().models)
        })
        v.findViewById<View>(R.id.btnAddAccount).setOnClickListener {
            val n = v.findViewById<EditText>(R.id.newAccount).text.toString()
            if (n.isNotBlank()) keys.addAccount(n) else keys.account = aSpin.selectedItem?.toString() ?: "default"
            accFill()
        }
        v.findViewById<View>(R.id.btnSaveKeys).setOnClickListener {
            val s = spec()
            keys.provider = s.id
            keys.model = mSpin.selectedItem?.toString() ?: s.defaultModel
            keys.setKey(s.id, key.text.toString())
            keys.localBase = v.findViewById<EditText>(R.id.localBase).text.toString()
            keys.maxRounds = v.findViewById<EditText>(R.id.maxRounds).text.toString().toIntOrNull() ?: 128
            keys.autoRotate = v.findViewById<CheckBox>(R.id.autoRotate).isChecked
            keys.showReasoning = v.findViewById<CheckBox>(R.id.showReason).isChecked
            slog.text = "saved"
            findViewById<TextView>(R.id.brand)
        }
        v.findViewById<View>(R.id.btnRefreshModels).setOnClickListener {
            lifecycleScope.launch {
                bindModels(withContext(Dispatchers.IO) { ai.fetchModels(spec()) })
            }
        }
        v.findViewById<View>(R.id.btnDlTiny).setOnClickListener {
            lifecycleScope.launch {
                slog.text = try {
                    withContext(Dispatchers.IO) { LocalModels.download("tinyllama-1.1b") {} }.name
                } catch (e: Exception) {
                    e.message
                }
            }
        }
        v.findViewById<View>(R.id.btnOllama).setOnClickListener {
            lifecycleScope.launch {
                slog.text = withContext(Dispatchers.IO) {
                    try {
                        LocalModels.ollamaPull("llama3.2")
                    } catch (e: Exception) {
                        e.message
                    }
                }
            }
        }
        show(v)
    }

    private fun simple(block: () -> Unit) = object : android.widget.AdapterView.OnItemSelectedListener {
        override fun onItemSelected(p: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) = block()
        override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
