package com.readershell.manga

import android.app.Activity
import android.os.Bundle
import android.widget.TextView

// Phase 3 placeholder. Replaced once core/ is extracted and the .cbz routing lands.
class PlaceholderActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply { text = "Manga shell: Phase 3" })
    }
}
