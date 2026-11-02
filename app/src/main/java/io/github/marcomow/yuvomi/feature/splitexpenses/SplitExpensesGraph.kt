package io.github.marcomow.yuvomi.feature.splitexpenses

import android.net.Uri
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument

/**
 * The budget page's SplitExpenses tab (docs/spec/splitexpenses.md). Not a module of its own: Budget's
 * feature registers these destinations, and their routes start with `budget` so the
 * Budget entry stays selected.
 */
object SplitExpensesGraph {
    fun register(graph: NavGraphBuilder, nav: NavHostController) {
        val splitNav = SplitNav(
            back = { nav.popBackStack() },
            expense = { nav.navigate(SplitExpensesRoutes.expense(it)) },
            recurring = { nav.navigate(SplitExpensesRoutes.recurring(it)) },
            group = { nav.navigate(SplitExpensesRoutes.groupEditor(it)) },
            settle = { nav.navigate(SplitExpensesRoutes.SETTLE) },
            guest = { nav.navigate(SplitExpensesRoutes.GUEST) },
        )
        graph.composable(SplitExpensesRoutes.MAIN, arguments = listOf(navArgument("group") { type = NavType.IntType; defaultValue = -1 })) { entry ->
            SplitScreen(entry.arguments?.getInt("group")?.takeIf { it > 0 }, splitNav)
        }
        graph.composable(
            SplitExpensesRoutes.EXPENSE,
            arguments = listOf(
                navArgument("id") { type = NavType.IntType; defaultValue = -1 },
                navArgument("title") { type = NavType.StringType; nullable = true; defaultValue = null },
                navArgument("amount") { type = NavType.StringType; nullable = true; defaultValue = null },
                navArgument("date") { type = NavType.StringType; nullable = true; defaultValue = null },
                navArgument("currency") { type = NavType.StringType; nullable = true; defaultValue = null },
                navArgument("people") { type = NavType.StringType; nullable = true; defaultValue = null },
            ),
        ) { entry ->
            val a = entry.arguments
            val prefill = a?.getString("title")?.let { title ->
                SplitPrefill(
                    title = title,
                    amount = a.getString("amount").orEmpty(),
                    date = a.getString("date"),
                    currency = a.getString("currency"),
                    participantIds = a.getString("people").orEmpty().split(',').mapNotNull { it.trim().toIntOrNull() },
                )
            }
            ExpenseEditorScreen(a?.getInt("id")?.takeIf { it > 0 }, prefill, close = { nav.popBackStack() })
        }
        graph.composable(SplitExpensesRoutes.RECURRING, arguments = listOf(navArgument("id") { type = NavType.IntType; defaultValue = -1 })) { entry ->
            RecurringEditorScreen(entry.arguments?.getInt("id")?.takeIf { it > 0 }, close = { nav.popBackStack() })
        }
        graph.composable(SplitExpensesRoutes.GROUP, arguments = listOf(navArgument("id") { type = NavType.IntType; defaultValue = -1 })) { entry ->
            GroupEditorScreen(entry.arguments?.getInt("id")?.takeIf { it > 0 }, close = { nav.popBackStack() })
        }
        graph.composable(SplitExpensesRoutes.SETTLE) { SettlementScreen(close = { nav.popBackStack() }) }
        graph.composable(SplitExpensesRoutes.GUEST) { GuestScreen(close = { nav.popBackStack() }) }
    }
}

/** SplitExpenses's routes; other code links here through these helpers only. */
object SplitExpensesRoutes {
    const val START = "budget/split"
    internal const val MAIN = "budget/split?group={group}"
    internal const val EXPENSE = "budget/split/expense?id={id}&title={title}&amount={amount}&date={date}&currency={currency}&people={people}"
    internal const val RECURRING = "budget/split/recurring?id={id}"
    internal const val GROUP = "budget/split/group?id={id}"
    const val SETTLE = "budget/split/settle"
    const val GUEST = "budget/split/guest"

    /** The tab on one group (the web's `?group=<id>`). */
    fun group(id: Int) = "budget/split?group=$id"

    /** A new expense in the chosen group (the budget FAB on this tab), or expense [id]. */
    fun expense(id: Int? = null) = if (id == null) "budget/split/expense" else "budget/split/expense?id=$id"

    /**
     * The budget's hand-over (`prefillSplitExpense()`, #1057): a new expense with title, amount
     * (decimal string), date, currency and the entry's responsible people as participants. The
     * dialog is not skipped: split, currency and group are confirmed on the expense screen.
     */
    fun newExpense(title: String, amount: String, date: String?, currency: String?, participantIds: List<Int>) = buildString {
        append("budget/split/expense?title=").append(Uri.encode(title))
        append("&amount=").append(Uri.encode(amount))
        if (date != null) append("&date=").append(Uri.encode(date))
        if (currency != null) append("&currency=").append(Uri.encode(currency))
        if (participantIds.isNotEmpty()) append("&people=").append(participantIds.joinToString(","))
    }

    internal fun recurring(id: Int?) = if (id == null) "budget/split/recurring" else "budget/split/recurring?id=$id"
    internal fun groupEditor(id: Int?) = if (id == null) "budget/split/group" else "budget/split/group?id=$id"
}
