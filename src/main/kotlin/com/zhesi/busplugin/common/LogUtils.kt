package com.zhesi.busplugin.common

import com.intellij.openapi.diagnostic.logger

/**
 * **LogUtils**
 *
 * @author lq
 * @version 1.0
 */

inline fun <reified T : Any> T.log() = logger<T>()
