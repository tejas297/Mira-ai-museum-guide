package ai.mira.guide

import ai.mira.guide.beacon.BeaconScanner
import ai.mira.guide.beacon.BleBeaconScanner
import ai.mira.guide.beacon.MockBeaconScanner
import ai.mira.guide.beacon.NearestResolver
import ai.mira.guide.beacon.Sighting
import ai.mira.guide.data.Artifact
import ai.mira.guide.data.ArtifactRepository
import ai.mira.guide.net.MiraApi
import ai.mira.guide.net.Turn
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
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private lateinit var tvStatus: TextView
    private lateinit var tvTitle: TextView
    private lateinit var tvMeta: TextView
    private lateinit var tvIntro: TextView
    private lateinit var tvTranscript: TextView
    private lateinit var tvScanStatus: TextView
    private lateinit var llBeacons: LinearLayout
    private lateinit var btnMic: Button
    private lateinit var btnAutoWalk: Button
    private lateinit var etQuestion: EditText
    private lateinit var swWake: Switch

    private val io = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    private var artifacts: List<Artifact> = emptyList()
    private var byMinor: Map<Int, Artifact> = emptyMap()
    private data class BeaconRow(val root: LinearLayout, val meta: TextView, val bar: ProgressBar)
    private val beaconRows = mutableMapOf<Int, BeaconRow>()
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
    private val visitedTitles = mutableListOf<String>()
    private val greetingCache = mutableMapOf<String, String>()
    private val proactiveCounts = mutableMapOf<String, Int>()
    private var dwellRunnable: Runnable? = null
    private var inConversation = false
    private var listeningForFollowUp = false
    private var followUpArmed = false
    private var cancelledListen = false

    private val autoWalkTick = object : Runnable {
        override fun run() {
            if (!autoWalk || artifacts.isEmpty()) return
            val mock = scanner as? MockBeaconScanner ?: run {
                autoWalk = false
                btnAutoWalk.text = "Auto-walk: off"
                return
            }
            setPosition((mock.position + 1) % artifacts.size)
            mainHandler.postDelayed(this, 8000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)
        tvTitle = findViewById(R.id.tvArtifactTitle)
        tvMeta = findViewById(R.id.tvArtifactMeta)
        tvIntro = findViewById(R.id.tvIntro)
        tvTranscript = findViewById(R.id.tvTranscript)
        tvScanStatus = findViewById(R.id.tvScanStatus)
        llBeacons = findViewById(R.id.llBeacons)
        btnMic = findViewById(R.id.btnMic)
        btnAutoWalk = findViewById(R.id.btnAutoWalk)
        etQuestion = findViewById(R.id.etQuestion)
        swWake = findViewById(R.id.swWake)

        voice = VoiceManager(this)
        voice.onPartial = { runOnUiThread { setStatus("You: $it") } }
        voice.onFinal = { text -> runOnUiThread { onQuestion(text) } }
        voice.onListenError = { msg ->
            runOnUiThread {
                when {
                    cancelledListen -> cancelledListen = false
                    listeningForFollowUp -> {
                        // Silence after an answer ends the conversation quietly.
                        listeningForFollowUp = false
                        inConversation = false
                        setStatus("Tap Ask Mira, or say Hey Mira.")
                        setWakePaused(false)
                        scheduleDwell()
                    }
                    else -> {
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
                    setWakePaused(false)
                    scheduleDwell()
                }
            }
        }

        btnMic.setOnClickListener { beginListening() }
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
            btnAutoWalk.text = if (autoWalk) "Auto-walk: on" else "Auto-walk: off"
            if (autoWalk) mainHandler.post(autoWalkTick) else mainHandler.removeCallbacks(autoWalkTick)
        }
        swWake.setOnCheckedChangeListener { _, checked -> setWakeEnabled(checked) }
        findViewById<Button>(R.id.btnInterests).setOnClickListener { showInterestsDialog() }
        interests = getSharedPreferences("mira", MODE_PRIVATE)
            .getStringSet("interests", emptySet())?.toList() ?: emptyList()

        WakeWordService.onStatus = { msg -> runOnUiThread { if (wakeEnabled) setStatus(msg) } }
        WakeWordService.onWakeWord = { runOnUiThread { if (wakeEnabled) beginListening() } }

        requestNeededPermissions()
        loadArtifacts()
    }

    override fun onResume() {
        super.onResume()
        // Pick up admin edits without reinstalling: refetch on return.
        if (artifacts.isNotEmpty()) loadArtifacts()
    }

    override fun onDestroy() {
        scanner?.stop()
        voice.shutdown()
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
        if (artifacts.isNotEmpty()) startBeaconScanner()
        setStatus(
            if (mode == "real") "Real beacon mode. Walk near a beacon."
            else "Simulated mode. Use Next artifact or Auto tour."
        )
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

    private fun buildBeaconRows(list: List<Artifact>) {
        llBeacons.removeAllViews()
        beaconRows.clear()
        for (art in list) {
            val root = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(10, 8, 10, 8)
            }
            val name = TextView(this).apply {
                text = art.title
                textSize = 15f
            }
            val meta = TextView(this).apply { textSize = 12f }
            val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 100
            }
            root.addView(name)
            root.addView(meta)
            root.addView(bar)
            llBeacons.addView(root)
            beaconRows[art.minor] = BeaconRow(root, meta, bar)
        }
    }

    private fun renderBeacons(sightings: List<Sighting>) {
        val seen = sightings.associateBy { it.minor }
        for ((minor, row) in beaconRows) {
            val art = byMinor[minor] ?: continue
            val s = seen[minor]
            if (s == null) {
                row.bar.progress = 0
                row.meta.text = "Beacon minor $minor · not in range"
                row.root.setBackgroundColor(Color.TRANSPARENT)
                continue
            }
            val strength = ((s.rssi + 95) * 100 / 50).coerceIn(0, 100)
            val distM = Math.pow(10.0, (-59.0 - s.rssi) / 20.0)
            val zone = when {
                s.rssi >= -60 -> "Immediate"
                s.rssi >= -85 -> "Near"
                else -> "Far"
            }
            row.bar.progress = strength
            row.meta.text = "Beacon minor $minor · ${s.rssi} dBm · ~${"%.1f".format(distM)} m · $zone"
            val isCurrent = current?.minor == s.minor
            row.root.setBackgroundColor(
                if (isCurrent) Color.parseColor("#DCEFE2") else Color.TRANSPARENT
            )
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
        if (visitedTitles.lastOrNull() != art.title) visitedTitles += art.title
        tvTitle.text = art.title
        tvMeta.text = listOf(art.maker, art.era, art.materials).filter { it.isNotBlank() }.joinToString(" · ")
        tvIntro.text = art.shortIntro
        requestGreeting(art)
    }

    private fun requestGreeting(art: Artifact) {
        greetingCache[art.id]?.let { cached ->
            if (wakeEnabled) followUpArmed = true
            appendTranscript("Mira: $cached")
            voice.speak(cached)
            setWakePaused(true)
            return
        }
        if (wakeEnabled) followUpArmed = true
        setWakePaused(true)
        io.execute {
            var first = true
            try {
                val full = MiraApi.askStreaming(
                    art.id, "", history, "greeting",
                    visitedTitles.filter { t -> t != art.title }, interests, 0
                ) { sentence ->
                    runOnUiThread {
                        voice.speakQueued(sentence, first)
                        first = false
                    }
                }
                if (full.isNotBlank()) {
                    greetingCache[art.id] = full
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
        io.execute {
            var first = true
            try {
                val full = MiraApi.askStreaming(
                    art.id, "", history, "proactive",
                    visitedTitles.filter { t -> t != art.title }, interests, idx
                ) { sentence ->
                    runOnUiThread {
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

    private fun startFollowUpListen() {
        if (current == null) {
            setWakePaused(false)
            return
        }
        listeningForFollowUp = true
        inConversation = true
        setWakePaused(true)
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
            setWakePaused(false)
            setStatus("Conversation paused. Tap Ask Mira, or say Hey Mira.")
            scheduleDwell()
            return
        }
        if (current == null) {
            setStatus("No artifact nearby yet. Walk closer to a stop first.")
            return
        }
        inConversation = true
        cancelDwell()
        setWakePaused(true)
        setStatus("Listening... ask away.")
        voice.startListening(this)
    }

    private fun onQuestion(text: String) {
        val art = current ?: return
        cancelDwell()
        listeningForFollowUp = false
        inConversation = true
        followUpArmed = true
        appendTranscript("You: $text")
        history += Turn("user", text)
        trimHistory()
        setStatus("Mira is thinking...")
        btnMic.isEnabled = false
        io.execute {
            var first = true
            try {
                MiraApi.logEvent("question", art.id, text, 0.0)
                val answer = MiraApi.askStreaming(
                    art.id, text, history.dropLast(1), "question",
                    visitedTitles.filter { t -> t != art.title }, interests, 0
                ) { sentence ->
                    runOnUiThread {
                        if (first) setStatus("Mira is speaking... tap Ask Mira to interrupt.")
                        voice.speakQueued(sentence, first)
                        first = false
                    }
                }
                runOnUiThread {
                    btnMic.isEnabled = true
                    if (answer.isNotBlank()) {
                        history += Turn("assistant", answer)
                        trimHistory()
                        appendTranscript("Mira: $answer")
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    btnMic.isEnabled = true
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
        if (!paused && wakeEnabled) setStatus("Listening for Hey Mira...")
    }

    private fun appendTranscript(line: String) {
        val prev = tvTranscript.text.toString()
        tvTranscript.text = if (prev.isBlank()) line else "$prev\n\n$line"
        val scroll = tvTranscript.parent?.parent as? ScrollView
        scroll?.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun setStatus(text: String) {
        tvStatus.text = text
    }
}
