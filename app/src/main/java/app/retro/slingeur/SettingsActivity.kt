package app.retro.slingeur

import android.app.Activity
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Spinner

class SettingsActivity : Activity() {

    companion object {
        const val RESULT_DISCONNECT = RESULT_FIRST_USER
    }

    private lateinit var resolutionSpinner: Spinner
    private lateinit var frameRateEdit: EditText
    private lateinit var videoBandwidthEdit: EditText
    private lateinit var videoSmoothnessEdit: EditText
    private lateinit var iframeRateEdit: EditText
    private lateinit var audioBitRateEdit: EditText
    private lateinit var aspectRatioGroup: RadioGroup
    private lateinit var aspect43: RadioButton
    private lateinit var aspect169: RadioButton
    private lateinit var aspectFit: RadioButton
    private lateinit var videoDecoderGroup: RadioGroup
    private lateinit var decoderAuto: RadioButton
    private lateinit var decoderHardware: RadioButton
    private lateinit var decoderSoftware: RadioButton
    private lateinit var decoderLegacyOmx: RadioButton
    private lateinit var pacingCheck: CheckBox
    private lateinit var lowLatencyCheck: CheckBox
    private lateinit var fullscreenCheck: CheckBox
    private lateinit var debugMessagesCheck: CheckBox
    private lateinit var displayTimeCheck: CheckBox

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        resolutionSpinner = findViewById(R.id.resolutionSpinner)
        resolutionSpinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, SlingboxResolution.OPTIONS.map { it.label }
        )
        frameRateEdit = findViewById(R.id.frameRateEdit)
        videoBandwidthEdit = findViewById(R.id.videoBandwidthEdit)
        videoSmoothnessEdit = findViewById(R.id.videoSmoothnessEdit)
        iframeRateEdit = findViewById(R.id.iframeRateEdit)
        audioBitRateEdit = findViewById(R.id.audioBitRateEdit)
        aspectRatioGroup = findViewById(R.id.aspectRatioGroup)
        aspect43 = findViewById(R.id.aspect43)
        aspect169 = findViewById(R.id.aspect169)
        aspectFit = findViewById(R.id.aspectFit)
        videoDecoderGroup = findViewById(R.id.videoDecoderGroup)
        decoderAuto = findViewById(R.id.decoderAuto)
        decoderHardware = findViewById(R.id.decoderHardware)
        decoderSoftware = findViewById(R.id.decoderSoftware)
        decoderLegacyOmx = findViewById(R.id.decoderLegacyOmx)
        pacingCheck = findViewById(R.id.pacingCheck)
        lowLatencyCheck = findViewById(R.id.lowLatencyCheck)
        fullscreenCheck = findViewById(R.id.fullscreenCheck)
        debugMessagesCheck = findViewById(R.id.debugMessagesCheck)
        displayTimeCheck = findViewById(R.id.displayTimeCheck)

        val s = AppSettings.load(this)
        resolutionSpinner.setSelection(SlingboxResolution.indexOf(s.resolution))
        frameRateEdit.setText(s.frameRate.toString())
        videoBandwidthEdit.setText(s.videoBandwidth.toString())
        videoSmoothnessEdit.setText(s.videoSmoothness.toString())
        iframeRateEdit.setText(s.iframeRate.toString())
        audioBitRateEdit.setText(s.audioBitRate.toString())
        when (s.aspectRatio) {
            "16:9" -> aspect169.isChecked = true
            "fit" -> aspectFit.isChecked = true
            else -> aspect43.isChecked = true
        }
        when (s.videoDecoder) {
            "hardware" -> decoderHardware.isChecked = true
            "software" -> decoderSoftware.isChecked = true
            "legacy" -> decoderLegacyOmx.isChecked = true
            else -> decoderAuto.isChecked = true
        }
        pacingCheck.isChecked = s.pacingEnabled
        lowLatencyCheck.isChecked = s.lowLatencyHint
        fullscreenCheck.isChecked = s.fullscreenEnabled
        debugMessagesCheck.isChecked = s.debugMessagesEnabled
        displayTimeCheck.isChecked = s.displayTimeEnabled

        findViewById<Button>(R.id.saveButton).setOnClickListener { onSave() }
        findViewById<Button>(R.id.disconnectButton).setOnClickListener { onDisconnect() }
    }


    private fun intOf(e: EditText, default: Int): Int = e.text.toString().trim().toIntOrNull() ?: default

    private fun onSave() {
        // Resolution/bitrate/etc are a quick-access edit of the box you're
        // actually watching, not a separate global setting -- write them
        // straight into the active box's own saved fields, same storage
        // "Manage Slingboxes" uses, just without the trip through that list.
        AppSettings.updateActiveBox(this) { box ->
            box.copy(
                resolution = SlingboxResolution.valueAt(resolutionSpinner.selectedItemPosition),
                frameRate = intOf(frameRateEdit, 30),
                videoBandwidth = intOf(videoBandwidthEdit, 500),
                videoSmoothness = intOf(videoSmoothnessEdit, 1),
                iframeRate = intOf(iframeRateEdit, 15),
                audioBitRate = intOf(audioBitRateEdit, 64)
            )
        }
        // The rest of Snapshot still needs host/port/password/videoSource/
        // slingboxType -- carry the (possibly just-updated) active box's
        // values through unchanged, since this screen doesn't edit those.
        val selectedBox = AppSettings.activeBox(this)
        val snapshot = AppSettings.Snapshot(
            host = selectedBox?.host ?: "",
            port = selectedBox?.port ?: 5001,
            password = selectedBox?.password ?: "",
            videoSource = selectedBox?.videoSource,
            resolution = selectedBox?.resolution ?: 5,
            frameRate = selectedBox?.frameRate ?: 30,
            videoBandwidth = selectedBox?.videoBandwidth ?: 500,
            videoSmoothness = selectedBox?.videoSmoothness ?: 1,
            iframeRate = selectedBox?.iframeRate ?: 15,
            audioBitRate = selectedBox?.audioBitRate ?: 64,
            slingboxType = selectedBox?.slingboxType ?: "solo_pro_prohd",
            aspectRatio = when (aspectRatioGroup.checkedRadioButtonId) {
                R.id.aspect169 -> "16:9"
                R.id.aspectFit -> "fit"
                else -> "4:3"
            },
            videoDecoder = when (videoDecoderGroup.checkedRadioButtonId) {
                R.id.decoderHardware -> "hardware"
                R.id.decoderSoftware -> "software"
                R.id.decoderLegacyOmx -> "legacy"
                else -> "auto"
            },
            pacingEnabled = pacingCheck.isChecked,
            lowLatencyHint = lowLatencyCheck.isChecked,
            fullscreenEnabled = fullscreenCheck.isChecked,
            debugMessagesEnabled = debugMessagesCheck.isChecked,
            displayTimeEnabled = displayTimeCheck.isChecked
        )
        AppSettings.save(this, snapshot)
        setResult(RESULT_OK)
        finish()
    }

    private fun onDisconnect() {
        setResult(RESULT_DISCONNECT)
        finish()
    }
}
