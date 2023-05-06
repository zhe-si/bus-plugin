package com.zhesi.busplugin.toolwindow

import com.intellij.codeInsight.navigation.NavigationUtil
import com.intellij.icons.AllIcons
import com.intellij.ide.util.treeView.NodeDescriptor
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.psi.PsiElement
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.treeStructure.Tree
import com.zhesi.busplugin.common.BusTarget
import com.zhesi.busplugin.common.EventTarget
import com.zhesi.busplugin.common.Icons
import com.zhesi.busplugin.common.getAllEventType
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.awt.BorderLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.Icon
import javax.swing.JSeparator
import javax.swing.tree.DefaultMutableTreeNode
import kotlin.properties.Delegates


/**
 * **EventBusToolWindowFactory**
 *
 * 组件间交互逻辑可读性：生成某个事件的发布订阅依赖关系图
 * 1. 从窗口查看所有事件
 *
 * 0.0.2
 * TODO: 分层级查看事件，基于某种规则聚类事件
 * TODO: 添加设置（各种配置，事件展示聚类方式设定）
 * TODO: 事件命名规范检查（提示在toolwindow或代码上）
 *
 * 0.0.3
 * TODO: 调试的运行时事件流转记录查看支持
 *
 * 0.0.4
 * TODO: 优化性能，全局监听修改，维护目标 psi 缓存，只在一开始并行搜索一次
 *
 * @author lq
 * @version 1.0
 */
class EventBusToolWindowFactory : ToolWindowFactory {

    private val contentFactory = ContentFactory.SERVICE.getInstance()

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val eventBusToolWindow = EventBusToolWindow(project)
        val content = contentFactory.createContent(eventBusToolWindow.getContent(), null, false)
        toolWindow.contentManager.addContent(content)
    }

    override fun shouldBeAvailable(project: Project) = true

    class EventBusToolWindow(private val project: Project) {
        private val runScope = CoroutineScope(CoroutineName("EventBusToolWindowRunScope") + Dispatchers.Default)

        private val rootNode = createTreeNode(EventBusTreeItemData("总线事件", Icons.BUS))
        private val eventTree = Tree(rootNode)

        private var busEventMap: Map<BusTarget, Set<EventTarget>>? = null

        /**
         * 是否使用事件名正序对事件进行排序
         */
        private var isUseEventNamePositiveSeq by Delegates.observable(false) { _, _, _ ->
            updateEventsShow()
        }

        /**
         * 创建一个新的工具栏组件
         */
        fun getContent(): JBPanel<JBPanel<*>> {
            return JBPanel<JBPanel<*>>(BorderLayout(7, 7)).apply {
                // 展示内容面板
                eventTree.addMouseListener(object : MouseAdapter() {
                    override fun mouseClicked(e: MouseEvent?) {
                        if (e?.clickCount == 2) {
                            nativeSelectedElement(eventTree)
                        }
                    }
                })
                val scrollPane = JBScrollPane(eventTree).apply { border = null }
                add(scrollPane, BorderLayout.CENTER)

                // 工具栏
                val actionGroup = DefaultActionGroup(ID_ACTION_GROUP, false).apply {
                    add(object : AnAction("更新事件列表", "更新事件列表", AllIcons.Actions.Refresh) {
                        override fun actionPerformed(e: AnActionEvent) {
                            updateEvents()
                        }
                    })
                    add(object : ToggleAction("事件名正序", "是否按照事件名正序排列", AllIcons.General.Filter) {
                        override fun isSelected(e: AnActionEvent): Boolean = isUseEventNamePositiveSeq
                        override fun setSelected(e: AnActionEvent, state: Boolean) {
                            isUseEventNamePositiveSeq = state
                        }
                    })
                }
                val toolbarActions = ActionManager.getInstance().createActionToolbar(ID_ACTION_TOOLBAR, actionGroup, true).apply {
                    targetComponent = scrollPane
                }
                val toolbar = JBPanel<JBPanel<*>>(VerticalLayout(0)).apply {
                    add(toolbarActions.component)
                    add(JSeparator())
                }
                add(toolbar, BorderLayout.NORTH)
            }
        }

        /**
         * 设置展示的内容，可在非主线程运行
         */
        fun setTreeData(data: Any?) {
            rootNode.removeAllChildren()
            _setTreeData(rootNode, data)
            eventTree.updateUI()
        }

        private fun _setTreeData(root: DefaultMutableTreeNode, data: Any?) {
            when (data) {
                null -> return
                is Map<*, *> -> {
                    for ((key, value) in data) {
                        _setTreeData(root, key)
                        (root.lastChild as? DefaultMutableTreeNode)?.let {
                            _setTreeData(it, value)
                        }
                    }
                }
                is Pair<*, *> -> {
                    _setTreeData(root, data.first)
                    (root.lastChild as? DefaultMutableTreeNode)?.let {
                        _setTreeData(it, data.second)
                    }
                }
                is Iterable<*> -> {
                    for (i in data) {
                        _setTreeData(root, i)
                    }
                }
                else -> root.add(createTreeNode(data))
            }
        }

        /**
         * 更新事件信息并设置到展示内容
         */
        private fun updateEvents() {
            runScope.launch(Dispatchers.IO) {
                busEventMap = getAllEventType(project)
                updateEventsShow()
            }
        }

        /**
         * 根据当前状态更新事件展示内容
         */
        private fun updateEventsShow() {
            busEventMap?.let {
                setTreeData(getEventSortNameShowData(it.toList()))
            }
        }

        /**
         * 根据事件的当前序展示事件
         */
        private fun getEventStdShowData(busEventMap: Collection<Pair<BusTarget, Collection<EventTarget>>>): List<Pair<EventBusTreeItemData, List<EventBusTreeItemData>>> {
            val result = mutableListOf<Pair<EventBusTreeItemData, List<EventBusTreeItemData>>>()
            for ((bus, eventList) in busEventMap) {
                // 默认使用目标的第一个psi描述
                bus.psiDesSet.firstOrNull()?.let { busPsi ->
                    result.add(
                        EventBusTreeItemData(busPsi, Icons.BUS_OBJ, bus.fqName) to eventList.mapNotNull { et ->
                            et.psiDesSet.firstOrNull()?.let { EventBusTreeItemData(it, Icons.EVENT, et.fqName) }
                        }
                    )
                }
            }
            return result
        }

        /**
         * 根据事件的名字序展示事件
         *
         * 排序规则：根据包名 + 事件类型名顺序 / 逆序
         */
        private fun getEventSortNameShowData(busEventMap: Collection<Pair<BusTarget, Collection<EventTarget>>>): List<Pair<EventBusTreeItemData, List<EventBusTreeItemData>>> {
            return getEventStdShowData(busEventMap.toList()
                .sortedBy { it.first.fqName }
                .map { pair ->
                    pair.first to pair.second.sortedBy { et ->
                        et.fqName.split(".").let {
                            it.slice(0 until it.size - 1).joinToString(".") + "." +
                                    if (isUseEventNamePositiveSeq) it.last()
                                    else it.last().reversed()
                        }
                    }
                }
            )
        }

        /**
         * 导航到内容面板当前选中项的位置
         */
        private fun nativeSelectedElement(eventTree: Tree) {
            val node = eventTree.lastSelectedPathComponent as? DefaultMutableTreeNode ?: return
            val psiElement = ((node.userObject as? EventBusNodeDescriptor)?.element as? EventBusTreeItemData)?.data as? PsiElement ?: return
            NavigationUtil.activateFileWithPsiElement(psiElement.navigationElement, true)
        }

        private fun createTreeNode(data: Any) = DefaultMutableTreeNode(EventBusNodeDescriptor(data))

        /**
         * 节点包装器，用于设置节点图标等信息
         */
        private inner class EventBusNodeDescriptor(private val data: Any) : NodeDescriptor<Any>(project, null) {
            init {
                if (data is EventBusTreeItemData) data.icon?.let { icon = it }
            }
            override fun update(): Boolean = false
            override fun getElement(): Any = data
            override fun toString(): String = data.toString()
        }

        data class EventBusTreeItemData(val data: Any, val icon: Icon? = null, val name: String? = null) {
            override fun toString(): String = name ?: data.toString()
        }

        companion object {
            const val ID_ACTION_GROUP = "EventBusActionGroup"
            const val ID_ACTION_TOOLBAR = "EventBusActionToolBar"
        }
    }

}
