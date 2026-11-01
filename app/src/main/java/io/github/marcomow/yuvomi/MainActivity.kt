package io.github.marcomow.yuvomi

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import io.github.marcomow.yuvomi.feature.shell.YuvomiRoot

class MainActivity : ComponentActivity() {

    /** A task id from `yuvomi-android://tasks/{id}` (TASKS-R27). */
    private val deepLinkTask = mutableStateOf<Int?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        deepLinkTask.value = taskIdOf(intent)
        val container = (application as YuvomiApp).container
        setContent {
            YuvomiRoot(container, deepLinkTask.value) { deepLinkTask.value = null }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        deepLinkTask.value = taskIdOf(intent)
    }

    private fun taskIdOf(intent: Intent?): Int? {
        val data = intent?.data ?: return null
        if (data.scheme != "yuvomi-android" || data.host != "tasks") return null
        return data.lastPathSegment?.toIntOrNull()
    }
}
