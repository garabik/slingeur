package app.retro.slingeur

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Toast

/**
 * Connect screen: pick which saved Slingbox to use (added/edited via "Manage
 * Slingboxes...") and tap Play. Everything else (stream parameters, decoder
 * choice, aspect ratio, pacing) lives in [SettingsActivity], reachable via
 * the Settings button here or from the live player's own overlay.
 */
class MainActivity : Activity() {

    private lateinit var boxGroup: RadioGroup
    private lateinit var playLiveButton: Button
    private lateinit var settingsButton: Button
    private var boxes: List<SlingboxProfile> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        boxGroup = findViewById(R.id.boxGroup)
        playLiveButton = findViewById(R.id.playLiveButton)
        settingsButton = findViewById(R.id.settingsButton)

        findViewById<Button>(R.id.manageBoxesButton).setOnClickListener {
            startActivity(Intent(this, SlingboxListActivity::class.java))
        }
        playLiveButton.setOnClickListener { onPlayLive() }
        settingsButton.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
    }

    override fun onResume() {
        super.onResume()
        // Refresh in case Manage Slingboxes (or a previous player session's
        // own route there) changed the list while this activity was paused.
        populateBoxes()
    }

    private fun populateBoxes() {
        boxes = AppSettings.loadBoxes(this)
        boxGroup.removeAllViews()
        val activeId = AppSettings.activeBoxId(this)
        for (box in boxes) {
            val rb = RadioButton(this)
            rb.id = android.view.View.generateViewId()
            rb.text = "${box.name}  (${box.host}:${box.port})"
            rb.tag = box.id
            boxGroup.addView(rb)
            if (box.id == activeId) rb.isChecked = true
        }
        // Nothing was marked active (first run, or the active one got
        // removed) -- default to the first box rather than leaving the
        // whole group unselected.
        if (boxGroup.checkedRadioButtonId == -1 && boxGroup.childCount > 0) {
            (boxGroup.getChildAt(0) as RadioButton).isChecked = true
        }
        playLiveButton.isEnabled = boxes.isNotEmpty()
        // Default focus on Play, so a D-pad/remote OK or Enter press starts
        // playback immediately on launch without needing to navigate down to
        // it first. Falls back to "Manage Slingboxes..." when there's
        // nothing to play yet, since Play is disabled in that case.
        if (boxes.isNotEmpty()) {
            playLiveButton.requestFocus()
        } else {
            findViewById<Button>(R.id.manageBoxesButton).requestFocus()
        }
    }

    private fun onPlayLive() {
        val checkedId = boxGroup.checkedRadioButtonId
        val checked = boxGroup.findViewById<RadioButton>(checkedId)
        val boxId = checked?.tag as? String
        if (boxId == null) {
            Toast.makeText(this, "Add a Slingbox first", Toast.LENGTH_SHORT).show()
            return
        }
        AppSettings.setActiveBoxId(this, boxId)
        startActivity(Intent(this, PlayerActivity::class.java))
    }
}
