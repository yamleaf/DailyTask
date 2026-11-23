package com.pengxh.daily.app.utils

import android.util.Log
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.IOException
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

object ChinaHolidayManager {
    private const val kTag = "ChinaHolidayManager"
    private const val CACHE_KEY = "holidayConfig"

    // 后台 WorkManager 刷新时通常没有 UI 订阅者；默认 SUSPEND 会在缓冲满后挂起 emit，
    // 进而卡住 updateChinaHolidayData 的 finally（isSyncing 永远为 true）。
    // 用 DROP_OLDEST 保证后台同步永不被 UI 订阅状态阻塞。
    private val _syncResult = MutableSharedFlow<SyncResult>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val syncResult = _syncResult.asSharedFlow()

    /**
     * CDN 镜像源
     * */
    private val cdnUrlTemplates = listOf(
        "https://cdn.jsdelivr.net/npm/chinese-days/dist/years/%s.json",
        "https://fastly.jsdelivr.net/npm/chinese-days/dist/years/%s.json",
        "https://registry.npmmirror.com/chinese-days/latest/files/dist/years/%s.json"
    )

    sealed class SyncResult {
        data class Success(val content: String) : SyncResult()
        data class Error(val message: String) : SyncResult()
    }

    private val scope = CoroutineScope(SupervisorJob())
    private val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    @Volatile
    private var holidayDates: Set<LocalDate> = emptySet()

    @Volatile
    private var workdayDates: Set<LocalDate> = emptySet()

    private val isSyncing = AtomicBoolean(false)
    private val dataMutex = Mutex()

    fun updateChinaHolidayData(onComplete: (() -> Unit)? = null) {
        if (!isSyncing.compareAndSet(false, true)) {
            Log.w(kTag, "Already syncing, skip duplicate request")
            return
        }
        scope.launch {
            try {
                // 缓存覆盖范围满足（当年 + 11/12 月时含次年）则直接可用；不足才走网络补拉
                if (!tryLoadFromCache()) {
                    fetchAndParse()
                }
            } finally {
                isSyncing.set(false)
                // 同步真正结束（成功或失败、缓存或网络）后再回调，调用方据此推送最新数据
                onComplete?.invoke()
            }
        }
    }

    /**
     * 从本地缓存加载节假日数据。
     * @return true 表示缓存覆盖已满足查询窗口（当前年 + 11/12 月时含次年），无需联网；
     *         false 表示缓存缺失/覆盖不足，需要网络补拉。
     * 覆盖不足时也会先把缓存中的已有年份解析进内存，作为网络失败时的尽力回退。
     */
    private suspend fun tryLoadFromCache(): Boolean {
        return withContext(Dispatchers.IO) {
            val cache = ConfigStore.get().load(CACHE_KEY)
            if (cache.size() == 0) return@withContext false

            val cachedYear = cache.get("year")?.asInt ?: return@withContext false
            // 旧缓存无 fromYear 字段，视为仅覆盖单年（cachedYear）
            val fromYear = cache.get("fromYear")?.asInt ?: cachedYear
            val now = LocalDate.now()
            val nowYear = now.year
            // 11 月起国务院已公布次年放假安排，查询窗口延伸到次年 1 月，缓存需一并覆盖次年
            val needToYear = if (now.monthValue >= 11) nowYear + 1 else nowYear

            val holidaysObj = cache.getAsJsonObject("holidays")
            val workdaysObj = cache.getAsJsonObject("workdays")
            val hasData = (holidaysObj?.size() ?: 0) > 0 || (workdaysObj?.size() ?: 0) > 0

            dataMutex.withLock {
                parseJsonToMemory(cache)
            }
            val enough = hasData && fromYear <= nowYear && cachedYear >= needToYear
            if (!enough) {
                Log.d(
                    kTag,
                    "Cache coverage [$fromYear..$cachedYear] insufficient (need through $needToYear), will fetch network"
                )
            } else {
                notifySuccess("节假日数据同步完成（缓存）")
            }
            enough
        }
    }

    private suspend fun fetchAndParse() {
        val now = LocalDate.now()
        val nowYear = now.year
        // 当年必拉；11 月后国务院已公布次年安排，顺带拉次年以覆盖来年 1 月（次年文件未发布时 404，忽略即可）
        val years = if (now.monthValue >= 11) listOf(nowYear, nowYear + 1) else listOf(nowYear)
        var successCount = 0

        for (year in years) {
            val urls = cdnUrlTemplates.map { String.format(it, year) }
            var resultJson: String? = null

            for (url in urls) {
                val outcome = downloadJson(url)
                when (outcome) {
                    is SyncResult.Success -> {
                        resultJson = outcome.content
                        break
                    }

                    is SyncResult.Error -> logFetchFailure(year, outcome.message)
                }
            }

            if (resultJson != null && handleYearData(year, resultJson)) {
                successCount++
            }
        }

        if (successCount == 0) {
            notifyError("节假日数据下载失败：所有镜像源均不可用")
            return
        }

        persistCacheFromMemory()
        notifySuccess("节假日数据同步完成（网络）")
    }

    private fun logFetchFailure(year: Int, message: String) {
        // 次年文件通常要等国务院在前一年 11-12 月公布后才收录，404 属预期，仅 debug 记录避免日志噪音
        if (message.contains("404")) {
            Log.d(kTag, "Year $year not published yet: $message")
        } else {
            Log.w(kTag, "Network error for year $year: $message")
        }
    }

    private suspend fun downloadJson(urlString: String): SyncResult {
        return withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder().url(urlString).build()
                okHttpClient.newCall(request).execute().use { response ->
                    val body = response.body.string()
                    if (!response.isSuccessful || body.isEmpty()) {
                        SyncResult.Error("HTTP ${response.code}")
                    } else {
                        try {
                            // 仅做Json格式校验，返回值不必处理
                            val element = JsonParser.parseString(body)
                        } catch (_: Exception) {
                            return@withContext SyncResult.Error("Invalid JSON")
                        }
                        SyncResult.Success(body)
                    }
                }
            } catch (e: IOException) {
                SyncResult.Error(e.message ?: "Unknown")
            }
        }
    }

    /**
     * 解析单个年份文件并入内存（当年数据替换、跨年数据保留），返回是否成功并入有效数据。
     */
    private suspend fun handleYearData(year: Int, rawJson: String): Boolean {
        val root = try {
            JsonParser.parseString(rawJson).asJsonObject
        } catch (e: Exception) {
            Log.e(kTag, "JSON parse failed for year $year", e)
            notifyError("节假日数据解析失败：JSON 无效")
            return false
        }

        val merged = dataMutex.withLock {
            mergeYearData(
                year,
                root.getAsJsonObject("holidays") ?: JsonObject(),
                root.getAsJsonObject("workdays") ?: JsonObject()
            )
        }
        if (!merged) {
            Log.w(kTag, "Year $year data has no valid holiday/workday entries")
        }
        return merged
    }

    private fun parseJsonToMemory(root: JsonObject) {
        val holidaysObj = root.getAsJsonObject("holidays") ?: JsonObject()
        holidayDates = holidaysObj.keySet().mapNotNull { parseDate(it) }.toSet()

        val workdaysObj = root.getAsJsonObject("workdays") ?: JsonObject()
        workdayDates = workdaysObj.keySet().mapNotNull { parseDate(it) }.toSet()

        Log.d(kTag, "Parsed ${holidayDates.size} holidays, ${workdayDates.size} workdays")
    }

    /**
     * 将单年数据并入内存集合：同年整体替换（数据源修订后不残留旧日期），其他年份保留。
     */
    private fun mergeYearData(year: Int, holidaysObj: JsonObject, workdaysObj: JsonObject): Boolean {
        val newHolidays = holidaysObj.keySet().mapNotNull { parseDate(it) }.filter { it.year == year }.toSet()
        val newWorkdays = workdaysObj.keySet().mapNotNull { parseDate(it) }.filter { it.year == year }.toSet()
        if (newHolidays.isEmpty() && newWorkdays.isEmpty()) {
            return false
        }
        holidayDates = (holidayDates.filterNot { it.year == year } + newHolidays).toSet()
        workdayDates = (workdayDates.filterNot { it.year == year } + newWorkdays).toSet()
        Log.d(kTag, "Merged year $year: +${newHolidays.size} holidays, +${newWorkdays.size} workdays")
        return true
    }

    /**
     * 用内存数据重建缓存（网络同步成功后调用）。
     * 裁剪保留 [当前年-1 .. 当前年+1] 窗口，避免多年同步导致缓存/内存无限膨胀；
     * 缓存结构：year=最大覆盖年、fromYear=最小覆盖年、holidays/workdays=各年日期并集。
     * 旧版本 App 读到该结构仅按并集整体解析，行为正确；旧版本再同步会覆写回单年缓存，同样兼容。
     */
    private suspend fun persistCacheFromMemory() {
        val nowYear = LocalDate.now().year
        val keepFrom = nowYear - 1
        val keepTo = nowYear + 1
        // 读取+修剪+回写统一在 dataMutex 内完成：与 parseJsonToMemory/mergeYearData 的写集合操作互斥，
        // 避免读到「另一次写入进行中」的半新集合（如某年 merge 完成但另一年尚未并入）。
        // 虽然 isSyncing CAS 已把同步串行化，此锁为双保险，开销仅一次 withLock。
        val (keepHolidays, keepWorkdays) = dataMutex.withLock {
            val keepHolidays = holidayDates.filter { it.year in keepFrom..keepTo }.toSet()
            val keepWorkdays = workdayDates.filter { it.year in keepFrom..keepTo }.toSet()
            // 同步修剪内存，保证内存/缓存/UI 计数一致
            holidayDates = keepHolidays
            workdayDates = keepWorkdays
            keepHolidays to keepWorkdays
        }

        val allYears = keepHolidays.map { it.year } + keepWorkdays.map { it.year }
        val cacheObj = JsonObject()
        val minYear = allYears.minOrNull()
        val maxYear = allYears.maxOrNull()
        if (minYear == null || maxYear == null) {
            // 数据为空（异常兜底）：写空标记，避免残留旧缓存让查询长期沿用过期数据
            cacheObj.addProperty("year", nowYear)
            cacheObj.addProperty("fromYear", nowYear)
            cacheObj.add("holidays", JsonObject())
            cacheObj.add("workdays", JsonObject())
        } else {
            cacheObj.addProperty("year", maxYear)
            cacheObj.addProperty("fromYear", minYear)
            val holidaysOut = JsonObject()
            keepHolidays.forEach { holidaysOut.addProperty(it.toString(), "") }
            val workdaysOut = JsonObject()
            keepWorkdays.forEach { workdaysOut.addProperty(it.toString(), "") }
            cacheObj.add("holidays", holidaysOut)
            cacheObj.add("workdays", workdaysOut)
        }
        ConfigStore.get().save(CACHE_KEY, cacheObj)
    }

    private fun parseDate(dateStr: String): LocalDate? {
        return try {
            LocalDate.parse(dateStr, dateFormatter)
        } catch (e: Exception) {
            Log.w(kTag, "Invalid date format: $dateStr", e)
            null
        }
    }

    /**
     * 给定日期是否为法定节假日（含调休放假，不含普通周末）
     * */
    fun isHoliday(date: LocalDate): Boolean {
        return date in holidayDates
    }

    /**
     * 给定日期是否为调休补班日
     * */
    fun isWorkday(date: LocalDate): Boolean {
        return date in workdayDates
    }

    /** 是否正在同步节假日数据（供快照上报与控制端 UI 展示） */
    fun isSyncing(): Boolean = isSyncing.get()

    /**
     * 是否有可显示的节假日/补班数据：内存中有数据 OR 已存在远程缓存（即使尚未解析进内存）。
     * 用于任务配置页等 UI 条件显示「节假日数据」信息块；空状态时该区块隐藏，避免展示无意义占位。
     */
    fun hasAvailableData(): Boolean =
        holidayDates.isNotEmpty() || workdayDates.isNotEmpty() ||
            runCatching { ConfigStore.get().contains(CACHE_KEY) }.getOrDefault(false)

    /** 已加载的节假日总天数（11/12 月跨年同步后含当年+次年累计，无数据返回 0） */
    fun getHolidayCount(): Int = holidayDates.size

    /** 已加载的调休补班总天数（11/12 月跨年同步后含当年+次年累计，无数据返回 0） */
    fun getWorkdayCount(): Int = workdayDates.size

    /** 缓存覆盖的年份范围 [最小覆盖年, 最大覆盖年]（无缓存或读取失败返回 null） */
    fun getCachedCoverage(): Pair<Int, Int>? = runCatching {
        val cache = ConfigStore.get().load(CACHE_KEY)
        if (cache.size() == 0) return@runCatching null
        val toYear = cache.get("year")?.asInt ?: return@runCatching null
        val fromYear = cache.get("fromYear")?.asInt ?: toYear
        fromYear to toYear
    }.getOrNull()

    /** 缓存数据覆盖的最大年份（兼容旧调用方；无缓存返回 null） */
    fun getCachedYear(): Int? = getCachedCoverage()?.second

    fun cancel() {
        scope.cancel()
    }

    private suspend fun notifySuccess(message: String) {
        _syncResult.emit(SyncResult.Success(message))
    }

    private suspend fun notifyError(message: String) {
        _syncResult.emit(SyncResult.Error(message))
    }
}