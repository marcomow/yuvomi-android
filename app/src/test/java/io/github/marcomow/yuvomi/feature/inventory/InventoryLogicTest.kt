package io.github.marcomow.yuvomi.feature.inventory

import io.github.marcomow.yuvomi.core.api.ApiJson
import io.github.marcomow.yuvomi.core.api.ApiProblem
import io.github.marcomow.yuvomi.core.i18n.Strings
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.time.LocalDate

/** docs/spec/inventory.md, INV-A6 to A10: the pure ports of `inventory.js` and `inventory-warranty.js`. */
class InventoryLogicTest {
    private val english = Strings.flatten(File(System.getProperty("yuvomi.assetsDir"), "i18n/en.json").readText())
    private val strings = Strings("en", english, english) { if (it == 1.0) "one" else "other" }
    private val today = LocalDate.of(2026, 11, 2)

    private val categories = listOf(
        InventoryCategory("electronics", labelKey = "inventory.categoryElectronics", sortOrder = 0),
        InventoryCategory("vehicles", labelKey = "inventory.categoryVehicles", sortOrder = 1, tracksOdometer = true),
        InventoryCategory("other", labelKey = "inventory.categoryOther", sortOrder = 2),
    )
    private val locations = listOf(
        InventoryLocation(1, "Garage", subcategories = listOf(InventoryLocation(3, "Shelf", parentId = 1))),
        InventoryLocation(2, "Office"),
    )

    private fun item(id: Int, name: String, category: String = "other", location: Int? = null, vararg dates: TrackedDate) =
        InventoryItem(id = id, name = name, category = category, categoryLabelKey = categories.firstOrNull { it.key == category }?.labelKey, locationId = location, trackedDates = dates.toList())

    @Test fun `dates count down like date-status js and warranties end like warrantyEndDateKey`() {
        assertEquals(InventoryRules.State.EXPIRED, InventoryRules.dateStatus("2026-11-01", today)!!.state)
        assertEquals(InventoryRules.State.EXPIRING, InventoryRules.dateStatus("2026-11-02", today)!!.state)
        assertEquals(InventoryRules.State.EXPIRING, InventoryRules.dateStatus("2026-12-02", today)!!.state)
        assertEquals(InventoryRules.State.VALID, InventoryRules.dateStatus("2026-12-03", today)!!.state)
        assertNull(InventoryRules.dateStatus(null, today))
        assertEquals(LocalDate.of(2024, 2, 29), InventoryRules.warrantyEnd("2024-01-31", 1))
        assertEquals(LocalDate.of(2028, 3, 15), InventoryRules.warrantyEnd("2026-03-15", 24))
        assertNull(InventoryRules.warrantyEnd("2026-03-15", null))
        assertEquals(30L, InventoryRules.warrantyStatus("2025-12-02", 12, today)!!.days)
    }

    @Test fun `the deadline chip picks overdue first, then the soonest, and forgets old warranties`() {
        val tuv = TrackedDate(1, "TÜV", "2026-10-20")
        val service = TrackedDate(2, "Service", "2026-11-10")
        val car = item(1, "Car", "vehicles", null, service, tuv).copy(purchaseDate = "2024-11-20", warrantyMonths = 24)
        val d = InventoryRules.deadline(car, today)!!
        assertEquals("tracked", d.kind)
        assertEquals("TÜV", d.label)
        assertEquals(InventoryRules.State.EXPIRED, d.state)
        assertEquals("TÜV overdue", InventoryRules.deadlineText(d, { it.toString() }, strings))
        val warranty = InventoryRules.deadline(item(2, "TV").copy(purchaseDate = "2024-11-20", warrantyMonths = 24), today)!!
        assertEquals("warranty", warranty.kind)
        assertEquals("Warranty until 2026-11-20", InventoryRules.deadlineText(warranty, { it.toString() }, strings))
        // A warranty that ended more than 30 days ago needs no attention any more.
        assertNull(InventoryRules.deadline(item(3, "Old").copy(purchaseDate = "2020-01-01", warrantyMonths = 12), today))
        assertEquals("Service in 8 days", InventoryRules.deadlineText(InventoryRules.deadline(item(4, "Bike", "other", null, service), today)!!, { "" }, strings))
        assertEquals(2, InventoryRules.countDeadlines(listOf(car, item(5, "None"), item(4, "Bike", "other", null, service)), today))
        assertTrue(InventoryRules.isDue(tuv, today))
        assertFalse(InventoryRules.isDue(TrackedDate(3, "Far", "2027-06-01"), today))
        assertEquals("1 day overdue", InventoryRules.countdown("2026-11-01", today, strings))
        assertEquals("Due today", InventoryRules.countdown("2026-11-02", today, strings))
    }

    @Test fun `groups follow categories, or places inside a category, and search matches four fields`() {
        val items = listOf(
            item(1, "Laptop", "electronics", 2).copy(brand = "Apple"),
            item(2, "Car", "vehicles", 3).copy(serialNumber = "WVW123"),
            item(3, "Drill", "tools", null),
            item(4, "Bike", "vehicles", null),
            item(5, "Van", "vehicles", 1),
        )
        val groups = InventoryRules.groupByCategory(items, categories, strings)
        assertEquals(listOf("electronics", "vehicles", "tools"), groups.map { it.key })
        assertEquals("Vehicles", groups[1].name)
        assertEquals("tools", groups[2].name)
        val inVehicles = InventoryRules.visibleGroups(items, "vehicles", "", false, today, categories, locations, strings)
        assertEquals(listOf("1", InventoryRules.UNLOCATED), inVehicles.map { it.key })
        assertEquals(listOf(2, 5), inVehicles[0].items.map { it.id })
        assertEquals("No location", inVehicles[1].name)
        assertEquals(listOf(1), InventoryRules.visibleGroups(items, null, "apple", false, today, categories, locations, strings).flatMap { g -> g.items.map { it.id } })
        assertEquals(listOf(2), InventoryRules.visibleGroups(items, null, "wvw", false, today, categories, locations, strings).flatMap { g -> g.items.map { it.id } })
        assertTrue(InventoryRules.visibleGroups(items, null, "", true, today, categories, locations, strings).isEmpty())
    }

    @Test fun `the value counts only the household's currency, and places list as parent and child`() {
        val items = listOf(item(1, "A").copy(purchasePrice = 10.0, currency = "EUR"), item(2, "B").copy(purchasePrice = 5.0, currency = "USD"), item(3, "C"))
        assertEquals(10.0, InventoryRules.totalValue(items, "EUR"), 0.0)
        assertEquals(listOf(null, 1, 3, 2), InventoryRules.locationOptions(locations, strings).map { it.first })
        assertEquals("Garage · Shelf", InventoryRules.locationOptions(locations, strings)[2].second)
    }

    @Test fun `the odometer chart adds the item's own reading`() {
        val history = ItemHistory(listOf(HistoryEntry("service_log", 1, "2026-05-01", "Service", odometer = 12000), HistoryEntry("budget_entry", 2, "2026-04-01", "Tyres", amount = -300.0)))
        val car = item(1, "Car", "vehicles").copy(odometer = 15000, odometerOn = "2026-10-01")
        assertEquals(listOf(12000L, 15000L), InventoryRules.odometerPoints(history, car).map { it.value })
        assertEquals(1, InventoryRules.odometerPoints(history, car.copy(odometerOn = "2026-05-01")).size)
    }

    @Test fun `the form sends the web app's payload`() {
        val form = InventoryRules.Form(
            name = " Car ", category = "vehicles", locationId = 3, purchaseDate = "2024-01-02", brand = " VW ", warranty = "24",
            dates = listOf(
                InventoryRules.DateRow("TÜV", "2027-01-01", "", "24", ""),
                InventoryRules.DateRow(" ", "2027-01-01"),
                InventoryRules.DateRow("No date", null),
            ),
            odometer = "15000", odometerUnit = "mi", odometerOn = "2026-10-01",
        )
        val body = InventoryRules.body(form, tracksOdometer = true, showsPrice = true, price = 12.5, attachmentIds = listOf(7), entryId = 9)
        assertEquals(JsonPrimitive("Car"), body["name"])
        assertEquals(JsonPrimitive(3), body["location_id"])
        assertEquals(JsonPrimitive(12.5), body["purchase_price"])
        assertEquals(JsonPrimitive("VW"), body["brand"])
        assertEquals(JsonNull, body["model"])
        assertEquals(JsonPrimitive(24L), body["warranty_months"])
        val dates = body["tracked_dates"] as JsonArray
        assertEquals(1, dates.size)
        assertEquals(JsonPrimitive(30), dates[0].jsonObject["reminder_offset_days"])
        assertEquals(JsonPrimitive(24L), dates[0].jsonObject["interval_months"])
        assertEquals(JsonNull, dates[0].jsonObject["interval_distance"])
        assertEquals(JsonPrimitive(15000L), body["odometer"])
        assertEquals(JsonPrimitive("mi"), body["odometer_unit"])
        assertEquals(JsonArray(listOf(JsonPrimitive(7))), body["attachment_document_ids"])
        assertEquals(JsonPrimitive(9), body["entry_id"])

        val plain = InventoryRules.body(form.copy(category = "other"), tracksOdometer = false, showsPrice = false, price = null, attachmentIds = null, entryId = null)
        assertFalse("purchase_price" in plain)
        assertFalse("attachment_document_ids" in plain)
        assertEquals(JsonNull, plain["odometer"])
        assertEquals(JsonNull, plain["odometer_unit"])
        assertEquals(JsonNull, plain["odometer_on"])

        val done = InventoryRules.completion("2026-11-02", "16000", " ", "Oil", tracksOdometer = true)
        assertEquals(JsonPrimitive(16000L), done["odometer"])
        assertEquals(JsonNull, done["vendor"])
        assertEquals(JsonNull, InventoryRules.completion("2026-11-02", "16000", "", "", tracksOdometer = false)["odometer"])
    }

    @Test fun `the form prefills from an item`() {
        val car = item(1, "Car", "vehicles", 3, TrackedDate(1, "TÜV", "2027-01-01", 14, 24, null)).copy(purchasePrice = 1200.0, currency = "EUR", warrantyMonths = 12, odometer = 5)
        val f = InventoryRules.form(car) { amount, _ -> amount.toString() }
        assertEquals("1200.0", f.price)
        assertEquals("12", f.warranty)
        assertEquals("5", f.odometer)
        assertEquals(InventoryRules.DateRow("TÜV", "2027-01-01", "14", "24", ""), f.dates.single())
        assertEquals(InventoryRules.Form(), InventoryRules.form(null) { _, _ -> "" })
    }

    @Test fun `months shift, owners change, labels resolve`() {
        assertEquals("2026-01", InventoryRules.shiftMonth("2025-12", 1))
        assertEquals("2025-12", InventoryRules.shiftMonth("2026-01", -1))
        val mine = item(1, "A").copy(createdBy = 3)
        assertTrue(InventoryRules.mayChange(mine, canWrite = true, userId = 3, editOthers = false))
        assertFalse(InventoryRules.mayChange(mine, canWrite = true, userId = 4, editOthers = false))
        assertTrue(InventoryRules.mayChange(mine, canWrite = true, userId = 4, editOthers = true))
        assertFalse(InventoryRules.mayChange(mine, canWrite = false, userId = 3, editOthers = true))
        assertEquals("Owned", InventoryRules.statusLabel("active", strings))
        assertEquals("Fair", InventoryRules.conditionLabel("fair", strings))
        assertEquals("Instalment", InventoryRules.roleLabel("instalment", strings))
        assertEquals("mi", InventoryRules.odometerUnitLabel("mi", strings))
        assertEquals("Vehicles", InventoryRules.categoryLabel(categories[1], strings))
        assertTrue(InventoryRules.tracksOdometer("vehicles", categories))
        assertEquals("12 months", strings.t("inventory.warrantyMonthsValue", "count" to 12))
    }

    @Test fun `errors say upstream's sentences`() {
        fun http(status: Int, reason: String?) = ApiProblem.Http(status, reason, "x")
        assertEquals(strings.t("inventory.errorItemGone"), InventoryRules.errorSentence(http(404, "inventory_item_not_found"), strings))
        assertEquals(strings.t("inventory.errorLocationExists"), InventoryRules.errorSentence(http(409, "inventory_location_exists"), strings))
        assertEquals(strings.t("common.errorNotOwner"), InventoryRules.errorSentence(http(403, "not_owner"), strings))
        assertEquals(strings.t("common.errorOfflineMutation"), InventoryRules.errorSentence(ApiProblem.Network(IOException("x")), strings))
        assertEquals(strings.t("common.errorGeneric"), InventoryRules.errorSentence(http(400, null), strings))
        assertTrue(InventoryRules.isGone(http(404, "inventory_tracked_date_not_found")))
        assertFalse(InventoryRules.isGone(http(404, "inventory_tracked_date_not_found"), "inventory_item_not_found"))
        assertEquals(strings.t("category.errorExists"), InventoryRules.managerError(http(409, "category_exists"), strings))
    }

    @Test fun `the tag source lists items with hashtags in their text`() {
        val tagged = InventoryTagSource.items(
            listOf(
                item(1, "Drill").copy(notes = "lent to #neighbour", locationPath = "Garage"),
                item(2, "Laptop #work").copy(brand = "#Apple"),
                item(3, "Chair"),
            ),
        )
        assertEquals(listOf("item:1", "item:2"), tagged.map { it.key })
        assertEquals(setOf("neighbour"), tagged[0].tags)
        assertEquals("Garage", tagged[0].subtitle)
        assertEquals(setOf("work", "Apple"), tagged[1].tags)
        assertEquals("inventory/2", tagged[1].route)
    }

    @Test fun `the items answer decodes with dates, links and attachments`() {
        val json = ApiJson.parseToJsonElement(
            """{"data":[{"id":1,"name":"Car","category":"vehicles","category_label_key":"inventory.categoryVehicles","purchase_price":null,
               "odometer":15000,"tracked_dates":[{"id":4,"item_id":1,"label":"TÜV","date":"2027-01-01","reminder_offset_days":30,"interval_months":24,"interval_distance":null}],
               "linked_entries":[{"id":1,"entry_id":9,"role":"purchase","amount_share":null,"title":"Car","amount":-20000,"date":"2024-01-02"}],
               "attachments":[{"id":2,"document_id":5,"name":"Invoice","original_name":"i.pdf","mime_type":"application/pdf"}]}]}""",
        )
        val car = InventoryApi.decodeItems(json).single()
        assertEquals(24, car.trackedDates.single().intervalMonths)
        assertEquals(-20000.0, car.linkedEntries.single().amount, 0.0)
        assertEquals("Invoice", car.attachments.single().label)
        assertEquals(15000L, car.odometer)
        val tree = InventoryApi.decodeLocations(ApiJson.parseToJsonElement("""{"data":[{"id":1,"name":"Garage","parent_id":null,"subcategories":[{"id":3,"name":"Shelf","parent_id":1}]}]}"""))
        assertEquals(3, tree.single().subcategories.single().id)
        val cats = InventoryApi.decodeCategories(ApiJson.parseToJsonElement("""{"data":[{"key":"vehicles","tracks_odometer":1}]}"""))
        assertTrue(cats.single().tracksOdometer)
    }
}
