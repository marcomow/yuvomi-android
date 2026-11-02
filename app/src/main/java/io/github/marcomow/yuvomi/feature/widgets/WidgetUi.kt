package io.github.marcomow.yuvomi.feature.widgets

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.Action
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.CheckBox
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import io.github.marcomow.yuvomi.core.api.TasksApi
import io.github.marcomow.yuvomi.feature.shell.Routes
import io.github.marcomow.yuvomi.feature.shopping.ShoppingApi
import io.github.marcomow.yuvomi.feature.shopping.ShoppingRoutes
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private val ROW_ID = ActionParameters.Key<Int>("row")

/** WIDGETS-R2: the member's open tasks, overdue and due today first; the box completes one. */
class TasksWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val first = Widgets.tasks(context)
        provideContent {
            val version = currentState(Widgets.VERSION)
            val start = remember { version }
            var snapshot by remember { mutableStateOf(first) }
            LaunchedEffect(version) { if (version != start) snapshot = Widgets.tasks(context) }
            val strings = remember { Widgets.container(context).strings.value }
            WidgetFrame(
                snapshot,
                open = actionStartActivity(Widgets.open(context, if (snapshot.signedIn) "tasks" else null)),
                openRow = { actionStartActivity(Widgets.open(context, Routes.task(it.id))) },
                check = { actionRunCallback<CompleteTask>(actionParametersOf(ROW_ID to it.id)) },
                checkLabel = { strings?.t("tasks.markDone", "title" to it.title) ?: it.title },
                staleLabel = strings?.t("android.widgets.stale").orEmpty(),
                moreLabel = { n -> strings?.t("android.widgets.more", "count" to n).orEmpty() },
            )
        }
    }
}

class TasksWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TasksWidget()
}

/** Completes a task from the widget; when the server refuses (locked, offline), the app opens on it. */
class CompleteTask : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val id = parameters[ROW_ID] ?: return
        val c = Widgets.container(context)
        val ok = runCatching { c.session.connection!!.tasks.setStatus(id, "done") }.isSuccess
        if (!ok) context.startActivity(Widgets.open(context, Routes.task(id)))
        Widgets.refresh(context, TasksWidget())
    }
}

/** WIDGETS-R3: the open items of the shopping list last opened in the app; the box checks one off. */
class ShoppingWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val first = Widgets.shopping(context)
        provideContent {
            val version = currentState(Widgets.VERSION)
            val start = remember { version }
            var snapshot by remember { mutableStateOf(first) }
            LaunchedEffect(version) { if (version != start) snapshot = Widgets.shopping(context) }
            val strings = remember { Widgets.container(context).strings.value }
            val listRoute = ShoppingRoutes.list(snapshot.listId)
            WidgetFrame(
                snapshot,
                open = actionStartActivity(Widgets.open(context, if (snapshot.signedIn) listRoute else null)),
                openRow = { actionStartActivity(Widgets.open(context, listRoute)) },
                check = { actionRunCallback<CheckItem>(actionParametersOf(ROW_ID to it.id)) },
                checkLabel = { strings?.t("shopping.markDoneLabel", "name" to it.title) ?: it.title },
                staleLabel = strings?.t("android.widgets.stale").orEmpty(),
                moreLabel = { n -> strings?.t("android.widgets.more", "count" to n).orEmpty() },
            )
        }
    }
}

class ShoppingWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ShoppingWidget()
}

/** Checks a shopping item off from the widget, as ticking it in the list does. */
class CheckItem : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val id = parameters[ROW_ID] ?: return
        val c = Widgets.container(context)
        runCatching { ShoppingApi(c.session.connection!!.client).updateItem(id, buildJsonObject { put("is_checked", 1) }) }
        Widgets.refresh(context, ShoppingWidget())
    }
}

@Composable
private fun WidgetFrame(
    snapshot: WidgetSnapshot,
    open: Action,
    openRow: (WidgetRow) -> Action,
    check: (WidgetRow) -> Action,
    checkLabel: (WidgetRow) -> String,
    staleLabel: String,
    moreLabel: (Int) -> String,
) {
    val colors = GlanceTheme.colors
    GlanceTheme {
        Column(
            GlanceModifier.fillMaxSize().background(colors.widgetBackground).cornerRadius(20.dp).padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Row(GlanceModifier.fillMaxWidth().clickable(open), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    snapshot.title,
                    style = TextStyle(color = colors.onSurface, fontSize = 16.sp, fontWeight = FontWeight.Bold),
                    maxLines = 1,
                    modifier = GlanceModifier.defaultWeight(),
                )
                if (snapshot.stale) Text(staleLabel, style = TextStyle(color = colors.onSurfaceVariant, fontSize = 12.sp))
            }
            Spacer(GlanceModifier.height(6.dp))
            val message = snapshot.message
            if (message != null) {
                Text(
                    message,
                    style = TextStyle(color = colors.onSurfaceVariant, fontSize = 14.sp),
                    modifier = GlanceModifier.fillMaxWidth().clickable(open).padding(vertical = 8.dp),
                )
            } else {
                LazyColumn(GlanceModifier.fillMaxSize()) {
                    items(snapshot.rows, itemId = { it.id.toLong() }) { row ->
                        Row(GlanceModifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (snapshot.canWrite) {
                                val label = checkLabel(row)
                                CheckBox(
                                    checked = false,
                                    onCheckedChange = check(row),
                                    modifier = GlanceModifier.semantics { contentDescription = label },
                                )
                            }
                            Column(GlanceModifier.defaultWeight().clickable(openRow(row)).padding(vertical = 4.dp)) {
                                Text(row.title, style = TextStyle(color = colors.onSurface, fontSize = 14.sp), maxLines = 2)
                                row.detail?.let {
                                    Text(it, style = TextStyle(color = if (row.urgent) colors.error else colors.onSurfaceVariant, fontSize = 12.sp), maxLines = 1)
                                }
                            }
                        }
                    }
                    if (snapshot.more > 0) {
                        item {
                            Text(
                                moreLabel(snapshot.more),
                                style = TextStyle(color = colors.primary, fontSize = 13.sp),
                                modifier = GlanceModifier.fillMaxWidth().clickable(open).padding(vertical = 6.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}
