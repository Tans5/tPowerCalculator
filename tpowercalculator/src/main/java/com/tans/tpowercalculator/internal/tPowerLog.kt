package com.tans.tpowercalculator.internal

import android.util.Log

@Suppress("ClassName")
internal object tPowerLog {

    fun d(tag: String, msg: String) {
        Log.d(tag, msg)
    }

    fun w(tag: String, msg: String, throwable: Throwable? = null) {
        if (throwable == null) {
            Log.w(tag, msg)
        } else {
            Log.w(tag, msg, throwable)
        }
    }

    fun e(tag: String, msg: String, throwable: Throwable? = null) {
        if (throwable == null) {
            Log.e(tag, msg)
        } else {
            Log.e(tag, msg, throwable)
        }
    }
}