package com.tans.tpowercalculator.internal

import android.os.Process
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import java.io.File

internal class CpuStateSnapshotCapture(private val powerProfile: PowerProfile) {

    val isInitSuccess: Boolean

    private val cpuSpeedSpecs: List<CpuSpec> by lazy {
        readCpuCoresSpec(powerProfile.cpuProfile.coreCount)
    }

    init {
        isInitSuccess = try {
            checkCpuSpeedAndTime()
            checkProcessCpuSpeedAndTime()
            checkCpuCoreIdleTime()
            checkCpuCoreSpeed()
            cpuSpeedSpecs
            tPowerLog.d(TAG, "Init CpuStateSnapshotCapture success.")
            true
        } catch (e: Throwable) {
            tPowerLog.e(TAG, "Init CpuStateSnapshotCapture fail.", e)
            false
        }
    }

    fun createCpuStateSnapshot(): CpuStateSnapshot? {
        return if (isInitSuccess) {
            val cpuCoreCount = powerProfile.cpuProfile.coreCount
            val currentProcessCpuSpeedToTime = readCurrentProcessCpuCoreTime()
            val coreStates = mutableListOf<SingleCoreStateSnapshot>()
            repeat(cpuCoreCount) { coreIndex ->
                val cpuSpeed = readCpuCoreSpeed(coreIndex)
                val cpuSpeedToTime = readCpuCoreTime(coreIndex)
                val cpuIdleTime = readCpuCoreIdleTime(coreIndex)
                coreStates.add(
                    SingleCoreStateSnapshot(
                        coreIndex = coreIndex,
                        currentCoreSpeedInKHz = cpuSpeed,
                        cpuSpeedToTime = cpuSpeedToTime,
                        cpuIdleTime = cpuIdleTime
                    )
                )
            }
            CpuStateSnapshot(
                createTime = SystemClock.uptimeMillis(),
                coreStates = coreStates,
                currentProcessCpuSpeedToTime = currentProcessCpuSpeedToTime
            )
        } else {
            null
        }
    }

    fun calculateCpuUsage(state1: CpuStateSnapshot, state2: CpuStateSnapshot): CpuUsage {
        val previous: CpuStateSnapshot
        val next: CpuStateSnapshot
        if (state1.createTime < state2.createTime) {
            previous = state1
            next = state2
        } else {
            next = state1
            previous = state2
        }
        val durationInMillis = next.createTime - previous.createTime

        // All processes cpu cores usages.
        val cpuCoreUsages = mutableListOf<SingleCpuCoreUsage>()
        for (ns in next.coreStates) {
            val coreSpec = cpuSpeedSpecs[ns.coreIndex]
            val ps = previous.coreStates[ns.coreIndex]
            val cpuIdleTimeInJiffies = ns.cpuIdleTime - ps.cpuIdleTime
            var cpuWorkTimeInJiffies = 0L
            var allCpuTimeInJiffies = cpuIdleTimeInJiffies
            for ((index, nSpeedAndTime) in ns.cpuSpeedToTime.withIndex()) {
                val (speed, nTimeInJiffies) = nSpeedAndTime
                val (_, pTimeInJiffies) = ps.cpuSpeedToTime[index]
                val diffInJiffies = nTimeInJiffies - pTimeInJiffies
                allCpuTimeInJiffies += diffInJiffies
                cpuWorkTimeInJiffies += (diffInJiffies.toDouble() * speed.toDouble() / coreSpec.maxSpeedInKHz.toDouble()).toLong()
            }
            val cpuUsage = cpuWorkTimeInJiffies.toDouble() / allCpuTimeInJiffies.toDouble()
            cpuCoreUsages.add(
                SingleCpuCoreUsage(
                    coreIndex = ns.coreIndex,
                    coreSpec = coreSpec,
                    currentCoreSpeedInKHz = ns.currentCoreSpeedInKHz,
                    cpuUsage = cpuUsage,
                    allCpuTimeInJiffies = allCpuTimeInJiffies,
                    cpuIdleTimeInJiffies = cpuIdleTimeInJiffies,
                    cpuWorkTimeInJiffies = cpuWorkTimeInJiffies
                )
            )
        }
        val maxCpuSpeed = cpuSpeedSpecs.maxBy { it.maxSpeedInKHz }.maxSpeedInKHz
        var avgCpuUsageNum = 0.0
        var avgCpuUsageDen = 0.0
        for (usage in cpuCoreUsages) {
            val coreSpec = cpuSpeedSpecs[usage.coreIndex]
            avgCpuUsageDen += coreSpec.maxSpeedInKHz.toDouble() / maxCpuSpeed.toDouble()
            avgCpuUsageNum += coreSpec.maxSpeedInKHz.toDouble() / maxCpuSpeed.toDouble() * usage.cpuUsage
        }
        val avgCpuUsage = avgCpuUsageNum / avgCpuUsageDen

        // Current process cpu cores usage.
        val currentProcessCpuCoresUsage = mutableListOf<ProgressSingleCpuCoreUsage>()
        for (ns in next.currentProcessCpuSpeedToTime) {
            val coreIndex = ns.key
            val ps = previous.currentProcessCpuSpeedToTime[coreIndex]
            val core = cpuCoreUsages.find { it.coreIndex == coreIndex }!!
            var cpuWorkTimeInJiffies = 0L
            for ((speed, nTimeInJiffies) in ns.value) {
                val pTimeInJiffies = ps?.find { it.first == speed }?.second ?: 0L
                val diffInJiffies = nTimeInJiffies - pTimeInJiffies
                cpuWorkTimeInJiffies += (diffInJiffies.toDouble() * speed.toDouble() / core.coreSpec.maxSpeedInKHz.toDouble()).toLong()
            }
            val usage = cpuWorkTimeInJiffies.toDouble() / core.allCpuTimeInJiffies.toDouble()
            currentProcessCpuCoresUsage.add(
                ProgressSingleCpuCoreUsage(
                    refCore = core,
                    cpuWorkTimeInJiffies = cpuWorkTimeInJiffies,
                    cpuUsage = usage
                )
            )
        }
        var currentProcessAvgCpuUsageNum = 0.0
        var currentProcessAvgCpuUsageDen = 0.0
        for (usage in currentProcessCpuCoresUsage) {
            currentProcessAvgCpuUsageDen += usage.refCore.coreSpec.maxSpeedInKHz.toDouble() / maxCpuSpeed.toDouble()
            currentProcessAvgCpuUsageNum += usage.refCore.coreSpec.maxSpeedInKHz.toDouble() / maxCpuSpeed.toDouble() * usage.cpuUsage
        }
        val currentProcessAvgCpuUsage = currentProcessAvgCpuUsageNum / currentProcessAvgCpuUsageDen
        return CpuUsage(
            durationInMillis = durationInMillis,
            cpuCoresUsage = cpuCoreUsages,
            avgCpuUsage = avgCpuUsage,
            currentProcessCpuCoresUsage = currentProcessCpuCoresUsage,
            currentProcessAvgCpuUsage = currentProcessAvgCpuUsage
        )
    }

    private fun checkCpuSpeedAndTime() {
        val cpuProfile = powerProfile.cpuProfile
        repeat(cpuProfile.coreCount) { coreIndex ->
            readCpuCoreTime(coreIndex)
        }
    }

    private fun checkProcessCpuSpeedAndTime() {
        readCurrentProcessCpuCoreTime()
    }

    private fun checkCpuCoreIdleTime() {
        repeat(powerProfile.cpuProfile.coreCount) { coreIndex ->
            readCpuCoreIdleTime(coreIndex)
        }
    }

    private fun checkCpuCoreSpeed() {
        repeat(powerProfile.cpuProfile.coreCount) { coreIndex ->
            readCpuCoreSpeed(coreIndex)
        }
    }

    companion object {

        private const val TAG = "CpuStateSnapshotCapture"

        data class SingleCoreStateSnapshot(
            val coreIndex: Int,
            val currentCoreSpeedInKHz: Long,
            val cpuSpeedToTime: List<Pair<Long, Long>>,
            val cpuIdleTime: Long
        )

        data class CpuStateSnapshot(
            val createTime: Long,
            val coreStates: List<SingleCoreStateSnapshot>,
            val currentProcessCpuSpeedToTime: Map<Int, List<Pair<Long, Long>>>
        )

        data class SingleCpuCoreUsage(
            val coreIndex: Int,
            val coreSpec: CpuSpec,
            val currentCoreSpeedInKHz: Long,
            val cpuUsage: Double,
            val allCpuTimeInJiffies: Long,
            val cpuIdleTimeInJiffies: Long,
            val cpuWorkTimeInJiffies: Long,
        )

        data class ProgressSingleCpuCoreUsage(
            val refCore: SingleCpuCoreUsage,
            val cpuWorkTimeInJiffies: Long,
            val cpuUsage: Double
        )

        data class CpuUsage(
            val durationInMillis: Long,
            val cpuCoresUsage: List<SingleCpuCoreUsage>,
            val avgCpuUsage: Double,
            val currentProcessCpuCoresUsage: List<ProgressSingleCpuCoreUsage>,
            val currentProcessAvgCpuUsage: Double
        )

        val oneJiffyInMillis: Long by lazy {
            (1000.0 / Os.sysconf(OsConstants._SC_CLK_TCK).toDouble()).toLong()
        }

        /**
         * https://docs.kernel.org/cpu-freq/cpufreq-stats.html
         */
        // first: Cpu speed, second: time in jiffies
        private fun readCpuCoreTime(cpuCoreIndex: Int): List<Pair<Long, Long>> {
            val f = File("/sys/devices/system/cpu/cpu${cpuCoreIndex}/cpufreq/stats/time_in_state")
            val result = ArrayList<Pair<Long, Long>>()
            val lines = f.inputStream().bufferedReader(Charsets.UTF_8).use {
                it.readLines()
            }
            for (l in lines) {
                result.add(
                    l.split(" ").let {
                        it[0].toLong() to it[1].toLong()
                    }
                )
            }
            return result
        }

        private fun readCurrentProcessCpuCoreTime(): Map<Int, List<Pair<Long, Long>>> = readProcessCpuCoreTime(Process.myPid())

        private fun readProcessCpuCoreTime(pid: Int): Map<Int, List<Pair<Long, Long>>> {
            val f = File("/proc/$pid/time_in_state")
            val lines = f.inputStream().bufferedReader(Charsets.UTF_8).use {
                it.readLines()
            }
            val reCpuTime = "cpu([0-9]*)".toRegex()
            val result = mutableMapOf<Int, List<Pair<Long, Long>>>()
            var parsingCpu: ArrayList<Pair<Long, Long>>? = null
            var parsingCpuIndex: Int? = null
            for (l in lines) {
                if (reCpuTime.matches(l)) {
                    if (parsingCpu != null) {
                        result[parsingCpuIndex!!] = parsingCpu
                    }
                    parsingCpuIndex = reCpuTime.find(l)!!.groupValues[1].toInt()
                    parsingCpu = ArrayList()
                    continue
                }
                l.split(" ").let {
                    parsingCpu!!.add(it[0].toLong() to it[1].toLong())
                }
            }
            if (parsingCpu != null) {
                result[parsingCpuIndex!!] = parsingCpu
            }
            return result
        }

        /**
         * https://android.googlesource.com/kernel/common/+/a7827a2a60218b25f222b54f77ed38f57aebe08b/Documentation/cpuidle/sysfs.txt
         */
        // read cpu idle time in microseconds.
        private fun readCpuCoreIdleTimeInMicroseconds(cpuCoreIndex: Int): Long {
            val baseDir = File("/sys/devices/system/cpu/cpu$cpuCoreIndex/cpuidle")
            var result = 0L
            var isFoundIdleFile = false
            val childrenFiles = baseDir.listFiles() ?: emptyArray()
            for (c in childrenFiles) {
                if (c.name.startsWith("state")) {
                    val timeFile = File(c, "time")
                    result += timeFile.readText(Charsets.UTF_8).trim().toLong()
                    isFoundIdleFile = true
                }
            }
            if (!isFoundIdleFile) {
                error("Didn't found cpu idle file.")
            }
            return result
        }

        // read cpu idle time in jiffies.
        private fun readCpuCoreIdleTime(cpuCoreIndex: Int): Long {
            return readCpuCoreIdleTimeInMicroseconds(cpuCoreIndex) / (1000L * oneJiffyInMillis)
        }

        data class CpuSpec(
            val cpuCoreIndex: Int,
            val minSpeedInKHz: Long,
            val maxSpeedInKHz: Long,
            val availableSpeedsInKHz: List<Long>
        )

        /**
         * https://www.kernel.org/doc/Documentation/cpu-freq/user-guide.txt
         */
        private fun readCpuCoreSpeed(cpuCoreIndex: Int): Long {
            val baseDir = File("/sys/devices/system/cpu/cpu$cpuCoreIndex/cpufreq")
            val currentSpeed = File(baseDir, "scaling_cur_freq").readText(Charsets.UTF_8).trim().toLong()
            return currentSpeed
        }

        private fun readCpuCoresSpec(cpuCoreCount: Int): List<CpuSpec> {
            val result = ArrayList<CpuSpec>()
            repeat(cpuCoreCount) { cpuCoreIndex ->
                val baseDir = File("/sys/devices/system/cpu/cpu$cpuCoreIndex/cpufreq")
                val minSpeed = File(baseDir, "cpuinfo_min_freq").readText(Charsets.UTF_8).trim().toLong()
                val maxSpeed = File(baseDir, "cpuinfo_max_freq").readText(Charsets.UTF_8).trim().toLong()
                val availableSpeeds = File(baseDir, "scaling_available_frequencies")
                    .readText(Charsets.UTF_8)
                    .split(" ")
                    .filter { it.isNotBlank() }
                    .map { it.trim().toLong() }
                result.add(
                    CpuSpec(
                        cpuCoreIndex = cpuCoreIndex,
                        minSpeedInKHz = minSpeed,
                        maxSpeedInKHz = maxSpeed,
                        availableSpeedsInKHz = availableSpeeds
                    )
                )
            }
            return result
        }
    }
}