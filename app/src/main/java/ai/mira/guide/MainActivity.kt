package ai.mira.guide

import ai.mira.guide.beacon.BeaconScanner
import ai.mira.guide.beacon.BleBeaconScanner
import ai.mira.guide.beacon.MockBeaconScanner
import ai.mira.guide.beacon.NearestResolver
import ai.mira.guide.beacon.Sighting
import ai.mira.guide.beacon.visibleBeaconMinors
import ai.mira.guide.data.Artifact
import ai.mira.guide.data.ArtifactRepository
import ai.mira.guide.net.MiraApi
import ai.mira.guide.net.Turn
import ai.mira.guide.ui.RadarDot
import ai.mira.guide.ui.RadarView
import ai.mira.guide.voice.VoiceManager
import ai.mira.guide.wakeword.WakeWordService
import android.Manifest
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.os.Build
import android.widget.RadioButton
import android.widget.RadioGroup
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.graphics.Color
import android.widget.Button
import android.widget.EditText
import android.animation.Animator
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.res.ColorStateList
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.app.Dialog
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private lateinit var tvTitle: TextView
    private lateinit var tvMeta: TextView
    private lateinit var tvIntro: TextView
    private lateinit var tvScanStatus: TextView
    private lateinit var llBeacons: LinearLayout
    private lateinit var btnMic: Button
    private lateinit var btnAutoWalk: Button
    private lateinit var etQuestion: EditText
    private lateinit var controlsRow: LinearLayout
    private lateinit var tvSimHint: TextView
    private lateinit var btnLang: Button
    private lateinit var btnWakeChip: Button
    private val transcript = StringBuilder()
    private var tvPopupTranscript: TextView? = null
    private var toastObj: Toast? = null

    private val io = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    private var artifacts: List<Artifact> = emptyList()
    private var byMinor: Map<Int, Artifact> = emptyMap()
    private data class BeaconRow(val root: LinearLayout, val meta: TextView, val bar: ProgressBar)
    private val beaconRows = mutableMapOf<Int, BeaconRow>()
    private lateinit var llGrid: LinearLayout
    private lateinit var radarContainer: LinearLayout
    private lateinit var radarView: RadarView
    private lateinit var tvRadarSummary: TextView
    private lateinit var tvBeaconEmpty: TextView
    private lateinit var tvProximity: TextView
    private var beaconView = "list"
    private var lastSightings: List<Sighting> = emptyList()
    private data class GridTile(val root: LinearLayout, val meta: TextView, val bar: ProgressBar)
    private val gridTiles = mutableMapOf<Int, GridTile>()

    private var scanner: BeaconScanner? = null
    private val resolver = NearestResolver()
    private var current: Artifact? = null
    private var beaconMode = "mock"
    private var pendingRealMode = false
    private var artifactSinceMs = 0L

    private lateinit var voice: VoiceManager
    private val history = mutableListOf<Turn>()
    private var autoWalk = false
    private var wakeEnabled = false
    private var interests: List<String> = emptyList()
    private var lang = "en"
    private val visitedTitles = mutableListOf<String>()
    private val greetingCache = mutableMapOf<String, String>()
    private val proactiveCounts = mutableMapOf<String, Int>()
    private var dwellRunnable: Runnable? = null
    private enum class MicState { IDLE, LISTENING, THINKING, SPEAKING }
    private var micState = MicState.IDLE
    /** Bumped whenever speech is interrupted or the stop changes; stale
     *  streamed sentences from an older generation are dropped, not spoken. */
    @Volatile private var speechGen = 0
    private var pulse: AnimatorSet? = null
    private lateinit var micWrap: FrameLayout
    private lateinit var ring1: View
    private lateinit var ring2: View
    private var rings: AnimatorSet? = null
    private lateinit var heroCard: LinearLayout
    private lateinit var tvMicHint: TextView
    private val smoothRssi = mutableMapOf<Int, Float>()
    private var listOrder: List<Int> = emptyList()

    private var inConversation = false
    private var listeningForFollowUp = false
    private var followUpArmed = false
    private var cancelledListen = false

    private val autoWalkTick = object : Runnable {
        override fun run() {
            if (!autoWalk || artifacts.isEmpty()) return
            val mock = scanner as? MockBeaconScanner ?: run {
                autoWalk = false
                btnAutoWalk.text = "Auto tour: off"
                return
            }
            setPosition((mock.position + 1) % artifacts.size)
            mainHandler.postDelayed(this, 8000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!getSharedPreferences("mira", MODE_PRIVATE).getBoolean("seen", false)) {
            startActivity(Intent(this, WelcomeActivity::class.java))
            finish()
            return
        }
        setContentView(R.layout.activity_main)

        tvTitle = findViewById(R.id.tvArtifactTitle)
        tvMeta = findViewById(R.id.tvArtifactMeta)
        tvIntro = findViewById(R.id.tvIntro)
        tvScanStatus = findViewById(R.id.tvScanStatus)
        controlsRow = findViewById(R.id.controlsRow)
        tvSimHint = findViewById(R.id.tvSimHint)
        btnLang = findViewById(R.id.btnLang)
        btnLang.setOnClickListener {
            setLanguage(if (lang == "hi") "en" else "hi")
            renderLangChip()
        }
        findViewById<Button>(R.id.btnInterestsIcon).setOnClickListener { showInterestsDialog() }
        btnWakeChip = findViewById(R.id.btnWakeChip)
        btnWakeChip.setOnClickListener { setWakeEnabled(!wakeEnabled) }
        findViewById<Button>(R.id.btnChat).setOnClickListener { showConversation() }
        llBeacons = findViewById(R.id.llBeacons)
        llGrid = findViewById(R.id.llGrid)
        radarContainer = findViewById(R.id.radarContainer)
        radarView = findViewById(R.id.radarView)
        tvRadarSummary = findViewById(R.id.tvRadarSummary)
        tvBeaconEmpty = findViewById(R.id.tvBeaconEmpty)
        tvProximity = findViewById(R.id.tvProximity)
        beaconView = getSharedPreferences("mira", MODE_PRIVATE).getString("beacon_view", "list") ?: "list"
        when (beaconView) {
            "cards" -> findViewById<RadioButton>(R.id.rbViewCards).isChecked = true
            "radar" -> findViewById<RadioButton>(R.id.rbViewRadar).isChecked = true
            else -> findViewById<RadioButton>(R.id.rbViewList).isChecked = true
        }
        findViewById<RadioGroup>(R.id.rgBeaconView).setOnCheckedChangeListener { _, checkedId ->
            beaconView = when (checkedId) {
                R.id.rbViewCards -> "cards"
                R.id.rbViewRadar -> "radar"
                else -> "list"
            }
            getSharedPreferences("mira", MODE_PRIVATE).edit()
                .putString("beacon_view", beaconView).apply()
            applyBeaconView()
            renderBeacons(lastSightings)
        }
        btnMic = findViewById(R.id.btnMic)
        micWrap = findViewById(R.id.micWrap)
        ring1 = findViewById(R.id.ring1)
        ring2 = findViewById(R.id.ring2)
        heroCard = findViewById(R.id.heroCard)
        tvMicHint = findViewById(R.id.tvMicHint)
        btnAutoWalk = findViewById(R.id.btnAutoWalk)
        etQuestion = findViewById(R.id.etQuestion)

        voice = VoiceManager(this)
        voice.onPartial = {
            runOnUiThread { if (micState == MicState.LISTENING) tvMicHint.text = "You: $it" }
        }
        voice.onRms = { rms ->
            runOnUiThread {
                if (micState == MicState.LISTENING) {
                    val scale = 1f + ((rms + 2f) / 12f * 0.05f).coerceIn(0f, 0.05f)
                    btnMic.animate().scaleX(scale).scaleY(scale).setDuration(90).start()
                }
            }
        }
        voice.onFinal = { text -> runOnUiThread { onQuestion(text) } }
        voice.onListenError = { msg ->
            runOnUiThread {
                when {
                    cancelledListen -> {
                        cancelledListen = false
                        setMicState(MicState.IDLE)
                    }
                    listeningForFollowUp -> {
                        // Silence after an answer ends the conversation quietly.
                        listeningForFollowUp = false
                        inConversation = false
                        setMicState(MicState.IDLE)
                        setStatus("Tap Ask Mira, or say Hey Mira.")
                        setWakePaused(false)
                        scheduleDwell()
                    }
                    else -> {
                        setMicState(MicState.IDLE)
                        setStatus(msg)
                        setWakePaused(false)
                    }
                }
            }
        }
        voice.onSpeakDone = {
            runOnUiThread {
                if (followUpArmed) {
                    followUpArmed = false
                    startFollowUpListen()
                } else {
                    setMicState(MicState.IDLE)
                    setWakePaused(false)
                    scheduleDwell()
                }
            }
        }

        btnMic.setOnClickListener {
            // Press feedback: the whole mic cluster dips and springs back.
            micWrap.animate().scaleX(0.96f).scaleY(0.96f).setDuration(90)
                .withEndAction {
                    micWrap.animate().scaleX(1f).scaleY(1f).setDuration(160).start()
                }.start()
            buzz(15)
            when (micState) {
                MicState.SPEAKING, MicState.THINKING -> interruptAndListen()
                MicState.LISTENING, MicState.IDLE -> beginListening()
            }
        }
        findViewById<Button>(R.id.btnAsk).setOnClickListener {
            val q = etQuestion.text.toString().trim()
            if (q.isNotEmpty()) {
                etQuestion.setText("")
                onQuestion(q)
            }
        }
        findViewById<Button>(R.id.btnNext).setOnClickListener {
            if (beaconMode == "real") {
                setStatus("Real mode: physically walk near a beacon.")
                return@setOnClickListener
            }
            val mock = scanner as? MockBeaconScanner ?: return@setOnClickListener
            if (artifacts.isNotEmpty()) setPosition((mock.position + 1) % artifacts.size)
        }
        findViewById<RadioGroup>(R.id.rgBeaconMode).setOnCheckedChangeListener { _, checkedId ->
            when (checkedId) {
                R.id.rbMock -> setBeaconMode("mock")
                R.id.rbReal -> setBeaconMode("real")
            }
        }
        btnAutoWalk.setOnClickListener {
            autoWalk = !autoWalk
            btnAutoWalk.text = if (autoWalk) "Auto tour: on" else "Auto tour: off"
            if (autoWalk) mainHandler.post(autoWalkTick) else mainHandler.removeCallbacks(autoWalkTick)
        }
        interests = getSharedPreferences("mira", MODE_PRIVATE)
            .getStringSet("interests", emptySet())?.toList() ?: emptyList()
        lang = getSharedPreferences("mira", MODE_PRIVATE).getString("lang", "en") ?: "en"
        voice.applyLanguage(lang)
        renderLangChip()
        wakeEnabled = getSharedPreferences("mira", MODE_PRIVATE)
            .getBoolean("wake_enabled", false)
        renderWakeChip()

        WakeWordService.onStatus = { msg ->
            runOnUiThread { if (wakeEnabled && micState == MicState.IDLE) setStatus(msg) }
        }
        WakeWordService.onWakeWord = { runOnUiThread { if (wakeEnabled) beginListening() } }
        if (wakeEnabled) setWakeEnabled(true)

        requestNeededPermissions()
        loadArtifacts()
    }

    override fun onResume() {
        super.onResume()
        // Pick up admin edits without reinstalling: refetch on return.
        if (artifacts.isNotEmpty()) loadArtifacts()
    }

    override fun onDestroy() {
        pulse?.cancel()
        scanner?.stop()
        if (::voice.isInitialized) voice.shutdown()
        WakeWordService.onWakeWord = null
        WakeWordService.onStatus = null
        super.onDestroy()
    }

    private fun requestNeededPermissions() {
        val needed = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) needed += Manifest.permission.RECORD_AUDIO
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) needed += Manifest.permission.POST_NOTIFICATIONS
        if (needed.isNotEmpty()) ActivityCompat.requestPermissions(this, needed.toTypedArray(), 42)
    }

    private fun loadArtifacts() {
        setStatus("Loading stops...")
        io.execute {
            try {
                val list = ArtifactRepository(filesDir).load()
                runOnUiThread { onArtifactsLoaded(list) }
            } catch (e: Exception) {
                runOnUiThread { setStatus("Could not load stops: ${e.message}. Is the server running?") }
            }
        }
    }

    private fun onArtifactsLoaded(list: List<Artifact>) {
        val firstLoad = artifacts.isEmpty()
        val prevPosition = (scanner as? MockBeaconScanner)?.position ?: 0
        artifacts = list
        byMinor = list.associateBy { it.minor }
        buildBeaconRows(list)
        startBeaconScanner()
        setStatus("Loaded ${list.size} stops. Walk the gallery, or ask Mira.")
        if (list.isNotEmpty()) {
            val keep = if (firstLoad) 0 else prevPosition.coerceAtMost(list.size - 1)
            setPosition(keep)
        }
        if (!getSharedPreferences("mira", MODE_PRIVATE).getBoolean("interests_set", false)) {
            showInterestsDialog()
        }
    }

    private fun setPosition(index: Int) {
        (scanner as? MockBeaconScanner)?.position = index
    }

    private fun onSightings(sightings: List<Sighting>) {
        val minor = resolver.resolve(sightings, System.currentTimeMillis())
        runOnUiThread {
            renderBeacons(sightings)
            if (minor != null) byMinor[minor]?.let { onNearestArtifact(it) }
        }
    }

    private fun startBeaconScanner() {
        scanner?.stop()
        resolver.reset()
        scanner = if (beaconMode == "real") {
            tvScanStatus.text = "Scanning real beacons..."
            BleBeaconScanner(this).also { it.start(::onSightings) }
        } else {
            tvScanStatus.text = "Nearby beacons (${artifacts.size})"
            MockBeaconScanner(artifacts).also { it.start(::onSightings) }
        }
        // Clear stale rows at once; fresh sightings refill within a tick.
        renderBeacons(emptyList())
    }

    private fun setBeaconMode(mode: String) {
        if (mode == beaconMode && scanner != null) return
        if (mode == "real") {
            val adapter = BluetoothAdapter.getDefaultAdapter()
            if (adapter == null || !adapter.isEnabled) {
                setStatus("Bluetooth is off. Turn it on for real beacons, or stay on Simulated.")
                findViewById<RadioButton>(R.id.rbMock).isChecked = true
                return
            }
            if (!ensureBlePermissions()) return
        }
        beaconMode = mode
        applyModeControls()
        if (artifacts.isNotEmpty()) startBeaconScanner()
        setStatus(
            if (mode == "real") "Real beacon mode. Walk near a beacon."
            else "Simulated mode. Use Next artifact or Auto tour."
        )
    }

    /** Auto tour / Next only make sense with simulated beacons. */
    private fun applyModeControls() {
        val sim = beaconMode != "real"
        controlsRow.visibility = if (sim) View.VISIBLE else View.GONE
        tvSimHint.visibility = if (sim) View.VISIBLE else View.GONE
        if (!sim && autoWalk) {
            autoWalk = false
            btnAutoWalk.text = "Auto tour: off"
            mainHandler.removeCallbacks(autoWalkTick)
        }
    }

    private fun ensureBlePermissions(): Boolean {
        val perms = if (Build.VERSION.SDK_INT >= 31) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        val missing = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) return true
        pendingRealMode = true
        ActivityCompat.requestPermissions(this, missing.toTypedArray(), 43)
        return false
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != 43) return
        val granted = grantResults.isNotEmpty() &&
            grantResults.all { it == PackageManager.PERMISSION_GRANTED }
        if (granted && pendingRealMode) {
            pendingRealMode = false
            beaconMode = "real"
            if (artifacts.isNotEmpty()) startBeaconScanner()
            setStatus("Real beacon mode. Walk near a beacon.")
        } else if (!granted) {
            pendingRealMode = false
            findViewById<RadioButton>(R.id.rbMock).isChecked = true
            setStatus("Bluetooth permission denied. Staying on Simulated.")
        }
    }

    private fun zoneOf(rssi: Int): String = when {
        rssi >= -60 -> "Immediate"
        rssi >= -85 -> "Near"
        else -> "Far"
    }

    private fun distOf(rssi: Int): Double = Math.pow(10.0, (-59.0 - rssi) / 20.0)

    private fun strengthOf(rssi: Int): Int = ((rssi + 95) * 100 / 50).coerceIn(0, 100)

    private fun jumpTo(art: Artifact) {
        if (beaconMode == "real") {
            setStatus("Real mode: walk physically near “${art.title}”.")
            return
        }
        val idx = artifacts.indexOf(art)
        if (idx >= 0) setPosition(idx)
    }

    private fun applyBeaconView() {
        llBeacons.visibility = if (beaconView == "list") View.VISIBLE else View.GONE
        llGrid.visibility = if (beaconView == "cards") View.VISIBLE else View.GONE
        radarContainer.visibility = if (beaconView == "radar") View.VISIBLE else View.GONE
    }

    private fun buildBeaconRows(list: List<Artifact>) {
        llBeacons.removeAllViews()
        beaconRows.clear()
        listOrder = emptyList()
        smoothRssi.clear()
        llGrid.removeAllViews()
        gridTiles.clear()
        val teal = Color.parseColor("#0F766E")

        for (art in list) {
            val root = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(14, 12, 14, 12)
                setBackgroundResource(R.drawable.bg_beacon_row)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = 8 }
                setOnClickListener { jumpTo(art) }
            }
            val name = TextView(this).apply {
                text = art.title
                textSize = 16f
                setTextColor(Color.parseColor("#1C1917"))
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            }
            val meta = TextView(this).apply {
                textSize = 12f
                setTextColor(Color.parseColor("#6B6660"))
            }
            val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 100
                progressTintList = ColorStateList.valueOf(teal)
            }
            root.addView(name)
            root.addView(meta)
            root.addView(bar)
            llBeacons.addView(root)
            beaconRows[art.minor] = BeaconRow(root, meta, bar)
        }

        // Cards grid: two tiles per row.
        var rowLayout: LinearLayout? = null
        for ((i, art) in list.withIndex()) {
            if (i % 2 == 0) {
                rowLayout = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = 8 }
                }
                llGrid.addView(rowLayout)
            }
            val tile = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(12, 12, 12, 12)
                setBackgroundResource(R.drawable.bg_beacon_row)
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                ).apply { if (i % 2 == 0) marginEnd = 8 }
                setOnClickListener { jumpTo(art) }
            }
            val name = TextView(this).apply {
                text = art.title
                textSize = 14f
                maxLines = 2
                setTextColor(Color.parseColor("#1C1917"))
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            }
            val meta = TextView(this).apply {
                textSize = 12f
                setTextColor(Color.parseColor("#6B6660"))
            }
            val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 100
                progressTintList = ColorStateList.valueOf(teal)
            }
            tile.addView(name)
            tile.addView(meta)
            tile.addView(bar)
            rowLayout?.addView(tile)
            gridTiles[art.minor] = GridTile(tile, meta, bar)
        }
        applyBeaconView()
    }

    private fun renderBeacons(sightings: List<Sighting>) {
        lastSightings = sightings
        val seen = sightings.associateBy { it.minor }

        // Exponential smoothing: raw RSSI jitters several dB per tick, and
        // unsmoothed values make rows swap places and bars flicker.
        for (s in sightings) {
            val prev = smoothRssi[s.minor]
            smoothRssi[s.minor] =
                if (prev == null) s.rssi.toFloat() else prev + 0.35f * (s.rssi - prev)
        }
        smoothRssi.keys.retainAll(seen.keys)
        fun srssi(minor: Int, raw: Int): Int =
            smoothRssi[minor]?.let { Math.round(it) } ?: raw

        // Real mode lists only beacons the radio actually hears.
        val visible = visibleBeaconMinors(beaconMode == "real", beaconRows.keys, seen.keys)
        if (beaconMode == "real") {
            val t = "Real beacons nearby (${sightings.size} active)"
            if (tvScanStatus.text.toString() != t) tvScanStatus.text = t
            tvBeaconEmpty.text =
                "No active beacons nearby.\nMove closer to an artwork, make sure the " +
                    "beacon phone is advertising, or switch to Simulated."
        }

        // Proximity pill for the artifact you are standing at.
        current?.let { cur ->
            val s = seen[cur.minor]
            tvProximity.text = if (s == null) "Beacon ${cur.minor} · out of range"
            else {
                val r = srssi(cur.minor, s.rssi)
                "● ${zoneOf(r)} · ~${"%.1f".format(distOf(r))} m away"
            }
        }

        tvBeaconEmpty.visibility =
            if (sightings.isEmpty()) View.VISIBLE else View.GONE

        // List, nearest first; not-in-range sinks to the bottom. Views are
        // only re-ordered when the order truly changes — no per-tick churn.
        val newOrder = beaconRows.keys
            .filter { it in visible }
            .sortedByDescending {
                seen[it]?.let { s -> srssi(it, s.rssi) } ?: Int.MIN_VALUE
            }
        if (newOrder != listOrder) {
            listOrder = newOrder
            llBeacons.removeAllViews()
            for (minor in newOrder) beaconRows[minor]?.let { llBeacons.addView(it.root) }
        }
        for ((minor, row) in beaconRows) {
            val s = seen[minor]
            val text: String
            val progress: Int
            if (s == null) {
                text = "Beacon minor $minor · not in range"
                progress = 0
            } else {
                val r = srssi(minor, s.rssi)
                text = "Beacon minor $minor · $r dBm · ~${"%.1f".format(distOf(r))} m · ${zoneOf(r)}"
                progress = strengthOf(r)
            }
            if (row.meta.text.toString() != text) row.meta.text = text
            if (row.bar.progress != progress) row.bar.progress = progress
            row.root.setBackgroundResource(
                if (current?.minor == minor) R.drawable.bg_beacon_current
                else R.drawable.bg_beacon_row
            )
        }

        // Cards.
        for ((minor, tile) in gridTiles) {
            tile.root.visibility = if (minor in visible) View.VISIBLE else View.GONE
            val s = seen[minor]
            val text: String
            val progress: Int
            if (s == null) {
                text = "Not in range"
                progress = 0
            } else {
                val r = srssi(minor, s.rssi)
                text = "● ${zoneOf(r)}\n~${"%.1f".format(distOf(r))} m · $r dBm"
                progress = strengthOf(r)
            }
            if (tile.meta.text.toString() != text) tile.meta.text = text
            if (tile.bar.progress != progress) tile.bar.progress = progress
            tile.root.setBackgroundResource(
                if (current?.minor == minor) R.drawable.bg_beacon_current
                else R.drawable.bg_beacon_row
            )
        }
        // Collapse grid rows whose tiles are all hidden (real mode).
        for (i in 0 until llGrid.childCount) {
            val row = llGrid.getChildAt(i) as? LinearLayout ?: continue
            var anyVisible = false
            for (j in 0 until row.childCount) {
                if (row.getChildAt(j).visibility == View.VISIBLE) anyVisible = true
            }
            row.visibility = if (anyVisible) View.VISIBLE else View.GONE
        }

        // Radar.
        val dots = sightings.mapNotNull { s ->
            val art = byMinor[s.minor] ?: return@mapNotNull null
            RadarDot(s.minor, art.title, srssi(s.minor, s.rssi), current?.minor == s.minor)
        }
        radarView.setData(dots)
        val nearest = sightings.maxByOrNull { srssi(it.minor, it.rssi) }
        tvRadarSummary.text = when {
            nearest == null -> "No beacons in range."
            else -> {
                val r = srssi(nearest.minor, nearest.rssi)
                val art = byMinor[nearest.minor]
                "Nearest: ${art?.title ?: "Beacon ${nearest.minor}"} · " +
                    "${zoneOf(r)} · ~${"%.1f".format(distOf(r))} m" +
                    if (sightings.size < artifacts.size)
                        " · ${artifacts.size - sightings.size} out of range" else ""
            }
        }
    }

    private fun onNearestArtifact(art: Artifact) {
        if (current?.id == art.id) return
        val previous = current
        current = art
        if (previous != null && artifactSinceMs > 0) {
            val dwellS = (System.currentTimeMillis() - artifactSinceMs) / 1000.0
            if (dwellS >= 3) {
                val prevId = previous.id
                io.execute { MiraApi.logEvent("dwell", prevId, "", dwellS) }
            }
        }
        artifactSinceMs = System.currentTimeMillis()
        cancelDwell()
        inConversation = false
        listeningForFollowUp = false
        followUpArmed = false
        // Moving on silences the previous stop instantly; its queued
        // sentences are generation-stale and will be dropped.
        speechGen++
        voice.stopSpeaking()
        if (micState == MicState.SPEAKING || micState == MicState.THINKING) {
            setMicState(MicState.IDLE)
        }
        if (visitedTitles.lastOrNull() != art.title) visitedTitles += art.title
        tvTitle.text = art.title
        tvMeta.text = listOf(art.maker, art.era, art.materials).filter { it.isNotBlank() }.joinToString(" · ")
        tvIntro.text = art.shortIntro
        heroCard.alpha = 0f
        heroCard.translationY = 16f
        heroCard.animate().alpha(1f).translationY(0f).setDuration(280).start()
        buzz(30)
        requestGreeting(art)
    }

    private fun requestGreeting(art: Artifact) {
        greetingCache["${art.id}:$lang"]?.let { cached ->
            if (wakeEnabled) followUpArmed = true
            appendTranscript("Mira: $cached")
            setMicState(MicState.SPEAKING)
            voice.speak(cached)
            setWakePaused(true)
            return
        }
        if (wakeEnabled) followUpArmed = true
        setWakePaused(true)
        val gen = speechGen
        io.execute {
            var first = true
            try {
                val full = MiraApi.askStreaming(
                    art.id, "", history, "greeting",
                    visitedTitles.filter { t -> t != art.title }, interests, 0, lang
                ) { sentence ->
                    runOnUiThread {
                        if (gen != speechGen) return@runOnUiThread
                        if (first) setMicState(MicState.SPEAKING)
                        voice.speakQueued(sentence, first)
                        first = false
                    }
                }
                if (full.isNotBlank()) {
                    greetingCache["${art.id}:$lang"] = full
                    runOnUiThread { appendTranscript("Mira: $full") }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    appendTranscript("Mira: ${art.shortIntro}")
                    voice.speak(art.shortIntro)
                }
            }
        }
    }

    private fun scheduleDwell() {
        cancelDwell()
        val art = current ?: return
        if ((proactiveCounts[art.id] ?: 0) >= 3) return
        val runnable = Runnable { fireProactive(art) }
        dwellRunnable = runnable
        mainHandler.postDelayed(runnable, 20_000)
    }

    private fun cancelDwell() {
        dwellRunnable?.let { mainHandler.removeCallbacks(it) }
        dwellRunnable = null
    }

    private fun fireProactive(art: Artifact) {
        if (current?.id != art.id || voice.speaking) return
        val idx = proactiveCounts[art.id] ?: 0
        proactiveCounts[art.id] = idx + 1
        if (wakeEnabled) followUpArmed = true
        setWakePaused(true)
        setStatus("Mira has something to share...")
        val gen = speechGen
        io.execute {
            var first = true
            try {
                val full = MiraApi.askStreaming(
                    art.id, "", history, "proactive",
                    visitedTitles.filter { t -> t != art.title }, interests, idx, lang
                ) { sentence ->
                    runOnUiThread {
                        if (gen != speechGen) return@runOnUiThread
                        if (first) {
                            setMicState(MicState.SPEAKING)
                            setStatus("Mira is speaking... tap Ask Mira to interrupt.")
                        }
                        voice.speakQueued(sentence, first)
                        first = false
                    }
                }
                if (full.isNotBlank()) runOnUiThread { appendTranscript("Mira: $full") }
            } catch (e: Exception) {
                runOnUiThread { setWakePaused(false) }
            }
        }
    }

    private fun showInterestsDialog() {
        val options = arrayOf("Stories & legends", "Craft & making", "People & drama", "Quick facts")
        val checked = BooleanArray(options.size) { interests.contains(options[it]) }
        AlertDialog.Builder(this)
            .setTitle("What do you love in a museum?")
            .setMultiChoiceItems(options, checked) { _, which, isChecked -> checked[which] = isChecked }
            .setPositiveButton("Save") { _, _ ->
                interests = options.filterIndexed { i, _ -> checked[i] }
                getSharedPreferences("mira", MODE_PRIVATE).edit()
                    .putStringSet("interests", interests.toSet())
                    .putBoolean("interests_set", true)
                    .apply()
                setStatus(
                    if (interests.isEmpty()) "No interests set. Mira will keep it general."
                    else "Got it. Mira will lean into: ${interests.joinToString(", ")}."
                )
            }
            .setNegativeButton("Skip", null)
            .show()
    }

    private fun renderLangChip() {
        btnLang.text = if (lang == "hi") "हिं" else "EN"
    }

    /** Small pill above the mic: the Hey Mira switch lives with the voice UI. */
    private fun renderWakeChip() {
        if (!::btnWakeChip.isInitialized) return
        btnWakeChip.text =
            if (wakeEnabled) "● Listening for “Hey Mira” — tap to pause"
            else "“Hey Mira” listening: off — tap to turn on"
        btnWakeChip.setTextColor(
            Color.parseColor(if (wakeEnabled) "#0B3B36" else "#6B6660")
        )
    }

    private fun setLanguage(code: String) {
        if (code == lang) return
        lang = code
        getSharedPreferences("mira", MODE_PRIVATE).edit().putString("lang", lang).apply()
        voice.applyLanguage(lang)
        toast(
            if (lang == "hi") "Ab Mira Hindi mein baat karegi."
            else "Mira will speak English now."
        )
    }

    /** Conversation popup: scrollable, close with ✕ or by tapping outside. */
    private fun showConversation() {
        val dlg = Dialog(this)
        dlg.setContentView(R.layout.dialog_conversation)
        val tv = dlg.findViewById<TextView>(R.id.tvPopupTranscript)
        tvPopupTranscript = tv
        tv.text = transcript.toString().ifBlank { "No conversation yet. Ask Mira something." }
        val scroll = dlg.findViewById<ScrollView>(R.id.convScroll)
        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
        dlg.findViewById<Button>(R.id.btnCloseConv).setOnClickListener { dlg.dismiss() }
        dlg.setOnDismissListener { tvPopupTranscript = null }
        dlg.show()
        dlg.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.94).toInt(),
            (resources.displayMetrics.heightPixels * 0.72).toInt()
        )
        buzz(15)
    }

    /** Expanding rings around the mic button while Mira is active. */
    private fun startRings(periodMs: Long) {
        stopRings()
        val anims = mutableListOf<Animator>()
        for ((i, ring) in listOf(ring1, ring2).withIndex()) {
            val sx = ObjectAnimator.ofFloat(ring, "scaleX", 1f, 1.22f).apply {
                duration = periodMs
                startDelay = i * periodMs / 2
                repeatCount = ObjectAnimator.INFINITE
            }
            val sy = ObjectAnimator.ofFloat(ring, "scaleY", 1f, 1.35f).apply {
                duration = periodMs
                startDelay = i * periodMs / 2
                repeatCount = ObjectAnimator.INFINITE
            }
            val alpha = ObjectAnimator.ofFloat(ring, "alpha", 0.45f, 0f).apply {
                duration = periodMs
                startDelay = i * periodMs / 2
                repeatCount = ObjectAnimator.INFINITE
            }
            anims += listOf(sx, sy, alpha)
        }
        rings = AnimatorSet().apply { playTogether(anims); start() }
    }

    private fun stopRings() {
        rings?.cancel()
        rings = null
        for (ring in listOf(ring1, ring2)) {
            ring.alpha = 0f
            ring.scaleX = 1f
            ring.scaleY = 1f
        }
    }

    /** Single place that owns what the mic button looks like. */
    private fun setMicState(state: MicState) {
        micState = state
        stopRings()
        pulse?.cancel()
        pulse = null
        btnMic.animate().cancel()
        btnMic.scaleX = 1f
        btnMic.scaleY = 1f
        when (state) {
            MicState.IDLE -> {
                btnMic.text = "Ask Mira"
                btnMic.setBackgroundResource(R.drawable.bg_mic)
                tvMicHint.text = "Tap and ask out loud — or say “Hey Mira”"
            }
            MicState.LISTENING -> {
                btnMic.text = "Listening…"
                btnMic.setBackgroundResource(R.drawable.bg_mic_listening)
                tvMicHint.text = "Speak now — I'm listening"
                startRings(1150)
            }
            MicState.THINKING -> {
                btnMic.text = "Thinking…"
                btnMic.setBackgroundResource(R.drawable.bg_mic)
                tvMicHint.text = "Mira is thinking…"
                val sx = ObjectAnimator.ofFloat(btnMic, "scaleX", 1f, 1.03f).apply {
                    duration = 550
                    repeatMode = ObjectAnimator.REVERSE
                    repeatCount = ObjectAnimator.INFINITE
                }
                val sy = ObjectAnimator.ofFloat(btnMic, "scaleY", 1f, 1.03f).apply {
                    duration = 550
                    repeatMode = ObjectAnimator.REVERSE
                    repeatCount = ObjectAnimator.INFINITE
                }
                pulse = AnimatorSet().apply { playTogether(sx, sy); start() }
            }
            MicState.SPEAKING -> {
                btnMic.text = "Tap to interrupt"
                btnMic.setBackgroundResource(R.drawable.bg_mic_speaking)
                tvMicHint.text = "Mira is speaking — tap the button to cut in"
                startRings(1900)
            }
        }
    }

    private fun buzz(ms: Long) {
        try {
            getSystemService(Vibrator::class.java)
                ?.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (e: Exception) {
            // Haptics are a nicety, never a blocker.
        }
    }

    /** Barge-in: cut Mira off mid-sentence and listen immediately. */
    private fun interruptAndListen() {
        speechGen++
        voice.interrupt()
        followUpArmed = false
        listeningForFollowUp = false
        inConversation = true
        cancelDwell()
        setWakePaused(true)
        buzz(20)
        setMicState(MicState.LISTENING)
        setStatus("Listening... ask away.")
        voice.startListening(this)
    }

    private fun startFollowUpListen() {
        if (current == null) {
            setWakePaused(false)
            return
        }
        listeningForFollowUp = true
        inConversation = true
        setWakePaused(true)
        setMicState(MicState.LISTENING)
        setStatus("Listening... anything else?")
        voice.startListening(this)
    }

    private fun beginListening() {
        if (listeningForFollowUp) {
            // Tapping while Mira awaits a follow-up ends the conversation.
            listeningForFollowUp = false
            inConversation = false
            cancelledListen = true
            voice.stopListening()
            setMicState(MicState.IDLE)
            setWakePaused(false)
            setStatus("Conversation paused. Tap Ask Mira, or say Hey Mira.")
            scheduleDwell()
            return
        }
        if (micState == MicState.LISTENING) {
            // Tapping while listening stops cleanly.
            cancelledListen = true
            voice.interrupt()
            setMicState(MicState.IDLE)
            setWakePaused(false)
            setStatus("Tap Ask Mira, or say Hey Mira.")
            return
        }
        if (current == null) {
            setStatus("No artifact nearby yet. Walk closer to a stop first.")
            return
        }
        inConversation = true
        cancelDwell()
        setWakePaused(true)
        buzz(25)
        setMicState(MicState.LISTENING)
        setStatus("Listening... ask away.")
        voice.startListening(this)
    }

    private fun onQuestion(text: String) {
        val art = current ?: return
        cancelDwell()
        listeningForFollowUp = false
        inConversation = true
        followUpArmed = true
        val gen = ++speechGen
        appendTranscript("You: $text")
        history += Turn("user", text)
        trimHistory()
        setStatus("Mira is thinking...")
        setMicState(MicState.THINKING)
        io.execute {
            var first = true
            try {
                MiraApi.logEvent("question", art.id, text, 0.0)
                val answer = MiraApi.askStreaming(
                    art.id, text, history.dropLast(1), "question",
                    visitedTitles.filter { t -> t != art.title }, interests, 0, lang
                ) { sentence ->
                    runOnUiThread {
                        // Interrupted or moved on: never speak stale sentences.
                        if (gen != speechGen) return@runOnUiThread
                        if (first) {
                            setMicState(MicState.SPEAKING)
                            setStatus("Mira is speaking... tap Ask Mira to interrupt.")
                        }
                        voice.speakQueued(sentence, first)
                        first = false
                    }
                }
                runOnUiThread {
                    if (gen == speechGen && answer.isNotBlank()) {
                        history += Turn("assistant", answer)
                        trimHistory()
                        appendTranscript("Mira: $answer")
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    if (gen != speechGen) return@runOnUiThread
                    setMicState(MicState.IDLE)
                    setWakePaused(false)
                    setStatus("Answer failed: ${e.message}. Check server, or type the question.")
                }
            }
        }
    }

    private fun trimHistory() {
        while (history.size > 12) history.removeAt(0)
    }

    private fun setWakeEnabled(enabled: Boolean) {
        wakeEnabled = enabled
        getSharedPreferences("mira", MODE_PRIVATE).edit()
            .putBoolean("wake_enabled", enabled).apply()
        renderWakeChip()
        if (enabled) {
            startService(Intent(this, WakeWordService::class.java))
            setStatus("Wake word starting...")
        } else {
            stopService(Intent(this, WakeWordService::class.java))
            setStatus("Wake word off. Tap Ask Mira.")
        }
    }

    private fun setWakePaused(paused: Boolean) {
        if (wakeEnabled) WakeWordService.instance?.setPaused(paused)
        if (!paused && wakeEnabled && micState == MicState.IDLE) {
            setStatus("Listening for Hey Mira...")
        }
    }

    private fun appendTranscript(line: String) {
        if (transcript.isNotEmpty()) transcript.append("\n\n")
        transcript.append(line)
        tvPopupTranscript?.let { tv ->
            tv.text = transcript.toString()
            (tv.parent as? ScrollView)?.post {
                (tv.parent as ScrollView).fullScroll(ScrollView.FOCUS_DOWN)
            }
        }
    }

    private fun toast(msg: String) {
        toastObj?.cancel()
        toastObj = Toast.makeText(this, msg, Toast.LENGTH_SHORT).also { it.show() }
    }

    /**
     * Old status line, now event toasts. Pure conversation state
     * (listening/thinking/speaking) is already shown on the mic button
     * and hint, so it never becomes a toast.
     */
    private fun setStatus(text: String) {
        val t = text.lowercase()
        val isState = t.contains("listening") || t.contains("thinking") ||
            t.contains("speaking") || t.contains("something to share")
        if (!isState) toast(text)
    }
}
