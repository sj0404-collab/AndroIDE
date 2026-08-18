package dev.sadat.androide.ui

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ListView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomnavigation.BottomNavigationView
import dev.sadat.androide.AndroApp
import dev.sadat.androide.R
import dev.sadat.androide.agent.AgentEngine
import dev.sadat.androide.ai.AiClient
import dev.sadat.androide.ai.Catalog
import dev.sadat.androide.github.GitHubClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : AppCompatActivity() {
    private lateinit var content: android.widget.FrameLayout
    private val inflater by lazy { LayoutInflater.from(this) }
    private val agent = AgentEngine()
    private val ai = AiClient()
    private val gh by lazy { GitHubClient(AndroApp.instance.workspace) }
    private var agentView: View? = null
    private var filesView: View? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        content = findViewById(R.id.content)
        showAgent()
        findViewById<BottomNavigationView>(R.id.nav).setOnItemSelectedListener {
            when (it.itemId) {
                R.id.tab_agent -> showAgent()
                R.id.tab_files -> showFiles()
                R.id.tab_github -> showGithub()
                R.id.tab_run -> showRun()
                R.id.tab_settings -> showSettings()
            }
            true
        }
    }

    private fun show(v: View) {
        content.removeAllViews()
        content.addView(v)
    }

    private fun showAgent() {
        val v = inflater.inflate(R.layout.tab_agent, content, false)
        agentView = v
        val log = v.findViewById<TextView>(R.id.agentLog)
        val meta = v.findViewById<TextView>(R.id.agentMeta)
        val input = v.findViewById<EditText>(R.id.agentInput)
        val keys = AndroApp.instance.keys
        meta.text = "project=${AndroApp.instance.workspace.currentProject.name}  ${keys.provider}/${keys.model}"
        v.findViewById<View>(R.id.btnSend).setOnClickListener {
            val text = input.text.toString().trim()
            if (text.isEmpty()) return@setOnClickListener
            input.setText("")
            log.append("\n\nYOU: $text\n")
            lifecycleScope.launch {
                try {
                    val out = withContext(Dispatchers.IO) {
                        agent.run(text) { ev ->
                            runOnUiThread { log.append("\n[${ev.kind}] ${ev.text}\n") }
                        }
                    }
                    log.append("\nAI:\n$out\n")
                } catch (e: Exception) {
                    log.append("\nERROR: ${e.message}\n")
                }
            }
        }
        v.findViewById<View>(R.id.btnClear).setOnClickListener {
            agent.reset()
            log.text = "session reset"
        }
        show(v)
    }

    private fun showFiles() {
        val v = inflater.inflate(R.layout.tab_files, content, false)
        filesView = v
        val ws = AndroApp.instance.workspace
        val list = v.findViewById<ListView>(R.id.fileList)
        val path = v.findViewById<EditText>(R.id.filePath)
        val body = v.findViewById<EditText>(R.id.fileBody)
        val proj = v.findViewById<EditText>(R.id.projectName)
        proj.setText(ws.currentProject.name)
        fun refresh() {
            val files = ws.listFiles().map { it.relativeTo(ws.currentProject).path }
            list.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, files)
        }
        refresh()
        list.setOnItemClickListener { _, _, i, _ ->
            val rel = list.adapter.getItem(i) as String
            path.setText(rel)
            body.setText(ws.read(rel))
        }
        v.findViewById<View>(R.id.btnOpenProject).setOnClickListener {
            ws.openOrCreate(proj.text.toString())
            refresh()
        }
        v.findViewById<View>(R.id.btnRefreshFiles).setOnClickListener { refresh() }
        v.findViewById<View>(R.id.btnSaveFile).setOnClickListener {
            ws.write(path.text.toString(), body.text.toString())
            refresh()
            toast("saved")
        }
        v.findViewById<View>(R.id.btnDelFile).setOnClickListener {
            ws.delete(path.text.toString())
            body.setText("")
            refresh()
        }
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
                    toast(r.take(80))
                } catch (e: Exception) {
                    log.text = e.message
                }
            }
        }
        v.findViewById<View>(R.id.btnSaveToken).setOnClickListener {
            AndroApp.instance.keys.githubToken = token.text.toString()
            go { val w = gh.whoami(); runOnUiThread { who.text = w }; w }
        }
        v.findViewById<View>(R.id.btnListRepos).setOnClickListener {
            go {
                val list = gh.listRepos()
                val t = list.joinToString("\n") { "${it.fullName}  ${it.defaultBranch}" }
                runOnUiThread { repos.text = t }
                "loaded ${list.size} repos"
            }
        }
        v.findViewById<View>(R.id.btnClone).setOnClickListener {
            go { gh.cloneRepo(repo.text.toString()).absolutePath }
        }
        v.findViewById<View>(R.id.btnBind).setOnClickListener {
            gh.bindRemote(repo.text.toString())
            log.text = "bound ${repo.text}"
        }
        v.findViewById<View>(R.id.btnPush).setOnClickListener {
            val msg = v.findViewById<EditText>(R.id.ghCommitMsg).text.toString()
            go { gh.commitAndPush(msg) }
        }
        v.findViewById<View>(R.id.btnRelease).setOnClickListener {
            val tag = v.findViewById<EditText>(R.id.ghTag).text.toString().ifBlank { "v2.0.0" }
            go { gh.createRelease(tag, "AndroIDE $tag", "Agent release built from device") }
        }
        v.findViewById<View>(R.id.btnWorkflow).setOnClickListener {
            go { gh.dispatchWorkflow("android.yml") }
        }
        v.findViewById<View>(R.id.btnCreateRepo).setOnClickListener {
            val name = v.findViewById<EditText>(R.id.ghNewRepo).text.toString()
            go {
                val r = gh.createRepo(name)
                runOnUiThread { repo.setText(r.fullName) }
                gh.bindRemote(r.fullName)
                "created ${r.fullName}"
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
        web.webViewClient = WebViewClient()
        web.webChromeClient = WebChromeClient()
        v.findViewById<View>(R.id.btnRunHtml).setOnClickListener {
            val ws = AndroApp.instance.workspace
            val html = listOf("index.html", "game.html", "play.html")
                .map { File(ws.currentProject, it) }
                .firstOrNull { it.exists() }
            if (html == null) {
                hint.text = "No index.html in ${ws.currentProject.name}. Ask the agent to write a playable HTML game."
            } else {
                hint.text = html.absolutePath
                web.loadUrl("file://${html.absolutePath}")
            }
        }
        show(v)
    }

    private fun showSettings() {
        val v = inflater.inflate(R.layout.tab_settings, content, false)
        val keys = AndroApp.instance.keys
        val pSpin = v.findViewById<Spinner>(R.id.providerSpin)
        val mSpin = v.findViewById<Spinner>(R.id.modelSpin)
        val hint = v.findViewById<TextView>(R.id.keyHint)
        val key = v.findViewById<EditText>(R.id.apiKey)
        val slog = v.findViewById<TextView>(R.id.setLog)
        val labels = Catalog.all.map { it.label }
        pSpin.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
        val idx = Catalog.all.indexOfFirst { it.id == keys.provider }.coerceAtLeast(0)
        pSpin.setSelection(idx)
        fun bindModels(models: List<String>) {
            mSpin.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, models)
            val mi = models.indexOf(keys.model)
            if (mi >= 0) mSpin.setSelection(mi)
        }
        fun currentSpec() = Catalog.all[pSpin.selectedItemPosition]
        bindModels(currentSpec().models)
        hint.text = currentSpec().keyHint
        key.setText(keys.getKey(currentSpec().id))
        pSpin.setOnItemSelectedListener(simple {
            val s = currentSpec()
            hint.text = s.keyHint
            key.setText(keys.getKey(s.id))
            bindModels(s.models)
        })
        v.findViewById<View>(R.id.btnSaveKeys).setOnClickListener {
            val s = currentSpec()
            keys.provider = s.id
            keys.model = mSpin.selectedItem?.toString() ?: s.defaultModel
            keys.setKey(s.id, key.text.toString())
            slog.text = "saved ${s.id} / ${keys.model}"
        }
        v.findViewById<View>(R.id.btnRefreshModels).setOnClickListener {
            val s = currentSpec()
            lifecycleScope.launch {
                val models = withContext(Dispatchers.IO) { ai.fetchModels(s) }
                bindModels(models)
                slog.text = "${models.size} models"
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
