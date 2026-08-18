package dev.sadat.androide.ui

import android.annotation.SuppressLint
import android.os.Bundle
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
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.tabs.TabLayout
import dev.sadat.androide.AndroApp
import dev.sadat.androide.R
import dev.sadat.androide.agent.AgentEngine
import dev.sadat.androide.agent.TodoStore
import dev.sadat.androide.ai.AiClient
import dev.sadat.androide.ai.Catalog
import dev.sadat.androide.github.GitHubClient
import dev.sadat.androide.local.LocalModels
import dev.sadat.androide.log.LogStore
import dev.sadat.androide.project.Templates
import org.json.JSONObject
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        content = findViewById(R.id.content)
        LogStore.listen { line ->
            runOnUiThread {
                findViewById<TextView>(R.id.globalInd)?.text =
                    "${line.kind}: ${line.text.take(90)}"
            }
        }
        val tabs = findViewById<TabLayout>(R.id.tabs)
        listOf("GitHub", "Dev", "AI", "Preview", "Settings", "Logs").forEach {
            tabs.addTab(tabs.newTab().setText(it))
        }
        showGithub()
        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                try {
                    AndroApp.instance.sessions.save()
                    when (tab.position) {
                        0 -> showGithub()
                        1 -> showFiles()
                        2 -> showAgent()
                        3 -> showRun()
                        4 -> showSettings()
                        5 -> showLogs()
                    }
                } catch (e: Exception) {
                    toast(e.message ?: "tab error")
                    LogStore.add("crash", e.stackTraceToString())
                }
            }
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })
    }

    override fun onPause() {
        super.onPause()
        AndroApp.instance.sessions.save()
    }

    private fun show(v: View) {
        content.removeAllViews()
        content.addView(v)
    }

    private fun bubble(parent: LinearLayout, who: String, body: String, think: String = ""): View {
        val b = inflater.inflate(R.layout.bubble, parent, false)
        b.findViewById<TextView>(R.id.who).text = who
        b.findViewById<TextView>(R.id.body).text = body
        val t = b.findViewById<TextView>(R.id.think)
        if (think.isNotBlank() && AndroApp.instance.keys.showReasoning) {
            t.visibility = View.VISIBLE
            t.text = "thinking\n$think"
        }
        parent.addView(b)
        return b
    }

    private fun showAgent() {
        val v = inflater.inflate(R.layout.tab_agent, content, false)
        val box = v.findViewById<LinearLayout>(R.id.chatBox)
        val scroll = v.findViewById<ScrollView>(R.id.agentScroll)
        val meta = v.findViewById<TextView>(R.id.agentMeta)
        val rounds = v.findViewById<TextView>(R.id.roundLabel)
        val input = v.findViewById<EditText>(R.id.agentInput)
        val spin = v.findViewById<Spinner>(R.id.sessionSpin)
        val todo = v.findViewById<TextView>(R.id.todoView)
        val ind = v.findViewById<TextView>(R.id.indicator)
        val busy = v.findViewById<ProgressBar>(R.id.busy)
        val term = v.findViewById<TextView>(R.id.termMini)
        val sessions = AndroApp.instance.sessions
        val keys = AndroApp.instance.keys

        fun paintSession() {
            val s = sessions.current
            box.removeAllViews()
            streamBubble = null
            s.messages.filter { it.role != "system" }.forEach { m ->
                bubble(box, m.role.uppercase(), m.content, m.reasoning)
            }
            rounds.text = "${s.rounds}/${keys.maxRounds}"
            todo.text = TodoStore.render()
            meta.text = "acc=${keys.account} ${keys.provider}/${keys.model} proj=${AndroApp.instance.workspace.currentProject.name} autosave=on"
            scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
        }

        fun fillSessions() {
            val list = sessions.list()
            spin.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, list.map { "${it.id} ${it.title}" })
            val i = list.indexOfFirst { it.id == sessions.current.id }
            if (i >= 0) spin.setSelection(i)
        }
        fillSessions()
        paintSession()
        spin.setOnItemSelectedListener(simple {
            val list = sessions.list()
            val i = spin.selectedItemPosition
            if (i in list.indices) {
                sessions.save()
                sessions.switchTo(list[i].id)
                paintSession()
            }
        })
        v.findViewById<View>(R.id.btnNewSession).setOnClickListener {
            sessions.save()
            sessions.create("New session", AndroApp.instance.workspace.currentProject.name)
            fillSessions()
            paintSession()
        }
        v.findViewById<View>(R.id.btnStop).setOnClickListener {
            agent.stop.set(true)
            ind.text = "stop requested"
            LogStore.add("status", "stop requested")
        }
        v.findViewById<View>(R.id.btnTmpl).setOnClickListener {
            val kinds = arrayOf("2d", "3d", "react", "kotlin", "canvas")
            AlertDialog.Builder(this).setTitle("Template").setItems(kinds) { _, i ->
                Templates.apply(kinds[i]); toast("template ${kinds[i]}")
            }.show()
        }
        v.findViewById<View>(R.id.btnSend).setOnClickListener {
            val text = input.text.toString().trim()
            if (text.isEmpty()) return@setOnClickListener
            input.setText("")
            bubble(box, "YOU", text)
            busy.visibility = View.VISIBLE
            ind.text = "running"
            streamBubble = null
            lifecycleScope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        agent.runAutonomous(text) { ev ->
                            runOnUiThread {
                                when (ev.kind) {
                                    "stream" -> {
                                        if (streamBubble == null) {
                                            val b = bubble(box, "AI*", "")
                                            streamBubble = b.findViewById(R.id.body)
                                        }
                                        streamBubble?.append(ev.text)
                                    }
                                    "think" -> bubble(box, "THINK", "", ev.text)
                                    "todo" -> todo.text = ev.text
                                    "term", "pty" -> term.append(ev.text + "\n")
                                    "round" -> rounds.text = ev.text.replace("round ", "")
                                    else -> {
                                        if (ev.kind != "stream") bubble(box, ev.kind.uppercase(), ev.text)
                                    }
                                }
                                ind.text = ev.kind
                                scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
                            }
                        }
                    }
                    sessions.save()
                    fillSessions()
                    todo.text = TodoStore.render()
                    ind.text = "idle"
                } catch (e: Exception) {
                    bubble(box, "ERROR", e.message ?: "fail")
                    LogStore.add("error", e.message ?: "fail")
                    ind.text = "error"
                }
                busy.visibility = View.GONE
                sessions.save()
            }
        }
        show(v)
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
            val name = pSpin.selectedItem?.toString() ?: return@simple
            ws.openOrCreate(name)
            refresh()
        })
        list.setOnItemClickListener { _, _, i, _ ->
            val rel = list.adapter.getItem(i) as String
            path.setText(rel); dest.setText(rel)
            val f = ws.resolve(rel)
            body.setText(if (rel.endsWith(".png") || rel.endsWith(".jpg")) "(binary ${f.length()})" else ws.read(rel))
        }
        v.findViewById<View>(R.id.btnOpenProject).setOnClickListener { ws.openOrCreate(proj.text.toString()); refresh() }
        v.findViewById<View>(R.id.btnRefreshFiles).setOnClickListener { refresh() }
        v.findViewById<View>(R.id.btnSaveFile).setOnClickListener { ws.write(path.text.toString(), body.text.toString()); refresh(); toast("saved") }
        v.findViewById<View>(R.id.btnMoveFile).setOnClickListener { ws.move(path.text.toString(), dest.text.toString()); refresh() }
        v.findViewById<View>(R.id.btnCopyFile).setOnClickListener { ws.copy(path.text.toString(), dest.text.toString()); refresh() }
        v.findViewById<View>(R.id.btnDelFile).setOnClickListener { ws.delete(path.text.toString()); body.setText(""); refresh() }
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
                    val r = withContext(Dispatchers.IO) { block() }
                    log.text = r
                    LogStore.add("github", r.take(300))
                } catch (e: Exception) {
                    log.text = e.message
                    LogStore.add("github-error", e.message ?: "")
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
                val list = gh.listRepos()
                val t = list.joinToString("\n") { it.fullName }
                runOnUiThread { repos.text = t }
                "repos ${list.size}"
            }
        }
        v.findViewById<View>(R.id.btnClone).setOnClickListener { go { gh.cloneRepo(repo.text.toString()).absolutePath } }
        v.findViewById<View>(R.id.btnBind).setOnClickListener { gh.bindRemote(repo.text.toString()); log.text = "bound" }
        v.findViewById<View>(R.id.btnPush).setOnClickListener {
            go { gh.commitAndPush(v.findViewById<EditText>(R.id.ghCommitMsg).text.toString()) }
        }
        v.findViewById<View>(R.id.btnRelease).setOnClickListener {
            val tag = v.findViewById<EditText>(R.id.ghTag).text.toString().ifBlank { "v2.2.0" }
            go { gh.createRelease(tag, "AndroIDE $tag", "release") }
        }
        v.findViewById<View>(R.id.btnWorkflow).setOnClickListener { go { gh.dispatchWorkflow("android.yml") } }
        v.findViewById<View>(R.id.btnPty).setOnClickListener {
            val cmd = v.findViewById<EditText>(R.id.ptyCmd).text.toString()
            go {
                val d = gh.dispatchWorkflow("pty.yml", mapOf("command" to cmd, "chat" to "androide-ui"))
                Thread.sleep(5000)
                val run = gh.latestRun("pty")
                val logs = if (run != null) gh.runLogs(run.getLong("id")) else "waiting for run"
                "$d\n$logs"
            }
        }
        v.findViewById<View>(R.id.btnCreateRepo).setOnClickListener {
            go {
                val r = gh.createRepo(v.findViewById<EditText>(R.id.ghNewRepo).text.toString())
                runOnUiThread { repo.setText(r.fullName) }
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
            val ws = AndroApp.instance.workspace
            val html = listOf("index.html", "game.html", "play.html").map { File(ws.currentProject, it) }.firstOrNull { it.exists() }
            if (html == null) hint.text = "No index.html"
            else {
                hint.text = html.absolutePath
                web.loadUrl("file://${html.absolutePath}")
            }
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
        val local = v.findViewById<EditText>(R.id.localBase)
        val rounds = v.findViewById<EditText>(R.id.maxRounds)
        val rot = v.findViewById<CheckBox>(R.id.autoRotate)
        val reason = v.findViewById<CheckBox>(R.id.showReason)
        fun accFill() {
            val acc = keys.accounts()
            aSpin.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, acc)
            val i = acc.indexOf(keys.account)
            if (i >= 0) aSpin.setSelection(i)
        }
        accFill()
        local.setText(keys.localBase)
        rounds.setText(keys.maxRounds.toString())
        rot.isChecked = keys.autoRotate
        reason.isChecked = keys.showReasoning
        pSpin.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, Catalog.all.map { it.label })
        val idx = Catalog.all.indexOfFirst { it.id == keys.provider }.coerceAtLeast(0)
        pSpin.setSelection(idx)
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
            keys.localBase = local.text.toString()
            keys.maxRounds = rounds.text.toString().toIntOrNull() ?: 128
            keys.autoRotate = rot.isChecked
            keys.showReasoning = reason.isChecked
            slog.text = "saved ${keys.snapshot()}"
        }
        v.findViewById<View>(R.id.btnRefreshModels).setOnClickListener {
            lifecycleScope.launch {
                val models = withContext(Dispatchers.IO) { ai.fetchModels(spec()) }
                bindModels(models)
            }
        }
        v.findViewById<View>(R.id.btnDlTiny).setOnClickListener {
            lifecycleScope.launch {
                slog.text = try {
                    withContext(Dispatchers.IO) { LocalModels.download("tinyllama-1.1b") {} }.absolutePath
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
