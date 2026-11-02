package io.github.marcomow.yuvomi.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.ShieldMoon
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.marcomow.yuvomi.core.api.ApiProblem
import io.github.marcomow.yuvomi.core.api.YuvomiClient
import io.github.marcomow.yuvomi.core.design.MemberAvatar
import io.github.marcomow.yuvomi.core.design.Yuvomi
import io.github.marcomow.yuvomi.core.i18n.t
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Pure rules of `admin-family.js` (SET-R50 to R57). */
object FamilyRules {
    val ROLES = listOf("dad", "mom", "parent", "child", "grandparent", "relative", "other")
    private val USERNAME = Regex("^[a-zA-Z0-9._-]{3,64}$")

    /** `familyRoleLabel`: `settings.familyRoleDad` and so on. */
    fun roleKey(role: String?): String = "settings.familyRole" + (role ?: "other").split('_').joinToString("") { it.replaceFirstChar(Char::uppercase) }

    /** `orderableIds`: active household members, and only when there are two or more. */
    fun orderable(users: List<JsonObject>): List<Int> {
        val ids = users.filter { it.bool("is_household_member", !it.bool("is_worker")) && it.str("deactivated_at") == null }.mapNotNull { it.int("id") }
        return if (ids.size > 1) ids else emptyList()
    }

    /** `movedOrder`. */
    fun moved(ids: List<Int>, id: Int, delta: Int): List<Int> {
        val from = ids.indexOf(id)
        val to = from + delta
        if (from < 0 || to !in ids.indices) return ids
        return ids.toMutableList().apply { add(to, removeAt(from)) }
    }

    /** `orderedMembers`: active first, former members after, each in the server's order. */
    fun ordered(users: List<JsonObject>): List<JsonObject> = users.filter { it.str("deactivated_at") == null } + users.filter { it.str("deactivated_at") != null }

    /** Member form problems (create needs a password unless the account signs in with SSO only). */
    fun memberProblems(username: String, displayName: String, password: String, creating: Boolean, ssoOnly: Boolean, email: String, birth: String, today: java.time.LocalDate): Map<String, String> = buildMap {
        if (!USERNAME.matches(username.trim())) put("username", "android.settings.usernameInvalid")
        if (displayName.isBlank()) put("display_name", "common.required")
        if (creating && !ssoOnly && password.isEmpty()) put("password", "common.required")
        else if (password.isNotEmpty() && password.length < AccountRules.PASSWORD_MIN_LENGTH) put("password", "settings.passwordTooShort")
        if (email.isNotBlank() && !AccountRules.isEmail(email.trim())) put("email", "common.emailInvalid")
        if (!AccountRules.birthDateValid(birth, today)) put("birth_date", "settings.memberBirthDateInvalid")
    }

    /** `inviteHtml`'s title: name, username, address, else "Open invitation". */
    fun invitePrimary(invite: JsonObject): String? = invite.str("display_name") ?: invite.str("username") ?: invite.str("email")

    fun inviteLink(client: YuvomiClient, token: String) = client.webUrl("/join?token=$token")
}

private const val USERS = "users"
private const val INVITES = "invites"
private const val TWO_FACTOR_OVERVIEW = "2fa-overview"
private const val OIDC_CONFIG = "oidc-config"

/** `admin-family.js`: members, their order, invitations and the household's second factor. */
@Composable
fun FamilySection(ctx: SectionContext) {
    val slots by ctx.vm.slots.collectAsState()
    LaunchedEffect(Unit) {
        ctx.vm.load(USERS) { it.data(SettingsOperation.USERS) }
        ctx.vm.load(INVITES) { it.data(SettingsOperation.INVITES) }
        ctx.vm.load(TWO_FACTOR_OVERVIEW) { it.call(SettingsOperation.TWO_FACTOR_OVERVIEW) }
        ctx.vm.load(OIDC_CONFIG) { it.call(SettingsOperation.OIDC_CONFIG) }
    }
    fun reloadUsers() = ctx.vm.load(USERS, force = true) { it.data(SettingsOperation.USERS) }
    val usersSlot = slots[USERS]
    val users = usersSlot?.value.arr().map { it.obj() }
    val ssoAvailable = slots[OIDC_CONFIG]?.value?.obj()?.bool("enabled") == true
    var editing by remember { mutableStateOf<JsonObject?>(null) }
    var creating by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<JsonObject?>(null) }
    var inviting by remember { mutableStateOf(false) }
    val me = ctx.shell.session.user.id

    SettingsGroup(title = t("settings.sectionFamily")) {
        when {
            usersSlot?.errorKey != null -> ActionRow(t("settings.retry"), hint = t(usersSlot.errorKey)) { reloadUsers() }
            usersSlot?.value == null -> Busy(true)
            else -> {
                val ordered = FamilyRules.ordered(users)
                val orderable = FamilyRules.orderable(users)
                ordered.forEachIndexed { i, u ->
                    if (i > 0) RowDivider()
                    MemberRow(ctx, u, me, orderable, onEdit = { editing = u }, onDelete = { deleting = u }) { delta ->
                        val id = u.int("id") ?: return@MemberRow
                        val next = FamilyRules.moved(orderable, id, delta)
                        ctx.scope.launch {
                            val failed = ctx.vm.write("quickLinks.orderError") { it.call(SettingsOperation.FAMILY_REORDER, body = buildJsonObject { put("order", JsonArray(next.map(::JsonPrimitive))) }) }
                            if (failed != null) ctx.notifyKey("quickLinks.orderError") else reloadUsers()
                        }
                    }
                }
                if (ctx.editable) {
                    RowDivider()
                    ActionRow(t("settings.addMember"), icon = { Icon(Icons.Outlined.Add, null) }) { creating = true }
                }
            }
        }
    }

    InvitesGroup(ctx, slots[INVITES]?.value?.obj()?.get("invites").arr().map { it.obj() }) { inviting = true }
    (slots[TWO_FACTOR_OVERVIEW]?.value as? JsonObject)?.let { TwoFactorHousehold(ctx, it) }

    if (creating || editing != null) {
        MemberDialog(ctx, editing, ssoAvailable, onDismiss = { creating = false; editing = null }) {
            creating = false
            editing = null
            reloadUsers()
            ctx.vm.load(TWO_FACTOR_OVERVIEW, force = true) { it.call(SettingsOperation.TWO_FACTOR_OVERVIEW) }
        }
    }
    deleting?.let { u ->
        val name = u.str("display_name").orEmpty()
        ConfirmDialog(ctx.strings.t("settings.removeMemberConfirm", "name" to name), ctx.strings.t("settings.removeMemberConfirmDetail", "name" to name), t("settings.removeMemberLabel"), danger = true, onDismiss = { deleting = null }) {
            deleting = null
            ctx.scope.launch {
                ctx.vm.attempt { it.call(SettingsOperation.USER_DELETE, mapOf("id" to (u.int("id") ?: 0))) }
                    .onSuccess { answer ->
                        ctx.notifyKey(if (answer.obj().str("outcome") == "deactivated") "settings.memberDeactivatedToast" else "settings.memberDeletedToast", "name" to name)
                        reloadUsers()
                    }
                    .onFailure { ctx.notifyKey(SettingsErrors.key(it)) }
            }
        }
    }
    if (inviting) InviteDialog(ctx, onDismiss = { inviting = false }) { ctx.vm.load(INVITES, force = true) { it.data(SettingsOperation.INVITES) } }
}

@Composable
private fun MemberRow(ctx: SectionContext, u: JsonObject, me: Int, orderable: List<Int>, onEdit: () -> Unit, onDelete: () -> Unit, move: (Int) -> Unit) {
    val name = u.str("display_name").orEmpty()
    val id = u.int("id")
    val former = u.str("deactivated_at") != null
    val meta = listOfNotNull(
        "@" + u.str("username").orEmpty(),
        if (u.bool("is_worker")) t("housekeeping.workerRole") else t(FamilyRules.roleKey(u.str("family_role"))),
        if (u.str("role") == "admin") t("settings.systemAdminBadge") else null,
        if (former) t("settings.memberFormerBadge") else null,
    ).joinToString(" · ")
    val contact = listOfNotNull(u.str("phone")?.let { ctx.strings.t("settings.memberPhoneMeta", "value" to it) }, u.str("email")).joinToString(" · ")
    var menu by remember { mutableStateOf(false) }
    ListItem(
        headlineContent = { Text(if (id == me) "$name  " + t("settings.memberYou") else name) },
        supportingContent = { Column { Text(meta, color = Yuvomi.colors.textSecondary); if (contact.isNotEmpty()) Text(contact, color = Yuvomi.colors.textSecondary) } },
        leadingContent = { MemberAvatar(name, u.str("avatar_color"), u.str("avatar_data"), size = 40.dp) },
        trailingContent = if (!ctx.editable || former) null else ({
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, ctx.strings.t("common.moreActionsNamed", "name" to name)) }
                DropdownMenu(menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text(ctx.strings.t("common.editNamed", "name" to name)) }, leadingIcon = { Icon(Icons.Outlined.Edit, null) }, onClick = { menu = false; onEdit() })
                    if (id != null && id in orderable) {
                        DropdownMenuItem(text = { Text(ctx.strings.t("category.moveUpNamed", "name" to name)) }, leadingIcon = { Icon(Icons.Outlined.KeyboardArrowUp, null) },
                            enabled = orderable.first() != id, onClick = { menu = false; move(-1) })
                        DropdownMenuItem(text = { Text(ctx.strings.t("category.moveDownNamed", "name" to name)) }, leadingIcon = { Icon(Icons.Outlined.KeyboardArrowDown, null) },
                            enabled = orderable.last() != id, onClick = { menu = false; move(1) })
                    }
                    if (id != me) DropdownMenuItem(
                        text = { Text(ctx.strings.t("settings.removeMemberNamed", "name" to name), color = Yuvomi.colors.danger) },
                        leadingIcon = { Icon(Icons.Outlined.Delete, null, tint = Yuvomi.colors.danger) },
                        onClick = { menu = false; onDelete() },
                    )
                }
            }
        }),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}

/** Add or edit a member (`openAddMemberModal`, `openEditMemberModal`). */
@Composable
private fun MemberDialog(ctx: SectionContext, member: JsonObject?, ssoAvailable: Boolean, onDismiss: () -> Unit, saved: () -> Unit) {
    val creating = member == null
    var username by rememberSaveable { mutableStateOf(member?.str("username").orEmpty()) }
    var name by rememberSaveable { mutableStateOf(member?.str("display_name").orEmpty()) }
    var color by rememberSaveable { mutableStateOf(member?.str("avatar_color") ?: AccountRules.COLOR_DEFAULT) }
    var role by rememberSaveable { mutableStateOf(member?.str("family_role") ?: "other") }
    var phone by rememberSaveable { mutableStateOf(member?.str("phone").orEmpty()) }
    var email by rememberSaveable { mutableStateOf(member?.str("email").orEmpty()) }
    var birth by rememberSaveable { mutableStateOf(member?.str("birth_date")?.take(10).orEmpty()) }
    var yearUnknown by rememberSaveable { mutableStateOf(member?.bool("birth_year_unknown") ?: false) }
    var admin by rememberSaveable { mutableStateOf(member?.str("role") == "admin") }
    var sso by rememberSaveable { mutableStateOf(member?.bool("sso_only") ?: false) }
    var password by remember { mutableStateOf("") }
    var problems by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var busy by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(t(if (creating) "settings.addMember" else "settings.editMemberTitle")) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Field(t("settings.usernameLabel"), username, { username = it.trim() }, problems["username"]?.let { t(it) })
                Field(t("settings.displayNameLabel"), name, { name = it.take(AccountRules.DISPLAY_NAME_MAX) }, problems["display_name"]?.let { t(it) })
                Text(t("settings.colorLabel"), style = MaterialTheme.typography.labelLarge)
                ColorSwatches(color, true) { color = it }
                ChoiceRow(t("settings.familyRoleLabel"), FamilyRules.ROLES.map { it to t(FamilyRules.roleKey(it)) }, role) { role = it }
                if (role == "child" && creating) Hint(t("settings.childStartHint"))
                Field(t("settings.memberPhoneLabel"), phone, { phone = it }, keyboard = KeyboardType.Phone)
                Field(t("settings.memberEmailLabel"), email, { email = it }, problems["email"]?.let { t(it) }, keyboard = KeyboardType.Email)
                DateField(t("settings.memberBirthDateLabel"), birth, problems["birth_date"]?.let { t(it) }, true) { birth = it }
                SwitchRow(t("birthdays.yearUnknown"), yearUnknown, enabled = birth.isNotBlank()) { yearUnknown = it }
                if (ssoAvailable) SwitchRow(t("settings.memberSsoOnlyLabel"), sso, hint = t(if (creating) "settings.memberSsoOnlyHint" else "settings.memberSsoOnlyEditHint")) { sso = it; if (it) password = "" }
                if (!sso) Field(
                    t(if (creating) "settings.memberPasswordLabel" else "settings.resetPasswordLabel"), password, { password = it },
                    problems["password"]?.let { t(it) }, hint = if (creating) null else t("settings.resetPasswordHint"), password = true,
                )
                SwitchRow(t("settings.systemAdminLabel"), admin, hint = t("settings.systemAdminHint")) { admin = it }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = {
                problems = FamilyRules.memberProblems(username, name, password, creating, sso, email, birth, ctx.shell.time.today())
                if (problems.isNotEmpty()) return@TextButton
                busy = true
                val body = buildJsonObject {
                    put("username", username.trim())
                    put("display_name", name.trim())
                    put("avatar_color", color)
                    put("family_role", role)
                    put("system_admin", admin)
                    if (phone.isBlank()) put("phone", JsonNull) else put("phone", phone.trim())
                    if (email.isBlank()) put("email", JsonNull) else put("email", email.trim())
                    if (birth.isBlank()) put("birth_date", JsonNull) else put("birth_date", birth.trim())
                    put("birth_year_unknown", yearUnknown && birth.isNotBlank())
                    if (password.isNotEmpty()) put("password", password)
                    if (ssoAvailable) put("sso_only", sso)
                }
                ctx.scope.launch {
                    ctx.vm.attempt { api ->
                        if (creating) api.call(SettingsOperation.USER_CREATE, body = body, idempotencyKey = YuvomiClient.newIdempotencyKey())
                        else api.call(SettingsOperation.USER_UPDATE, mapOf("id" to (member?.int("id") ?: 0)), body = body)
                    }.onSuccess {
                        ctx.notifyKey(if (creating) "settings.memberAddedToast" else "settings.memberUpdatedToast", "name" to name.trim())
                        if (member?.int("id") == ctx.shell.session.user.id) ctx.vm.refreshSession()
                        saved()
                    }.onFailure { e ->
                        val http = e as? ApiProblem.Http
                        when {
                            http?.reason == "email_in_use" -> problems = mapOf("email" to "common.emailInUse")
                            http?.status == 409 -> problems = mapOf("username" to "android.settings.usernameTaken")
                            else -> ctx.notifyKey(SettingsErrors.key(e))
                        }
                    }
                    busy = false
                }
            }) { Text(t(if (creating) "settings.createMember" else "settings.saveMember")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(t("common.cancel")) } },
    )
}

@Composable
private fun InvitesGroup(ctx: SectionContext, invites: List<JsonObject>, add: () -> Unit) {
    var revoking by remember { mutableStateOf<JsonObject?>(null) }
    SettingsGroup(title = t("settings.invites.title"), footer = t("settings.invites.intro")) {
        invites.forEachIndexed { i, invite ->
            if (i > 0) RowDivider()
            val primary = FamilyRules.invitePrimary(invite) ?: t("settings.invites.untitled")
            val meta = listOfNotNull(
                invite.str("username")?.takeIf { it != primary }?.let { "@$it" },
                t(FamilyRules.roleKey(invite.str("family_role"))),
                if (invite.str("role") == "admin") t("settings.systemAdminBadge") else null,
            ).joinToString(" · ")
            ListItem(
                headlineContent = { Text(primary) },
                supportingContent = {
                    Column {
                        Text(meta, color = Yuvomi.colors.textSecondary)
                        Text(ctx.strings.t("settings.invites.expires", "date" to ctx.dateTime(invite.str("expires_at"))), color = Yuvomi.colors.textSecondary)
                    }
                },
                trailingContent = if (ctx.editable) ({
                    IconButton(onClick = { revoking = invite }) { Icon(Icons.Outlined.Delete, t("settings.invites.revoke"), tint = Yuvomi.colors.danger) }
                }) else null,
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
        }
        if (ctx.editable) {
            if (invites.isNotEmpty()) RowDivider()
            ActionRow(t("settings.invites.add"), icon = { Icon(Icons.Outlined.Add, null) }, onClick = add)
        }
    }
    revoking?.let { invite ->
        val name = FamilyRules.invitePrimary(invite) ?: t("settings.invites.untitled")
        ConfirmDialog(ctx.strings.t("settings.invites.revokeConfirm", "name" to name), null, t("settings.invites.revoke"), danger = true, onDismiss = { revoking = null }) {
            revoking = null
            ctx.run("settings.invites.revoked") {
                ctx.vm.write { it.call(SettingsOperation.INVITE_REVOKE, mapOf("id" to (invite.int("id") ?: 0))) }
                    .also { if (it == null) ctx.vm.load(INVITES, force = true) { api -> api.data(SettingsOperation.INVITES) } }
            }
        }
    }
}

/** `addInviteFormHtml` and the link that replaces the form once created. */
@Composable
private fun InviteDialog(ctx: SectionContext, onDismiss: () -> Unit, created: () -> Unit) {
    val context = LocalContext.current
    var name by rememberSaveable { mutableStateOf("") }
    var role by rememberSaveable { mutableStateOf("other") }
    var preset by rememberSaveable { mutableStateOf("restricted") }
    var username by rememberSaveable { mutableStateOf("") }
    var email by rememberSaveable { mutableStateOf("") }
    var sendEmail by rememberSaveable { mutableStateOf(false) }
    var admin by rememberSaveable { mutableStateOf(false) }
    var link by rememberSaveable { mutableStateOf<String?>(null) }
    var emailSent by rememberSaveable { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(t("settings.invites.add")) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val shown = link
                if (shown != null) {
                    Text(t("settings.invites.linkTitle"), style = MaterialTheme.typography.labelLarge)
                    SelectionContainer { Text(shown) }
                    Hint(t("settings.invites.linkOnce"))
                    if (sendEmail) Hint(t(if (emailSent) "settings.invites.emailSent" else "settings.invites.emailNotSent"))
                } else {
                    Field(t("settings.displayNameLabel"), name, { name = it.take(128) })
                    ChoiceRow(t("settings.familyRoleLabel"), FamilyRules.ROLES.map { it to t(FamilyRules.roleKey(it)) }, role) { role = it }
                    ChoiceRow(t("settings.invites.presetLabel"), listOf("restricted" to t("settings.invites.presetRestricted"), "role" to t("settings.invites.presetRole")), preset) { preset = it }
                    Field(t("settings.usernameLabel"), username, { username = it.trim() }, hint = t("settings.invites.usernameHint"))
                    Field(t("settings.memberEmailLabel"), email, { email = it.trim() }, keyboard = KeyboardType.Email)
                    SwitchRow(t("settings.invites.sendEmail"), sendEmail) { sendEmail = it }
                    SwitchRow(t("settings.systemAdminLabel"), admin, hint = t("settings.systemAdminHint")) { admin = it }
                    error?.let { Hint(it, Yuvomi.colors.danger) }
                }
            }
        },
        confirmButton = {
            val shown = link
            if (shown != null) {
                TextButton(onClick = { copy(context, shown); ctx.notifyKey("settings.invites.copied") }) { Text(t("settings.invites.copy")) }
            } else TextButton(enabled = !busy, onClick = {
                if (email.isNotEmpty() && !AccountRules.isEmail(email)) { error = ctx.strings.t("common.emailInvalid"); return@TextButton }
                if (sendEmail && email.isEmpty()) { error = ctx.strings.t("android.settings.inviteEmailRequired"); return@TextButton }
                busy = true
                ctx.scope.launch {
                    ctx.vm.attempt { api ->
                        api.data(SettingsOperation.INVITE_CREATE, idempotencyKey = YuvomiClient.newIdempotencyKey(), body = buildJsonObject {
                            if (username.isNotEmpty()) put("username", username)
                            if (name.isNotBlank()) put("display_name", name.trim())
                            if (email.isNotEmpty()) put("email", email)
                            put("family_role", role)
                            put("permission_preset", preset)
                            put("send_email", sendEmail)
                            put("system_admin", admin)
                        })
                    }.onSuccess { data ->
                        val d = data.obj()
                        link = d.str("token")?.let { FamilyRules.inviteLink(ctx.vm.api!!.client, it) }
                        emailSent = d.bool("email_sent")
                        created()
                    }.onFailure { e ->
                        error = ctx.strings.t(if ((e as? ApiProblem.Http)?.status == 409) "android.settings.usernameTaken" else SettingsErrors.key(e))
                    }
                    busy = false
                }
            }) { Text(t("settings.invites.submit")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(t(if (link != null) "settings.invites.done" else "common.cancel")) } },
    )
}

/** Who has a second factor, and whether the household requires one (`loadTwoFactorHousehold`). */
@Composable
private fun TwoFactorHousehold(ctx: SectionContext, overview: JsonObject) {
    val members = overview["data"].arr().map { it.obj() }
    SettingsGroup(title = t("settings.twoFactorTitle"), footer = t("settings.twoFactorHouseholdHint")) {
        SwitchRow(t("settings.twoFactorRequireLabel"), overview.bool("required"), ctx.editable) { required ->
            ctx.scope.launch {
                val failed = ctx.vm.write { it.call(SettingsOperation.TWO_FACTOR_REQUIRE, body = buildJsonObject { put("required", required) }) }
                if (failed != null) ctx.notifyKey(failed)
                ctx.vm.load(TWO_FACTOR_OVERVIEW, force = true) { it.call(SettingsOperation.TWO_FACTOR_OVERVIEW) }
            }
        }
        members.forEach { m ->
            RowDivider()
            val on = m.bool("enabled")
            ListItem(
                headlineContent = { Text(m.str("display_name").orEmpty()) },
                trailingContent = { Text(t(if (on) "settings.twoFactorMemberOn" else "settings.twoFactorMemberOff"), color = if (on) Yuvomi.colors.success else Yuvomi.colors.textSecondary) },
                leadingContent = { Icon(if (on) Icons.Outlined.Shield else Icons.Outlined.ShieldMoon, null, tint = if (on) Yuvomi.colors.success else Yuvomi.colors.textSecondary) },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
        }
    }
}
