#!/usr/bin/env bash
set -euo pipefail

# 1) DOMAIN models
cat > app/src/main/java/com/ml/app/domain/Models.kt <<'EOF'
package com.ml.app.domain

data class ColorValue(val color: String, val value: Double)

data class AdsMetrics(
  val spend: Double = 0.0,
  val impressions: Long = 0,
  val clicks: Long = 0,
  val ctr: Double = 0.0,
  val cpc: Double = 0.0
)

data class BagDayRow(
  val bag: String,
  val price: Double?,
  val hypothesis: String?,
  val imagePath: String?,

  // header stats
  val totalOrders: Double,   // показываем как Int
  val totalSpend: Double,
  val cpo: Double,

  // breakdowns
  val ordersByColors: List<ColorValue>,
  val stockByColors: List<ColorValue>,

  // ads
  val rk: AdsMetrics,
  val ig: AdsMetrics,
  val totalAds: AdsMetrics
)

data class BagOrders(
  val bag: String,
  val orders: Int
)

data class DaySummary(
  val date: String,          // YYYY-MM-DD
  val totalOrders: Int,
  val byBags: List<BagOrders>
)
EOF

# 2) SQLiteRepo: добавляем timeline + делаем orders/stock целыми
cat > app/src/main/java/com/ml/app/data/SQLiteRepo.kt <<'EOF'
package com.ml.app.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.ml.app.domain.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

class SQLiteRepo(private val context: Context) {

  private fun openDb(): SQLiteDatabase {
    val dbFile: File = PackPaths.dbFile(context)
    return SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
  }

  private fun normalizeColor(raw: String?, bag: String?): String? {
    if (raw == null) return null
    val s = raw.trim()
    if (s.isEmpty()) return null

    val low = s.lowercase()
    if (low == "__total__" || low == "total") return null
    if (low.contains("#")) return null
    if (low.contains("div/")) return null
    if (low.matches(Regex("-?\\d+(\\.\\d+)?"))) return null

    val bad = listOf(
      "сумка","рюкзак","органика","инста","instagram","внутренняя","рк",
      "гипотеза","цена","заказы","остаток","расход","ставка","показы",
      "клики","ctr","cpc","цвет"
    )
    if (bad.any { low.contains(it) }) return null

    bag?.let { if (low == it.trim().lowercase()) return null }

    if (s.length > 25) return null
    return s
  }

  // ---------- TIMELINE ----------
  suspend fun listDatesDesc(limit: Int = 120): List<String> = withContext(Dispatchers.IO) {
    openDb().use { db ->
      val out = ArrayList<String>()
      db.rawQuery(
        "SELECT DISTINCT date FROM svodka ORDER BY date DESC LIMIT $limit",
        null
      ).use { c ->
        val i = c.getColumnIndexOrThrow("date")
        while (c.moveToNext()) out.add(c.getString(i))
      }
      out
    }
  }

  suspend fun loadTimeline(limitDays: Int = 120): List<DaySummary> = withContext(Dispatchers.IO) {
    openDb().use { db ->
      val dates = ArrayList<String>()
      db.rawQuery(
        "SELECT DISTINCT date FROM svodka ORDER BY date DESC LIMIT $limitDays",
        null
      ).use { c ->
        val i = c.getColumnIndexOrThrow("date")
        while (c.moveToNext()) dates.add(c.getString(i))
      }

      val out = ArrayList<DaySummary>(dates.size)

      for (date in dates) {
        val byBags = ArrayList<BagOrders>()

        db.rawQuery(
          """
          SELECT bag, CAST(ROUND(COALESCE(orders,0)) AS INTEGER) AS o
          FROM svodka
          WHERE date=? AND (color="__TOTAL__" OR color="TOTAL")
          ORDER BY o DESC, bag ASC
          """.trimIndent(),
          arrayOf(date)
        ).use { c ->
          val ib = c.getColumnIndexOrThrow("bag")
          val io = c.getColumnIndexOrThrow("o")
          while (c.moveToNext()) {
            val bag = c.getString(ib)?.trim() ?: continue
            val o = c.getInt(io)
            byBags.add(BagOrders(bag, o))
          }
        }

        val total = byBags.sumOf { it.orders }
        out.add(DaySummary(date = date, totalOrders = total, byBags = byBags))
      }

      out
    }
  }

  // ---------- DETAILS (как раньше) ----------
  suspend fun loadForDate(date: String): List<BagDayRow> = withContext(Dispatchers.IO) {
    openDb().use { db ->
      val bags = LinkedHashSet<String>()
      db.rawQuery(
        "SELECT DISTINCT bag FROM svodka WHERE date=? ORDER BY bag",
        arrayOf(date)
      ).use { c ->
        val i = c.getColumnIndexOrThrow("bag")
        while (c.moveToNext()) {
          val b = c.getString(i)
          if (!b.isNullOrBlank()) bags.add(b)
        }
      }

      val result = ArrayList<BagDayRow>()
      for (bag in bags) {

        var price: Double? = null
        var hypothesis: String? = null
        var totalOrders = 0.0

        var rk = AdsMetrics()
        var ig = AdsMetrics()

        db.rawQuery(
          """
          SELECT *
          FROM svodka
          WHERE date=? AND bag=? AND (color="__TOTAL__" OR color="TOTAL")
          LIMIT 1
          """.trimIndent(),
          arrayOf(date, bag)
        ).use { c ->
          if (c.moveToFirst()) {
            val ip = c.getColumnIndex("price")
            if (ip >= 0 && !c.isNull(ip)) price = c.getDouble(ip)

            val ih = c.getColumnIndex("hypothesis")
            if (ih >= 0 && !c.isNull(ih)) hypothesis = c.getString(ih)

            val io = c.getColumnIndex("orders")
            if (io >= 0 && !c.isNull(io)) totalOrders = c.getDouble(io).roundToInt().toDouble()

            // если колонок нет — сборка упадёт. Но у нас schema эталонная, так что ок.
            rk = AdsMetrics(
              spend = c.getDouble(c.getColumnIndexOrThrow("rk_spend")),
              impressions = c.getLong(c.getColumnIndexOrThrow("rk_impressions")),
              clicks = c.getLong(c.getColumnIndexOrThrow("rk_clicks")),
              ctr = c.getDouble(c.getColumnIndexOrThrow("rk_ctr")),
              cpc = c.getDouble(c.getColumnIndexOrThrow("rk_cpc"))
            )
            ig = AdsMetrics(
              spend = c.getDouble(c.getColumnIndexOrThrow("ig_spend")),
              impressions = c.getLong(c.getColumnIndexOrThrow("ig_impressions")),
              clicks = c.getLong(c.getColumnIndexOrThrow("ig_clicks")),
              ctr = c.getDouble(c.getColumnIndexOrThrow("ig_ctr")),
              cpc = c.getDouble(c.getColumnIndexOrThrow("ig_cpc"))
            )
          }
        }

        val ordersByColors = mutableListOf<ColorValue>()
        val stockByColors = mutableListOf<ColorValue>()

        db.rawQuery(
          """
          SELECT color, COALESCE(orders,0) AS o, COALESCE(stock,0) AS s
          FROM svodka
          WHERE date=? AND bag=? AND color NOT IN ("__TOTAL__","TOTAL")
          """.trimIndent(),
          arrayOf(date, bag)
        ).use { c ->
          val ic = c.getColumnIndexOrThrow("color")
          val io = c.getColumnIndexOrThrow("o")
          val isx = c.getColumnIndexOrThrow("s")

          while (c.moveToNext()) {
            val color = normalizeColor(c.getString(ic), bag) ?: continue
            val o = c.getDouble(io).roundToInt().toDouble()
            val s = c.getDouble(isx).roundToInt().toDouble()

            if (o > 0) ordersByColors.add(ColorValue(color, o))
            if (s > 0) stockByColors.add(ColorValue(color, s))
          }
        }

        val totalSpend = rk.spend + ig.spend
        val cpo = if (totalOrders > 0) totalSpend / totalOrders else 0.0

        val totalImpr = rk.impressions + ig.impressions
        val totalClicks = rk.clicks + ig.clicks

        val totalAds = AdsMetrics(
          spend = totalSpend,
          impressions = totalImpr,
          clicks = totalClicks,
          ctr = if (totalImpr > 0) totalClicks.toDouble() / totalImpr else 0.0,
          cpc = if (totalClicks > 0) totalSpend / totalClicks else 0.0
        )

        result.add(
          BagDayRow(
            bag = bag,
            price = price,
            hypothesis = hypothesis,
            imagePath = null,
            totalOrders = totalOrders,
            totalSpend = totalSpend,
            cpo = cpo,
            ordersByColors = ordersByColors.sortedByDescending { it.value },
            stockByColors = stockByColors.sortedByDescending { it.value },
            rk = rk,
            ig = ig,
            totalAds = totalAds
          )
        )
      }

      result.sortedByDescending { it.totalOrders }
    }
  }
}
EOF

# 3) ViewModel: режим Timeline -> Details
cat > app/src/main/java/com/ml/app/ui/SummaryViewModel.kt <<'EOF'
package com.ml.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ml.app.data.PackPaths
import com.ml.app.domain.BagDayRow
import com.ml.app.domain.DaySummary
import com.ml.app.data.SQLiteRepo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate

sealed class ScreenMode {
  data object Timeline : ScreenMode()
  data class Details(val date: LocalDate) : ScreenMode()
}

data class SummaryState(
  val mode: ScreenMode = ScreenMode.Timeline,
  val selectedDate: LocalDate = LocalDate.now(),

  // timeline
  val timeline: List<DaySummary> = emptyList(),

  // details
  val rows: List<BagDayRow> = emptyList(),

  val status: String = "",
  val loading: Boolean = false,
  val hasPack: Boolean = false
)

class SummaryViewModel(app: Application) : AndroidViewModel(app) {
  private val ctx = app.applicationContext
  private val repo = SQLiteRepo(ctx)

  private val _state = MutableStateFlow(SummaryState())
  val state: StateFlow<SummaryState> = _state

  fun init() {
    val has = PackPaths.dbFile(ctx).exists()
    _state.value = _state.value.copy(hasPack = has)
    if (has) refreshTimeline()
  }

  fun refreshTimeline() {
    viewModelScope.launch(Dispatchers.IO) {
      try {
        _state.value = _state.value.copy(loading = true, status = "Loading timeline…", mode = ScreenMode.Timeline)
        val t = repo.loadTimeline(limitDays = 180)
        _state.value = _state.value.copy(timeline = t, loading = false, status = "OK")
      } catch (t: Throwable) {
        _state.value = _state.value.copy(loading = false, status = "Error: ${t.message}")
      }
    }
  }

  fun openDetails(date: LocalDate) {
    _state.value = _state.value.copy(selectedDate = date, mode = ScreenMode.Details(date))
    refreshDetails()
  }

  fun backToTimeline() {
    _state.value = _state.value.copy(mode = ScreenMode.Timeline)
  }

  fun setDateFromPicker(date: LocalDate) {
    // на таймлайне — сразу открываем детали
    openDetails(date)
  }

  fun refreshDetails() {
    viewModelScope.launch(Dispatchers.IO) {
      try {
        _state.value = _state.value.copy(loading = true, status = "Loading details…")
        val rows = repo.loadForDate(_state.value.selectedDate.toString())
        _state.value = _state.value.copy(rows = rows, loading = false, status = "OK")
      } catch (t: Throwable) {
        _state.value = _state.value.copy(loading = false, status = "Error: ${t.message}")
      }
    }
  }
}
EOF

# 4) UI: Timeline + Details в одном экране
cat > app/src/main/java/com/ml/app/ui/SummaryScreen.kt <<'EOF'
package com.ml.app.ui

import android.app.DatePickerDialog
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ml.app.domain.BagDayRow
import com.ml.app.domain.DaySummary
import java.time.LocalDate
import kotlin.math.roundToInt

private val MercadoYellow = Color(0xFFFFE600)
private val MercadoBlue = Color(0xFF2D3277)
private val TextBlack = Color(0xFF111111)
private val SoftGray = Color(0xFFF7F7F7)

private fun fmtInt(v: Double): String = v.roundToInt().toString()
private fun fmtMoney(v: Double): String = String.format("%.2f", v)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SummaryScreen(vm: SummaryViewModel = viewModel()) {
  val state by vm.state.collectAsState()
  val ctx = LocalContext.current

  LaunchedEffect(Unit) { vm.init() }

  fun openDatePicker(current: LocalDate, onPicked: (LocalDate) -> Unit) {
    DatePickerDialog(
      ctx,
      { _, y, m, d -> onPicked(LocalDate.of(y, m + 1, d)) },
      current.year, current.monthValue - 1, current.dayOfMonth
    ).show()
  }

  Column(
    modifier = Modifier
      .fillMaxSize()
      .background(Color.White)
  ) {
    // Header
    Box(
      modifier = Modifier
        .fillMaxWidth()
        .background(MercadoYellow)
        .padding(14.dp)
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
      ) {
        Text(
          text = "ml",
          style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Black),
          color = TextBlack
        )
        Spacer(Modifier.weight(1f))

        if (state.mode is ScreenMode.Details) {
          TextButton(onClick = { vm.backToTimeline() }) { Text("Назад", color = TextBlack) }
        } else {
          TextButton(onClick = { vm.refreshTimeline() }) { Text("Обновить", color = TextBlack) }
        }
      }
    }

    if (!state.hasPack) {
      // если пакета нет — пока просто сообщение (скачивание у тебя было в другом VM, если нужно — вернем)
      Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("Нет базы данных (pack). Сначала скачай/положи database_pack.", color = TextBlack)
      }
      return@Column
    }

    // Top date picker (и на таймлайне, и в деталях)
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(12.dp),
      horizontalArrangement = Arrangement.spacedBy(12.dp),
      verticalAlignment = Alignment.CenterVertically
    ) {
      Button(
        onClick = { openDatePicker(state.selectedDate) { vm.setDateFromPicker(it) } },
        colors = ButtonDefaults.buttonColors(containerColor = SoftGray, contentColor = TextBlack),
        modifier = Modifier.weight(1f)
      ) {
        Text("Дата: ${state.selectedDate}", maxLines = 1, overflow = TextOverflow.Ellipsis)
      }

      if (state.mode is ScreenMode.Details) {
        Button(
          onClick = { vm.refreshDetails() },
          colors = ButtonDefaults.buttonColors(containerColor = MercadoBlue, contentColor = Color.White)
        ) { Text("Обновить") }
      }
    }

    if (state.loading) {
      LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    }

    when (val mode = state.mode) {
      is ScreenMode.Timeline -> TimelineList(
        items = state.timeline,
        onOpen = { vm.openDetails(LocalDate.parse(it.date)) }
      )
      is ScreenMode.Details -> DetailsList(rows = state.rows)
    }

    if (state.status.isNotBlank()) {
      Text(
        text = state.status,
        modifier = Modifier.padding(12.dp),
        color = Color.Gray
      )
    }
  }
}

@Composable
private fun TimelineList(items: List<DaySummary>, onOpen: (DaySummary) -> Unit) {
  LazyColumn(
    modifier = Modifier.fillMaxSize(),
    contentPadding = PaddingValues(12.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp)
  ) {
    items(items) { day ->
      Card(
        colors = CardDefaults.cardColors(containerColor = SoftGray),
        modifier = Modifier
          .fillMaxWidth()
          .clickable { onOpen(day) }
      ) {
        Column(Modifier.padding(14.dp)) {
          Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
              text = day.date,
              style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
              color = TextBlack
            )
            Spacer(Modifier.weight(1f))
            Text(
              text = "Заказы: ${day.totalOrders}",
              style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Black),
              color = MercadoBlue
            )
          }

          Spacer(Modifier.height(8.dp))

          day.byBags.take(12).forEach { b ->
            Row(Modifier.fillMaxWidth()) {
              Text(
                text = b.bag,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = TextBlack
              )
              Text(text = b.orders.toString(), color = TextBlack, fontWeight = FontWeight.SemiBold)
            }
          }
        }
      }
    }
  }
}

@Composable
private fun DetailsList(rows: List<BagDayRow>) {
  LazyColumn(
    modifier = Modifier.fillMaxSize(),
    contentPadding = PaddingValues(12.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp)
  ) {
    items(rows) { r ->
      Card(
        colors = CardDefaults.cardColors(containerColor = SoftGray),
        modifier = Modifier.fillMaxWidth()
      ) {
        Column(Modifier.padding(14.dp)) {
          Text(
            text = r.bag,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            color = TextBlack
          )

          Spacer(Modifier.height(6.dp))

          Row(Modifier.fillMaxWidth()) {
            Text("Заказы: ${fmtInt(r.totalOrders)}", modifier = Modifier.weight(1f), color = TextBlack)
            Text("Расход: ${fmtMoney(r.totalSpend)}", color = TextBlack)
          }

          if (!r.hypothesis.isNullOrBlank() || r.price != null) {
            Spacer(Modifier.height(6.dp))
            Text(
              text = "${r.hypothesis ?: ""}${if (r.price != null) " • Цена: ${fmtMoney(r.price)}" else ""}",
              color = Color.Gray,
              maxLines = 2,
              overflow = TextOverflow.Ellipsis
            )
          }

          Spacer(Modifier.height(10.dp))

          if (r.ordersByColors.isNotEmpty()) {
            Text("Заказы по цветам", fontWeight = FontWeight.SemiBold, color = TextBlack)
            Spacer(Modifier.height(6.dp))
            r.ordersByColors.take(12).forEach { cv ->
              Row(Modifier.fillMaxWidth()) {
                Text(cv.color, modifier = Modifier.weight(1f), color = TextBlack)
                Text(fmtInt(cv.value), color = TextBlack, fontWeight = FontWeight.SemiBold)
              }
            }
          }

          if (r.stockByColors.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Text("Остаток по цветам", fontWeight = FontWeight.SemiBold, color = TextBlack)
            Spacer(Modifier.height(6.dp))
            r.stockByColors.take(12).forEach { cv ->
              Row(Modifier.fillMaxWidth()) {
                Text(cv.color, modifier = Modifier.weight(1f), color = TextBlack)
                Text(fmtInt(cv.value), color = TextBlack, fontWeight = FontWeight.SemiBold)
              }
            }
          }

          Spacer(Modifier.height(10.dp))
          Text(
            text = "РК: ${fmtMoney(r.rk.spend)} • IG: ${fmtMoney(r.ig.spend)} • CPO: ${fmtMoney(r.cpo)}",
            color = Color.Gray
          )
        }
      }
    }
  }
}
EOF

echo "✅ Patch applied: timeline + details + integer orders/stock"
