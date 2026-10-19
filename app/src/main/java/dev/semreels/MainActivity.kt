package dev.semreels

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

private const val REPO_URL = "https://github.com/FilipeeeRS/no-reels"

class MainActivity : Activity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var master: Switch
    private lateinit var serviceStatus: TextView
    private lateinit var enableButton: Button
    private lateinit var hideReels: Switch
    private lateinit var hideSearch: Switch
    private lateinit var blockSharedReels: Switch
    private lateinit var skipStoryAds: Switch
    private lateinit var skipReelAds: Switch

    // Campos de texto ligados a uma chave de preferência e seu valor padrão.
    private val textFields = mutableListOf<Triple<EditText, String, String>>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences(Prefs.FILE, MODE_PRIVATE)

        val pad = dp(16)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        master = Switch(this).apply {
            textSize = 22f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(8), 0, dp(8))
            isChecked = prefs.getBoolean(Prefs.ENABLED, true)
            setOnCheckedChangeListener { _, on ->
                prefs.edit().putBoolean(Prefs.ENABLED, on).apply()
                refreshControls()
            }
        }.also(col::addView)
        serviceStatus = note("Serviço de acessibilidade desligado.").also(col::addView)
        enableButton = button("Ativar nas configurações de acessibilidade") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }.also(col::addView)

        col.addView(divider())
        section(col, "Reels e Pesquisa")
        hideReels = option(col, "Esconder Reels", Prefs.HIDE_REELS, true)
        hideSearch = option(col, "Esconder Pesquisa", Prefs.HIDE_SEARCH, true)
        blockSharedReels = option(col, "Sair de Reels mandados por amigos", Prefs.BLOCK_SHARED_REELS, false)

        col.addView(divider())
        section(col, "Anúncios")
        skipStoryAds = option(col, "Pular anúncios nos Stories", Prefs.SKIP_STORY_ADS, false)
        skipReelAds = option(col, "Pular anúncios nos Reels", Prefs.SKIP_REEL_ADS, false)
        col.addView(divider())

        // Ajustes para quando uma atualização do Instagram quebrar a detecção; ficam recolhidos.
        val advanced = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        col.addView(TextView(this).apply {
            text = "Avançado ▸"
            alpha = 0.7f
            setPadding(0, dp(8), 0, dp(8))
            setOnClickListener {
                val open = advanced.visibility != View.VISIBLE
                advanced.visibility = if (open) View.VISIBLE else View.GONE
                text = if (open) "Avançado ▾" else "Avançado ▸"
            }
        })
        col.addView(advanced)

        advanced.addView(note("Use se a barra voltar ao normal após uma atualização do Instagram. Vários valores: separe por vírgula."))
        textField(advanced, "ID da barra", Prefs.TAB_BAR_IDS, Prefs.DEFAULT_TAB_BAR_IDS)
        textField(advanced, "ID do Reels", Prefs.REELS_IDS, Prefs.DEFAULT_REELS_IDS)
        textField(advanced, "Descrição do Reels", Prefs.REELS_DESCS, Prefs.DEFAULT_REELS_DESCS)
        textField(advanced, "ID da Pesquisa", Prefs.SEARCH_IDS, Prefs.DEFAULT_SEARCH_IDS)
        textField(advanced, "Descrição da Pesquisa", Prefs.SEARCH_DESCS, Prefs.DEFAULT_SEARCH_DESCS)
        textField(advanced, "Rótulos de anúncio", Prefs.AD_LABELS, Prefs.DEFAULT_AD_LABELS)

        advanced.addView(buttonRow(
            button("Salvar") { save() },
            button("Padrões") { restoreDefaults() },
        ))
        advanced.addView(buttonRow(
            button("Gerar diagnóstico") {
                prefs.edit().putBoolean(Prefs.DUMP_REQUESTED, true).apply()
                toast("Abra o Instagram na tela inicial. O arquivo será salvo automaticamente.")
            },
            button("Copiar") { copyLastDump() },
        ))

        setContentView(ScrollView(this).apply {
            fitsSystemWindows = true
            addView(col)
        })
        loadSelectors()
    }

    override fun onResume() {
        super.onResume()
        refreshControls()
    }

    private fun refreshControls() {
        val service = isServiceEnabled()
        val on = master.isChecked
        master.text = if (on) "No Reels ativado" else "No Reels desativado"
        master.isEnabled = service
        serviceStatus.visibility = if (service) View.GONE else View.VISIBLE
        enableButton.visibility = if (service) View.GONE else View.VISIBLE
        for (s in listOf(hideReels, hideSearch, blockSharedReels, skipStoryAds, skipReelAds)) s.isEnabled = service && on
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add("GitHub").apply {
            setIcon(R.drawable.ic_github)
            setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            setOnMenuItemClickListener {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(REPO_URL)))
                true
            }
        }
        return true
    }

    private fun loadSelectors() {
        for ((field, key, default) in textFields) field.setText(prefs.getString(key, default))
    }

    private fun save() {
        prefs.edit().apply {
            for ((field, key, _) in textFields) putString(key, field.text.toString().trim())
        }.apply()
        toast("Salvo")
    }

    private fun restoreDefaults() {
        prefs.edit().apply {
            for ((_, key, _) in textFields) remove(key)
        }.apply()
        loadSelectors()
        toast("Padrões restaurados")
    }

    private fun copyLastDump() {
        val last = getExternalFilesDir("dumps")?.listFiles()?.maxByOrNull { it.lastModified() }
        if (last == null) {
            toast("Nenhum diagnóstico ainda")
            return
        }
        getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText(last.name, last.readText()))
        toast("Copiado: ${last.name}")
    }

    private fun isServiceEnabled(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?: return false
        val me = ComponentName(this, ReelsBlockerService::class.java)
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
    }

    private fun textField(parent: LinearLayout, label: String, key: String, default: String) {
        textFields += Triple(labeledEdit(parent, label), key, default)
    }

    private fun labeledEdit(parent: LinearLayout, label: String): EditText {
        parent.addView(TextView(this).apply {
            text = label
            setPadding(0, dp(8), 0, 0)
        })
        return EditText(this).apply { isSingleLine = true }.also(parent::addView)
    }

    private fun option(parent: LinearLayout, label: String, key: String, default: Boolean): Switch =
        Switch(this).apply {
            text = label
            textSize = 17f
            setPadding(0, dp(14), 0, dp(14))
            isChecked = prefs.getBoolean(key, default)
            setOnCheckedChangeListener { _, on -> prefs.edit().putBoolean(key, on).apply() }
        }.also(parent::addView)

    private fun divider() = View(this).apply {
        setBackgroundColor(0x33888888)
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)).apply {
            topMargin = dp(16)
            bottomMargin = dp(8)
        }
    }

    private fun section(parent: LinearLayout, title: String) {
        parent.addView(TextView(this).apply {
            text = title.uppercase()
            textSize = 12f
            alpha = 0.6f
            letterSpacing = 0.08f
            setPadding(0, dp(8), 0, 0)
        })
    }

    private fun note(text: String) = TextView(this).apply {
        this.text = text
        alpha = 0.7f
    }

    // Botões dividindo a largura, com respiro entre eles e acima da linha.
    private fun buttonRow(vararg buttons: Button) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(0, dp(12), 0, 0)
        buttons.forEachIndexed { i, b ->
            addView(b, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                if (i > 0) marginStart = dp(12)
            })
        }
    }

    private fun button(text: String, onClick: (View) -> Unit) = Button(this).apply {
        this.text = text
        setOnClickListener(onClick)
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
