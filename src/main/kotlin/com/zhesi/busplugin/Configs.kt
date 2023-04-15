package com.zhesi.busplugin

/**
 * **Configs**
 *
 * @author lq
 * @version 1.0
 */
object Configs {
    const val I_EVENT_BUS_FQ_NAME = "com.nwpu.ucdp.util.IEventBus"
    const val EVENT_BUS_OBSERVE_FUN_NAME = "observe"
    const val EVENT_BUS_POST_FUN_NAME = "post"

    // 要求拓展方法接收者为IEventBus，类型参数第一个为事件
    const val EXT_OBSERVER_FUN_FQ_NAME = "com.nwpu.ucdp.util.observe"
}
