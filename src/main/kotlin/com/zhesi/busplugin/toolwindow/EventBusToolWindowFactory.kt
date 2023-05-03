package com.zhesi.busplugin.toolwindow

import com.intellij.codeInsight.navigation.NavigationUtil
import com.intellij.icons.AllIcons
import com.intellij.ide.util.treeView.NodeDescriptor
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
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
import org.jetbrains.kotlin.idea.base.utils.fqname.getKotlinFqName
import java.awt.BorderLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.Icon
import javax.swing.JSeparator
import javax.swing.tree.DefaultMutableTreeNode


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
        private val rootNode = createTreeNode(EventBusTreeItemData("总线事件", Icons.BUS))
        private val runScope = CoroutineScope(CoroutineName("EventBusToolWindowRunScope") + Dispatchers.Default)

        private var busEventMap: Map<BusTarget, Set<EventTarget>>? = null

        /**
         * 创建一个新的工具栏组件
         */
        fun getContent(): JBPanel<JBPanel<*>> {
            return JBPanel<JBPanel<*>>(BorderLayout(7, 7)).apply {
                // 展示内容面板
                val eventTree = Tree(rootNode)
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
                    add(object : AnAction(AllIcons.Actions.Refresh) {
                        override fun actionPerformed(e: AnActionEvent) {
                            updateEvents()
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
                val data = getAllEventType(project)
                busEventMap = data
                setTreeData(getEventSortNameShowData(data.toList()))
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
                        EventBusTreeItemData(busPsi, Icons.BUS_OBJ) to eventList.mapNotNull { et ->
                            et.psiDesSet.firstOrNull()?.let { EventBusTreeItemData(it, Icons.EVENT) }
                        }
                    )
                }
            }
            return result
        }

        /**
         * 根据事件的名字序展示事件
         *
         * 排序规则：根据包名 + 事件类型名从后向前
         */
        private fun getEventSortNameShowData(busEventMap: Collection<Pair<BusTarget, Collection<EventTarget>>>): List<Pair<EventBusTreeItemData, List<EventBusTreeItemData>>> {
            return getEventStdShowData(busEventMap.toList()
                .sortedBy { it.first.fqName }
                .map { pair ->
                    pair.first to pair.second.sortedBy { et ->
                        et.fqName.split(".").let {
                            it.slice(0 until it.size - 1).joinToString(".") + it.last().reversed()
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
            NavigationUtil.activateFileWithPsiElement(psiElement, true)
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

        data class EventBusTreeItemData(val data: Any, val icon: Icon? = null) {
            override fun toString(): String =
                if (data is PsiElement) data.getKotlinFqName()?.asString() ?: data.text
                else data.toString()
        }

        companion object {
            const val ID_ACTION_GROUP = "EventBusActionGroup"
            const val ID_ACTION_TOOLBAR = "EventBusActionToolBar"
        }
    }

}
