package app.retro.slingeur

import android.app.Activity
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView

/**
 * Add/remove/edit the saved Slingbox list (host/port/password/videoSource
 * each). No fixed cap on how many -- rows are built and torn down in plain
 * Kotlin (addView/removeView) rather than a RecyclerView, since this is a
 * handful of rows at most, not a long scrolling list.
 */
class SlingboxListActivity : Activity() {

    private lateinit var rowsContainer: LinearLayout

    private class RowViews(
        val id: String,
        val container: View,
        val name: EditText,
        val host: EditText,
        val port: EditText,
        val password: EditText,
        val videoSource: EditText,
        val type: Spinner,
        val resolution: Spinner,
        val frameRate: EditText,
        val videoBandwidth: EditText,
        val videoSmoothness: EditText,
        val iframeRate: EditText,
        val audioBitRate: EditText
    )

    private val rows = ArrayList<RowViews>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_slingbox_list)
        rowsContainer = findViewById(R.id.rowsContainer)

        for (box in AppSettings.loadBoxes(this)) addRow(box)

        findViewById<Button>(R.id.addBoxButton).setOnClickListener {
            addRow(
                SlingboxProfile(
                    id = AppSettings.newBoxId(),
                    name = "Slingbox ${rows.size + 1}",
                    host = "",
                    port = 5001,
                    password = "",
                    videoSource = null,
                    slingboxType = "solo_pro_prohd",
                    resolution = 5,
                    frameRate = 30,
                    videoBandwidth = 500,
                    videoSmoothness = 1,
                    iframeRate = 15,
                    audioBitRate = 64
                )
            )
        }

        findViewById<Button>(R.id.saveBoxesButton).setOnClickListener { onSave() }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun labeled(labelText: String, row: LinearLayout) {
        val label = TextView(this)
        label.text = labelText
        label.textSize = 12f
        label.setTextColor(0xFF808080.toInt())
        row.addView(label)
    }

    private fun editText(hint: String, inputType: Int, row: LinearLayout): EditText {
        val e = EditText(this)
        e.hint = hint
        e.inputType = inputType
        e.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        row.addView(e)
        return e
    }

    private fun addRow(box: SlingboxProfile) {
        val card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL
        val cardParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        cardParams.topMargin = dp(12)
        card.layoutParams = cardParams
        card.setPadding(dp(8), dp(8), dp(8), dp(8))
        card.setBackgroundColor(0xFFEEEEEE.toInt())

        labeled("Name", card)
        val name = editText("Name", InputType.TYPE_CLASS_TEXT, card)
        name.setText(box.name)

        labeled("Host", card)
        val host = editText("Host", InputType.TYPE_TEXT_VARIATION_URI, card)
        host.setText(box.host)

        labeled("Port", card)
        val port = editText("Port", InputType.TYPE_CLASS_NUMBER, card)
        port.setText(box.port.toString())

        labeled("Password", card)
        val password = editText("Password", InputType.TYPE_TEXT_VARIATION_PASSWORD, card)
        password.setText(box.password)

        labeled("Video source (blank = leave as-is)", card)
        val videoSource = editText(
            "VideoSource (blank = leave as-is)",
            InputType.TYPE_CLASS_NUMBER, card
        )
        videoSource.setText(box.videoSource?.toString() ?: "")

        labeled("Slingbox type", card)
        val type = Spinner(this)
        type.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        type.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, SlingboxType.OPTIONS.map { it.label })
        type.setSelection(SlingboxType.indexOf(box.slingboxType))
        card.addView(type)

        labeled("Resolution", card)
        val resolution = Spinner(this)
        resolution.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        resolution.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, SlingboxResolution.OPTIONS.map { it.label })
        resolution.setSelection(SlingboxResolution.indexOf(box.resolution))
        card.addView(resolution)

        labeled("Frame rate", card)
        val frameRate = editText("FrameRate", InputType.TYPE_CLASS_NUMBER, card)
        frameRate.setText(box.frameRate.toString())

        labeled("Bitrate", card)
        val videoBandwidth = editText("VideoBandwidth (bitrate)", InputType.TYPE_CLASS_NUMBER, card)
        videoBandwidth.setText(box.videoBandwidth.toString())

        labeled("Video smoothness", card)
        val videoSmoothness = editText("VideoSmoothness", InputType.TYPE_CLASS_NUMBER, card)
        videoSmoothness.setText(box.videoSmoothness.toString())

        labeled("Keyframe interval (seconds)", card)
        val iframeRate = editText("IframeRate", InputType.TYPE_CLASS_NUMBER, card)
        iframeRate.setText(box.iframeRate.toString())

        labeled("Audio bit rate", card)
        val audioBitRate = editText("AudioBitRate", InputType.TYPE_CLASS_NUMBER, card)
        audioBitRate.setText(box.audioBitRate.toString())

        val removeButton = Button(this)
        removeButton.text = "Remove"
        val removeParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        removeParams.topMargin = dp(8)
        removeParams.gravity = Gravity.END
        removeButton.layoutParams = removeParams
        card.addView(removeButton)

        val rv = RowViews(
            box.id, card, name, host, port, password, videoSource, type,
            resolution, frameRate, videoBandwidth, videoSmoothness, iframeRate, audioBitRate
        )
        removeButton.setOnClickListener {
            rowsContainer.removeView(card)
            rows.remove(rv)
        }

        rows.add(rv)
        rowsContainer.addView(card)
    }

    private fun onSave() {
        val boxes = rows.map { r ->
            SlingboxProfile(
                id = r.id,
                name = r.name.text.toString().trim().ifEmpty { "Slingbox" },
                host = r.host.text.toString().trim(),
                port = r.port.text.toString().trim().toIntOrNull() ?: 5001,
                password = r.password.text.toString(),
                videoSource = r.videoSource.text.toString().trim().toIntOrNull(),
                slingboxType = SlingboxType.keyAt(r.type.selectedItemPosition),
                resolution = SlingboxResolution.valueAt(r.resolution.selectedItemPosition),
                frameRate = r.frameRate.text.toString().trim().toIntOrNull() ?: 30,
                videoBandwidth = r.videoBandwidth.text.toString().trim().toIntOrNull() ?: 500,
                videoSmoothness = r.videoSmoothness.text.toString().trim().toIntOrNull() ?: 1,
                iframeRate = r.iframeRate.text.toString().trim().toIntOrNull() ?: 15,
                audioBitRate = r.audioBitRate.text.toString().trim().toIntOrNull() ?: 64
            )
        }
        AppSettings.saveBoxes(this, boxes)
        // If the previously-active box was removed, or there never was one,
        // fall back to the first remaining box so there's always a sensible
        // active selection rather than a dangling id pointing at nothing.
        val activeId = AppSettings.activeBoxId(this)
        if (boxes.none { it.id == activeId }) {
            boxes.firstOrNull()?.let { AppSettings.setActiveBoxId(this, it.id) }
        }
        finish()
    }
}
