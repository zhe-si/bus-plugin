package com.zhesi.busplugin.common

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.WindowManager
import java.math.RoundingMode
import java.text.DecimalFormat
import kotlin.math.min

/**
 * **JBUtils**
 *
 * @author lq
 * @version 1.0
 */

/**
 * JB 状态栏进度条
 */
class JBStatusBarProgressBar(
    project: Project,
    private val doingName: String,
) {

    private val statusBar: StatusBar = WindowManager.getInstance().getStatusBar(project)

    var allTaskNum = 100
    private var nowPercent = .0

    private val percentFormatter = DecimalFormat("##.##").apply { roundingMode = RoundingMode.FLOOR }

    fun start() {
        statusBar.startRefreshIndication(doingName)
    }

    fun addStep(num: Int = 1) {
        addNowPercent(num.toDouble() / allTaskNum * (1 - nowPercent))
        updateProgress()
    }

    fun addPercent(p: Double) {
        addNowPercent(p)
        updateProgress()
    }

    fun finish() {
        nowPercent = 1.0
        statusBar.stopRefreshIndication()
        statusBar.info = "Finish $doingName"
    }

    fun updateProgress() {
        statusBar.info = "$doingName: ${getPercentMsg(nowPercent)}%"
    }

    private fun addNowPercent(p: Double) {
        nowPercent += p
        nowPercent = min(nowPercent, 0.99)
    }

    private fun getPercentMsg(value: Double): String = percentFormatter.format(100.0 * value)

}
