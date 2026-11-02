package io.github.marcomow.yuvomi.feature.housekeeping

import io.github.marcomow.yuvomi.core.i18n.Strings
import java.text.DecimalFormatSymbols
import java.text.NumberFormat
import java.time.YearMonth
import java.util.Currency
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

/**
 * The decisions of `public/pages/housekeeping.js` that need no screen: labels, the session
 * line, the chart, the areas, the amount fields and the bodies the page sends
 * (docs/spec/housekeeping.md). Pure Kotlin, unit tested.
 */
object HousekeepingRules {

    const val NAME_MAX = 200
    const val AREA_MAX = 100
    const val WORKER_NAME_MAX = 128
    const val USERNAME_MAX = 64
    const val NOTES_MAX = 5000

    /** `USER_COLORS` of `public/utils/color.js`, the swatches of the colour picker. */
    val COLORS = listOf("#78808C", "#3B82F6", "#16A34A", "#D97706", "#059669", "#D946EF", "#EF4444", "#0891B2", "#EA580C", "#EC4899")

    /** `USER_COLOR_DEFAULT`. */
    const val COLOR_DEFAULT = "#0891B2"

    val SCHEDULES = listOf("daily", "twice_monthly", "monthly")

    private val USERNAME = Regex("^[a-zA-Z0-9._-]{3,64}$")

    // ── Amounts (R20) ──

    /** `amountsHidden()`: no right to see amounts, or the server sent `null` for this one. */
    fun amountsHidden(canSeeAmounts: Boolean, value: Any?): Boolean = !canSeeAmounts || value == null

    /** `canSeeHousekeepingAmounts()`: admins always; otherwise the capability, allowed unless `none`. */
    fun canSeeAmounts(admin: Boolean, capabilities: Map<String, String>): Boolean =
        admin || (capabilities["housekeeping_view_amounts"] ?: "allow") != "none"

    fun currencyDigits(currency: String): Int = runCatching { Currency.getInstance(currency).defaultFractionDigits }.getOrDefault(2).coerceAtLeast(0)

    /** `money()`: the amount in the household currency, in the UI language. */
    fun money(value: Double?, currency: String, locale: String): String = format(value ?: 0.0, currency, locale, whole = false)

    /** `chartMoney()`: whole amounts without decimals over a chart bar. */
    fun chartMoney(value: Double, currency: String, locale: String): String = format(value, currency, locale, whole = value == Math.rint(value))

    private fun format(value: Double, currency: String, locale: String, whole: Boolean): String {
        val nf = NumberFormat.getCurrencyInstance(Locale.forLanguageTag(locale))
        runCatching { nf.currency = Currency.getInstance(currency) }
        val digits = if (whole) 0 else currencyDigits(currency)
        nf.minimumFractionDigits = digits
        nf.maximumFractionDigits = digits
        return nf.format(value)
    }

    /** `amountToInput()`: a stored amount as the text of its field, without grouping. */
    fun amountToInput(value: Double?, currency: String, locale: String): String {
        val digits = currencyDigits(currency)
        val v = value ?: 0.0
        val text = String.format(Locale.ROOT, "%.${digits}f", v)
        val sep = DecimalFormatSymbols.getInstance(Locale.forLanguageTag(locale)).decimalSeparator
        return if (sep == ',') text.replace('.', ',') else text
    }

    /** What is wrong with an amount field (`amountInputProblem` with `sign: 'nonNegative'`), or null. */
    enum class AmountProblem { GROUPED, INVALID, PRECISION }

    fun amountProblem(text: String, currency: String, locale: String, original: Double? = null): AmountProblem? {
        val raw = text.trim().replace('−', '-')
        if (raw.isEmpty()) return null
        val decimal = toDecimal(raw, locale) ?: return AmountProblem.GROUPED
        if (Regex("""\d\s+\d""").containsMatchIn(decimal)) return AmountProblem.GROUPED
        if (!Regex("""^\d+(\.\d+)?$""").matches(decimal)) return AmountProblem.INVALID
        val value = decimal.toDouble()
        val places = decimal.substringAfter('.', "").length
        if (places <= currencyDigits(currency)) return null
        val untouched = original != null && original == value && places <= original.toString().removeSuffix(".0").substringAfter('.', "").length
        return if (untouched) null else AmountProblem.PRECISION
    }

    /** `parseAmountInput()`: null for an empty field, NaN for what [amountProblem] refuses. */
    fun parseAmount(text: String, locale: String): Double? {
        val raw = text.trim().replace('−', '-')
        if (raw.isEmpty()) return null
        val decimal = toDecimal(raw, locale) ?: return Double.NaN
        if (!Regex("""^-?\d+(\.\d+)?$""").matches(decimal)) return Double.NaN
        return decimal.toDoubleOrNull() ?: Double.NaN
    }

    /** `toAmountDecimalString()`: the locale's separator or a comma; grouping is refused (null). */
    private fun toDecimal(raw: String, locale: String): String? {
        if (Regex(""",\d{3}(?!\d)""").containsMatchIn(raw)) return null
        val sep = DecimalFormatSymbols.getInstance(Locale.forLanguageTag(locale)).decimalSeparator
        if (sep == ',' && Regex("""\.\d{3}(?!\d)""").containsMatchIn(raw)) return null
        val text = raw.replace(',', '.')
        if (text.count { it == '.' } > 1) return null
        return text.replace(Regex("""^(-?)\."""), "$10.")
    }

    /** The sentence of [problem] (`amountProblemText`). */
    fun amountProblemText(problem: AmountProblem, currency: String, locale: String, strings: Strings): String = when (problem) {
        AmountProblem.GROUPED -> strings.t("common.amountGrouped", "example" to amountExample(currency, locale))
        AmountProblem.INVALID -> strings.t("common.amountInvalid", "example" to amountExample(currency, locale))
        AmountProblem.PRECISION -> {
            val digits = currencyDigits(currency)
            val nf = NumberFormat.getNumberInstance(Locale.forLanguageTag(locale)).apply {
                minimumFractionDigits = digits
                maximumFractionDigits = digits
            }
            strings.t("common.amountPrecisionRequired", "currency" to currency, "step" to nf.format(1.0 / Math.pow(10.0, digits.toDouble())))
        }
    }

    private fun amountExample(currency: String, locale: String): String {
        val digits = currencyDigits(currency)
        val nf = NumberFormat.getNumberInstance(Locale.forLanguageTag(locale)).apply {
            isGroupingUsed = false
            minimumFractionDigits = digits
            maximumFractionDigits = digits
        }
        return nf.format(1250)
    }

    /** `computeHourlyAmount()` (server/services/housekeeping-billing.js): minutes to the quarter hour. */
    fun hourlyAmount(minutes: Double, hourlyRate: Double?): Double {
        val rounded = (max(0.0, minutes) / 15.0).roundToLong() * 15.0
        return rounded / 60.0 * (hourlyRate ?: 0.0)
    }

    // ── Labels ──

    fun urgencyKey(status: String): String = when (status) {
        "overdue" -> "housekeeping.overdue"
        "today" -> "housekeeping.dueToday"
        else -> "housekeeping.ok"
    }

    /** `scheduleLabel()`: unknown values read as monthly. */
    fun scheduleKey(value: String?): String = when (value) {
        "daily" -> "housekeeping.scheduleDaily"
        "twice_monthly" -> "housekeeping.scheduleTwiceMonthly"
        else -> "housekeeping.scheduleMonthly"
    }

    /** `templateLabel()`: the translation of a keyed template, else its own text. */
    fun templateLabel(template: TaskTemplate, area: Boolean, strings: Strings): String {
        val own = if (area) template.area else template.name
        val key = template.key ?: return own
        val k = "housekeeping.taskTemplateData.$key.${if (area) "area" else "name"}"
        return if (strings.has(k)) strings.t(k) else own
    }

    /** `knownAreas()`: the areas of the chores and the templates, case-insensitive unique, sorted. */
    fun knownAreas(tasks: List<DecayTask>, templateAreas: List<String>): List<String> {
        val seen = LinkedHashMap<String, String>()
        (tasks.map { it.area } + templateAreas).forEach { raw ->
            val area = raw.trim()
            if (area.isNotEmpty()) seen.putIfAbsent(area.lowercase(), area)
        }
        return seen.values.sortedWith(String.CASE_INSENSITIVE_ORDER)
    }

    /** The templates the create sheet still offers: not those already a chore by name (`openTaskCreateModal`). */
    fun remainingTemplates(templates: List<TaskTemplate>, tasks: List<DecayTask>, name: (TaskTemplate) -> String): List<TaskTemplate> {
        val existing = tasks.map { it.name.trim().lowercase() }.toSet()
        return templates.filter { name(it).trim().lowercase() !in existing }
    }

    /** The chore form's checks (`required`, `maxlength`, `min="1" step="1"`). */
    fun taskFormValid(name: String, area: String, frequency: String): Boolean {
        val days = frequency.trim().toIntOrNull() ?: return false
        return name.isNotBlank() && area.isNotBlank() && days >= 1 && name.trim().length <= NAME_MAX && area.trim().length <= AREA_MAX
    }

    // ── The worker strip (renderWorkerSummary) ──

    /** What the line under a worker's name says. */
    sealed interface SessionLine {
        data class CheckedIn(val checkIn: String) : SessionLine
        data class DoneToday(val checkIn: String, val checkOut: String) : SessionLine
        data class Recorded(val checkIn: String) : SessionLine
        /** No session today and no amounts: "Not here yet today". */
        data object NotHere : SessionLine
        data class Rate(val hourly: Boolean, val rate: Double?, val schedule: String?) : SessionLine
    }

    fun sessionLine(worker: Worker, canSeeAmounts: Boolean): SessionLine {
        val checkedIn = worker.currentSession != null
        val session = worker.currentSession ?: worker.todaySession
        val doneToday = !checkedIn && worker.todaySession?.checkOut != null
        return when {
            session == null && amountsHidden(canSeeAmounts, worker.dailyRate) -> SessionLine.NotHere
            session == null -> SessionLine.Rate(worker.hourly, if (worker.hourly) worker.hourlyRate else worker.dailyRate, worker.paymentSchedule)
            checkedIn -> SessionLine.CheckedIn(session.checkIn)
            doneToday -> SessionLine.DoneToday(session.checkIn, session.checkOut!!)
            else -> SessionLine.Recorded(session.checkIn)
        }
    }

    /** The button: check out while in, "check in again" after a visit today, else check in. */
    enum class CheckAction { CHECK_IN, CHECK_IN_AGAIN, CHECK_OUT }

    fun checkAction(worker: Worker): CheckAction = when {
        worker.currentSession != null -> CheckAction.CHECK_OUT
        worker.todaySession?.checkOut != null -> CheckAction.CHECK_IN_AGAIN
        else -> CheckAction.CHECK_IN
    }

    // ── Payments chart (renderDashboard) ──

    data class Bar(val month: String, val total: Double, val pending: Double, val scale: Double, val paidShare: Double)

    fun bars(months: List<MonthPayment>): List<Bar> {
        val maxTotal = max(1.0, months.maxOfOrNull { it.total } ?: 0.0)
        return months.map { row ->
            val scale = if (row.total > 0) max(0.06, row.total / maxTotal) else 0.0
            val paid = if (row.total > 0) max(0.0, min(1.0, (row.total - row.pending) / row.total)) else 0.0
            Bar(row.month, row.total, row.pending, scale, paid)
        }
    }

    /** The chart only from two months with payments on; one month gets a sentence instead. */
    fun paidMonths(months: List<MonthPayment>?): List<MonthPayment> = months.orEmpty().filter { it.total > 0 }

    // ── Visits ──

    /** `visitPaymentMeta()`: why buttons are missing, in the meta line. */
    fun paymentMetaKeys(visit: Visit, readOnly: Boolean): List<String> = when {
        !visit.paid -> listOf("housekeeping.paymentPending")
        visit.canEdit || readOnly -> listOf("housekeeping.paymentPaid")
        else -> listOf("housekeeping.paymentPaid", "housekeeping.settledAdminOnly")
    }

    /** `receiptHiddenFromViewer()`: a receipt exists, but its id is masked for this member. */
    fun receiptHidden(visit: Visit): Boolean = visit.hasReceipt == true && visit.receiptDocumentId == null

    /** `shiftMonth()`. */
    fun shiftMonth(ym: String, dir: Int): String = runCatching { YearMonth.parse(ym).plusMonths(dir.toLong()).toString() }.getOrDefault(ym)

    // ── Profiles ──

    fun usernameValid(username: String): Boolean = username.isBlank() || USERNAME.matches(username.trim())

    /** The profile form, as `openStaffModal` sends it. */
    data class WorkerForm(
        val id: Int? = null,
        val displayName: String = "",
        val username: String = "",
        val rateType: String = "daily",
        val dailyRate: String = "",
        val hourlyRate: String = "",
        val paymentSchedule: String = "monthly",
        val phone: String = "",
        val email: String = "",
        val birthDate: String? = null,
        val birthYearUnknown: Boolean = false,
        val calendarColor: String = COLOR_DEFAULT,
        val avatarColor: String = COLOR_DEFAULT,
        val notes: String = "",
        /** Set only when the member picked a new picture or removed it; null keeps the stored one. */
        val avatarChange: AvatarChange? = null,
    ) {
        val hourly: Boolean get() = rateType == "hourly"
    }

    sealed interface AvatarChange {
        data class Set(val dataUrl: String) : AvatarChange
        data object Remove : AvatarChange
    }

    fun workerForm(worker: Worker?, currency: String, locale: String): WorkerForm = if (worker == null) WorkerForm(
        dailyRate = amountToInput(0.0, currency, locale),
        hourlyRate = amountToInput(0.0, currency, locale),
    ) else WorkerForm(
        id = worker.id,
        displayName = worker.displayName,
        username = worker.username.orEmpty(),
        rateType = if (worker.hourly) "hourly" else "daily",
        dailyRate = amountToInput(worker.dailyRate, currency, locale),
        hourlyRate = amountToInput(worker.hourlyRate, currency, locale),
        paymentSchedule = worker.paymentSchedule?.takeIf { it in SCHEDULES } ?: "monthly",
        phone = worker.phone.orEmpty(),
        email = worker.email.orEmpty(),
        birthDate = worker.birthDate,
        birthYearUnknown = worker.birthYearUnknown,
        calendarColor = worker.calendarColor ?: COLOR_DEFAULT,
        avatarColor = worker.avatarColor ?: COLOR_DEFAULT,
        notes = worker.notes.orEmpty(),
    )

    /**
     * The body of `POST /worker`. The rate of the type not chosen goes out as stored, never a
     * leftover entry (`inactiveField.value = amountToInput(inactiveOriginal)`).
     */
    fun workerBody(form: WorkerForm, stored: Worker?, locale: String): kotlinx.serialization.json.JsonObject {
        val daily = if (form.hourly) stored?.dailyRate ?: 0.0 else parseAmount(form.dailyRate, locale) ?: 0.0
        val hourly = if (form.hourly) parseAmount(form.hourlyRate, locale) ?: 0.0 else stored?.hourlyRate ?: 0.0
        return kotlinx.serialization.json.buildJsonObject {
            put("id", form.id?.let(::jsonNumber) ?: kotlinx.serialization.json.JsonNull)
            put("display_name", jsonString(form.displayName.trim()))
            put("username", optional(form.username))
            put("phone", optional(form.phone))
            put("email", optional(form.email))
            put("birth_date", form.birthDate?.let(::jsonString) ?: kotlinx.serialization.json.JsonNull)
            put("birth_year_unknown", kotlinx.serialization.json.JsonPrimitive(form.birthYearUnknown && form.birthDate != null))
            put("daily_rate", jsonNumber(daily))
            put("rate_type", jsonString(form.rateType))
            put("hourly_rate", jsonNumber(hourly))
            put("payment_schedule", jsonString(form.paymentSchedule))
            put("calendar_color", jsonString(form.calendarColor))
            put("avatar_color", jsonString(form.avatarColor))
            when (val change = form.avatarChange) {
                is AvatarChange.Set -> put("avatar_data", jsonString(change.dataUrl))
                AvatarChange.Remove -> put("avatar_data", kotlinx.serialization.json.JsonNull)
                null -> Unit
            }
            put("notes", optional(form.notes))
        }
    }

    /** The rate field of the chosen type, checked; the other one is not ([workerBody]). */
    fun workerRateProblem(form: WorkerForm, stored: Worker?, currency: String, locale: String): AmountProblem? =
        if (form.hourly) amountProblem(form.hourlyRate, currency, locale, stored?.hourlyRate)
        else amountProblem(form.dailyRate, currency, locale, stored?.dailyRate)

    // ── The visit editor (openVisitEditModal) ──

    data class VisitTexts(val eventTitle: String, val paymentTitle: String, val paymentDescription: String)

    /** `visitTextPayload()`: [dateLabel] is `dateText(visitDate)`, [amount] the formatted total. */
    fun visitTexts(workerName: String?, dateLabel: String, amount: String, strings: Strings): VisitTexts {
        val name = workerName?.takeIf { it.isNotBlank() } ?: strings.t("housekeeping.unnamedWorker")
        return VisitTexts(
            eventTitle = strings.t("housekeeping.calendarVisitTitle", "name" to name),
            paymentTitle = strings.t("housekeeping.paymentTaskTitle", "name" to name),
            paymentDescription = strings.t("housekeeping.paymentTaskDescription", "date" to dateLabel, "amount" to amount),
        )
    }

    /** The body of `PUT /visits/{id}`; an hourly visit sends minutes, a daily one its rate. */
    fun visitBody(
        visit: Visit,
        date: String,
        originalDate: String,
        minutes: Double?,
        dailyRate: Double?,
        extras: Double,
        receiptDocumentId: Int?,
        texts: VisitTexts,
    ): kotlinx.serialization.json.JsonObject = kotlinx.serialization.json.buildJsonObject {
        put("date", jsonString(date))
        put("original_date", jsonString(originalDate))
        if (visit.hourly) put("minutes_worked", jsonNumber(minutes ?: 0.0)) else put("daily_rate", jsonNumber(dailyRate ?: 0.0))
        put("extras", jsonNumber(extras))
        put("receipt_document_id", receiptDocumentId?.let(::jsonNumber) ?: kotlinx.serialization.json.JsonNull)
        put("event_title", jsonString(texts.eventTitle))
        put("payment_title", jsonString(texts.paymentTitle))
        put("payment_description", jsonString(texts.paymentDescription))
    }

    /** The check-in body: an hourly worker starts at 0, a daily one at their rate (`toggleSession`). */
    fun checkInBody(worker: Worker, zone: String, texts: VisitTexts): kotlinx.serialization.json.JsonObject = kotlinx.serialization.json.buildJsonObject {
        put("worker_id", jsonNumber(worker.id))
        put("daily_rate", jsonNumber(if (worker.hourly) 0.0 else worker.dailyRate ?: 0.0))
        put("extras", jsonNumber(0))
        put("timezone", jsonString(zone))
        put("event_title", jsonString(texts.eventTitle))
        put("payment_title", jsonString(texts.paymentTitle))
        put("payment_description", jsonString(texts.paymentDescription))
    }

    private fun jsonString(value: String) = kotlinx.serialization.json.JsonPrimitive(value)
    private fun jsonNumber(value: Number) = kotlinx.serialization.json.JsonPrimitive(value)
    private fun optional(value: String) = value.trim().takeIf { it.isNotEmpty() }?.let(::jsonString) ?: kotlinx.serialization.json.JsonNull
}
