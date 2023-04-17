package com.zhesi.busplugin.toolwindow

import com.intellij.icons.AllIcons
import com.intellij.ide.util.treeView.NodeDescriptor
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.treeStructure.Tree
import com.zhesi.busplugin.common.Icons
import com.zhesi.busplugin.common.getAllEventType
import org.jetbrains.kotlin.idea.base.utils.fqname.getKotlinFqName
import java.awt.BorderLayout
import javax.swing.Icon
import javax.swing.JSeparator
import javax.swing.tree.DefaultMutableTreeNode


/**
 * **EventBusToolWindowFactory**
 *
 * 组件间交互逻辑可读性：生成某个事件的发布订阅依赖关系图
 * 1. 从窗口查看所有事件
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

        fun getContent(): JBPanel<JBPanel<*>> {
            return JBPanel<JBPanel<*>>(BorderLayout(7, 7)).apply {
                val eventTree = Tree(rootNode)
                val scrollPane = JBScrollPane(eventTree).apply { border = null }
                add(scrollPane, BorderLayout.CENTER)

                val actionGroup = DefaultActionGroup(ID_ACTION_GROUP, false).apply {
                    add(object : AnAction(AllIcons.Actions.Refresh) {
                        override fun actionPerformed(e: AnActionEvent) {
                            val objEventMap = getAllEventType(project)
                            val resultMap = LinkedHashMap<EventBusTreeItemData, List<EventBusTreeItemData>>()
                            for ((obj, eventList) in objEventMap.entries) {
                                resultMap[EventBusTreeItemData(obj, Icons.BUS_OBJ)] = eventList.map {
                                    EventBusTreeItemData(it.getKotlinFqName()?.asString() ?: it.text, Icons.EVENT)
                                }
                            }
                            setTreeData(resultMap)
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

        fun setTreeData(data: Any?) {
            rootNode.removeAllChildren()
            _setTreeData(rootNode, data)
        }

        private fun _setTreeData(root: DefaultMutableTreeNode, data: Any?) {
            when (data) {
                null -> return
                is Iterable<*> -> {
                    for (i in data) {
                        _setTreeData(root, i)
                    }
                }
                is Map<*, *> -> {
                    for ((key, value) in data) {
                        _setTreeData(root, key)
                        (root.lastChild as? DefaultMutableTreeNode)?.let {
                            _setTreeData(it, value)
                        }
                    }
                }
                else -> root.add(createTreeNode(data))
            }
        }

        private fun createTreeNode(data: Any) = DefaultMutableTreeNode(EventBusNodeDescriptor(data))

        private inner class EventBusNodeDescriptor(private val data: Any) : NodeDescriptor<Any>(project, null) {
            init {
                if (data is EventBusTreeItemData) data.icon?.let { icon = it }
            }
            override fun update(): Boolean = false
            override fun getElement(): Any = data
            override fun toString(): String = data.toString()
        }

        data class EventBusTreeItemData(val data: Any, val icon: Icon? = null) {
            override fun toString(): String = data.toString()
        }

        companion object {
            const val ID_ACTION_GROUP = "EventBusActionGroup"
            const val ID_ACTION_TOOLBAR = "EventBusActionToolBar"
        }
    }
}
