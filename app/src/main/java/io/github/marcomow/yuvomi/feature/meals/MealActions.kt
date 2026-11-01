package io.github.marcomow.yuvomi.feature.meals

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.marcomow.yuvomi.core.i18n.Strings
import io.github.marcomow.yuvomi.core.i18n.t
import io.github.marcomow.yuvomi.feature.shell.ShellContext
import io.github.marcomow.yuvomi.feature.shopping.ShoppingRoutes
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch

/** A question with a few answers (upstream's `selectModal()`): list choice and delete scope. */
@Stable
class ChoiceState {
    class Request(val title: String, val options: List<Pair<String, String>>, val answer: CompletableDeferred<String?>)

    var request by mutableStateOf<Request?>(null)
        private set

    /** Shows the question; the value of the chosen option, or null when dismissed. */
    suspend fun ask(title: String, options: List<Pair<String, String>>): String? {
        val answer = CompletableDeferred<String?>()
        request = Request(title, options, answer)
        return try {
            answer.await()
        } finally {
            request = null
        }
    }

    fun answer(value: String?) {
        request?.answer?.complete(value)
    }
}

@Composable
fun ChoiceDialog(state: ChoiceState) {
    val request = state.request ?: return
    AlertDialog(
        onDismissRequest = { state.answer(null) },
        title = { Text(request.title) },
        text = {
            Column {
                request.options.forEach { (value, label) ->
                    Text(
                        label,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { state.answer(value) }
                            .padding(vertical = 14.dp),
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = { state.answer(null) }) { Text(t("common.cancel")) } },
    )
}

/**
 * The plan's writes that end in a message, with their undo: sending ingredients to a shopping
 * list (`transferMeal()`, `announceTransfer()` in utils/kitchen-transfer.js), deleting a meal
 * (`deleteMeal()`, `scheduleUndoableDelete()`) and the random plan. They run on the app's scope,
 * so a message and its undo outlive the editor that started them.
 */
class MealActions(
    private val shell: ShellContext,
    private val strings: Strings,
    private val vm: MealsViewModel,
    private val choice: ChoiceState,
) {
    private val scope get() = shell.container.scope

    /** `mayTransferMealToShopping()`: the meal plan's write right and the shopping list's (#1290). */
    val mayTransfer: Boolean
        get() = shell.session.access("meals") == "write" && shell.session.access("shopping") == "write"

    private suspend fun say(key: String, vararg args: Pair<String, Any?>) {
        shell.notify(strings.t(key, *args))
    }

    /** `resolveShoppingTarget()`: no list is a hint with a way out, one list is the target, more ask. */
    suspend fun resolveTarget(): ShoppingListRef? {
        val lists = vm.state.value.lists
        if (lists.isEmpty()) {
            val canCreate = shell.shows("shopping") && shell.session.access("shopping") == "write"
            val go = shell.notify(strings.t("kitchen.noShoppingLists"), if (canCreate) strings.t("kitchen.createShoppingList") else null, long = true)
            if (go) shell.navigate(ShoppingRoutes.START)
            return null
        }
        if (lists.size == 1) return lists.first()
        val chosen = choice.ask(strings.t("common.toShoppingListWhich"), lists.map { it.id.toString() to it.name }) ?: return null
        return lists.firstOrNull { it.id.toString() == chosen }
    }

    fun transferMeal(mealId: Int) {
        if (!mayTransfer) return
        scope.launch {
            val target = resolveTarget() ?: return@launch
            sendMeal(mealId, target)
        }
    }

    /** The editor's own list picker already chose [target]. */
    fun transferMealTo(mealId: Int, target: ShoppingListRef) {
        if (!mayTransfer) return
        scope.launch { sendMeal(mealId, target) }
    }

    private suspend fun sendMeal(mealId: Int, target: ShoppingListRef) {
        try {
            announce(vm.transfer(mealId, target.id), target.name)
        } catch (e: Exception) {
            say(MealsErrors.key(e, mealGoneOn404 = true))
            vm.reload()
        }
    }

    /** MEALS-R23: the whole shown week, `POST /meals/week-to-shopping-list`. */
    fun transferWeek() {
        if (!mayTransfer) return
        scope.launch {
            val target = resolveTarget() ?: return@launch
            try {
                announce(vm.weekTransfer(target.id), target.name)
            } catch (e: Exception) {
                say(MealsErrors.key(e))
            }
        }
    }

    /** `announceTransfer()` with `transferMessage()`: the target list named, undo while shopping is writable. */
    private suspend fun announce(result: TransferResult, listName: String) {
        if (result.transferred <= 0) {
            say("kitchen.transferAllPresent")
            return
        }
        val done = strings.t("meals.transferSuccess", "count" to result.transferred, "list" to listName)
        val message = if (result.skipped > 0) "$done ${strings.t("kitchen.transferSkipped", "count" to result.skipped)}" else done
        val canUndo = shell.session.access("shopping") == "write" && result.addedIds.isNotEmpty()
        if (shell.notify(message, if (canUndo) strings.t("common.undo") else null, long = true) && canUndo) {
            try {
                vm.undoTransfer(result)
                say("kitchen.transferUndone")
            } catch (e: Exception) {
                say(MealsErrors.key(e))
            }
        }
    }

    /** `mealDeleteScopeQuestion()`. */
    suspend fun askDeleteScope(): String? = choice.ask(
        strings.t("meals.deleteRecurringTitle"),
        listOf(
            "single" to strings.t("meals.deleteScopeSingle"),
            "future" to strings.t("meals.deleteScopeFuture"),
            "series" to strings.t("meals.deleteScopeSeries"),
        ),
    )

    /** `deleteMeal()`: a series ends or goes at once; one meal hides and goes when the undo window closes. */
    fun delete(mealId: Int, scopeAnswer: String?) {
        if (shell.session.access("meals") != "write") return
        scope.launch {
            val meal = vm.state.value.meal(mealId) ?: return@launch
            if (meal.isRecurring) {
                val chosen = scopeAnswer ?: askDeleteScope() ?: return@launch
                if (chosen == "series" || chosen == "future") {
                    try {
                        vm.deleteSeries(mealId, chosen)
                        say(if (chosen == "future") "meals.seriesEndedToast" else "meals.seriesDeletedToast")
                    } catch (e: Exception) {
                        say(MealsErrors.key(e, mealGoneOn404 = true))
                    }
                    return@launch
                }
            }
            vm.hide(mealId)
            val undo = shell.notify(strings.t("meals.deletedToast"), strings.t("common.undo"))
            if (undo) {
                vm.unhide(mealId)
                return@launch
            }
            try {
                vm.commitDelete(mealId)
            } catch (e: Exception) {
                say(MealsErrors.key(e))
            }
        }
    }

    /** `runRandomize()`. */
    fun randomize(replaceExisting: Boolean, onDone: () -> Unit, onFailed: () -> Unit = {}) {
        scope.launch {
            val plan = vm.randomPlan(replaceExisting)
            if (plan.assignments.isEmpty()) {
                onFailed()
                say(if (plan.reason == "week_full") "meals.randomizeWeekFull" else "meals.randomizeNoRecipes")
                return@launch
            }
            try {
                val count = vm.randomize(plan, replaceExisting)
                onDone()
                say("meals.randomizeSuccess", "count" to count)
            } catch (e: Exception) {
                // A failed run leaves the dialog open for another try.
                onFailed()
                say(MealsErrors.key(e))
            }
        }
    }
}
