package com.zhesi.busplugin.linemarker

import com.intellij.codeInsight.daemon.RelatedItemLineMarkerInfo
import com.intellij.codeInsight.daemon.RelatedItemLineMarkerProvider
import com.intellij.codeInsight.navigation.NavigationGutterIconBuilder
import com.intellij.ide.highlighter.JavaFileType
import com.intellij.ide.util.DefaultPsiElementCellRenderer
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.project.Project
import com.intellij.psi.*
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.PsiTreeUtil
import com.zhesi.busplugin.common.*
import com.zhesi.busplugin.config.Configs
import org.jetbrains.kotlin.idea.KotlinFileType
import org.jetbrains.kotlin.idea.base.utils.fqname.getKotlinFqName
import org.jetbrains.kotlin.psi.KtCallElement
import javax.swing.Icon

/**
 * **EventBusLineMarkerProvider**
 *
 * 组件间交互逻辑可读性：生成某个事件的发布订阅依赖关系图
 * 1. 根据订阅查看对应事件发布者
 * 2. 根据发布者查看所有订阅
 *
 * @author lq
 * @version 1.0
 */
class EventBusLineMarkerProvider : RelatedItemLineMarkerProvider() {
    override fun collectNavigationMarkers(
        element: PsiElement, result: MutableCollection<in RelatedItemLineMarkerInfo<*>>
    ) {
        collectObserversJava(element)?.let { result.add(it) }
        collectObserversKt(element)?.let { result.add(it) }

        collectPostJava(element)?.let { result.add(it) }
        collectPostKt(element)?.let { result.add(it) }
    }

    /**
     * 标记所有 java 发布者
     */
    private fun collectPostJava(element: PsiElement): RelatedItemLineMarkerInfo<PsiElement>? {
        if (element is PsiMethodCallExpression) {
            element.containingFile?.virtualFile ?: return null
            val project = element.project

            if (element.isBusPostFun()) {
                val callObj = element.getCallObj() ?: return null
                val postEventType = element.getPostEventType() ?: return null
                val targets = getObserveTargets(project, callObj, postEventType)

                return createNavigationGutterIcon(element, targets, Icons.POST, Icons.OBSERVE,
                    "Navigate to ${postEventType.name} observer")
            }
        }
        return null
    }

    /**
     * 标记所有 kotlin 发布者
     */
    private fun collectPostKt(element: PsiElement): RelatedItemLineMarkerInfo<PsiElement>? {
        if (element is KtCallElement) {
            element.containingFile?.virtualFile ?: return null
            val project = element.project

            val postEventType = if (isBusPostFun(project, element.getCallPsiMethod())) {
                element.getPostEventType() ?: return null
            } else return null
            val callObj = element.getCallObj() ?: return null
            val targets = getObserveTargets(project, callObj, postEventType)

            return createNavigationGutterIcon(element, targets, Icons.POST, Icons.OBSERVE,
                "Navigate to ${postEventType.name} observer")
        }
        return null
    }

    /**
     * 标记所有 kotlin 订阅者
     */
    private fun collectObserversKt(element: PsiElement): RelatedItemLineMarkerInfo<PsiElement>? {
        if (element is KtCallElement) {
            element.containingFile?.virtualFile ?: return null
            val project = element.project

            val observeEventType = if (isBusObserveFun(project, element.getCallPsiMethod())) {
                element.getObserveEventType() ?: return null
            } else if (element.getCallFunFqName() == Configs.EXT_OBSERVER_FUN_FQ_NAME) {
                element.getExtObserveEventType() ?: return null
            } else return null
            val callObj = element.getCallObj() ?: return null
            val targets = getPostTargets(project, callObj, observeEventType)

            return createNavigationGutterIcon(element, targets, Icons.OBSERVE, Icons.POST,
                "Navigate to ${observeEventType.getKotlinFqName()} poster")
        }
        return null
    }

    /**
     * 标记所有 java 订阅者
     */
    private fun collectObserversJava(element: PsiElement): RelatedItemLineMarkerInfo<PsiElement>? {
        if (element is PsiMethodCallExpression) {
            element.containingFile?.virtualFile ?: return null
            val project = element.project

            // 找目标接口下的observe方法，获得来自的对象和订阅的事件，找到对象和发送事件(匹配所有子类)匹配的post调用
            if (element.isBusObserveFun()) {
                val callObj = element.getCallObj() ?: return null
                val observeEventType = element.getObserveEventType() ?: return null
                val targets = getPostTargets(project, callObj, observeEventType)

                return createNavigationGutterIcon(element, targets, Icons.OBSERVE, Icons.POST,
                    "Navigate to ${observeEventType.name} poster")
            }
        }
        return null
    }

    private fun getPostTargets(
        project: Project,
        callObj: PsiField,
        observeEventType: PsiElement
    ): ArrayList<PsiElement> {
        val targets = ArrayList<PsiElement>()
        addJavaFunTargets(project, callObj, observeEventType, targets, { isBusPostFun() }) { getPostEventType() }
        addKtFunTargets(project, callObj, observeEventType, targets, { pro, call ->
            isBusPostFun(pro, call.getCallPsiMethod())
        }) { getPostEventType() }
        return targets
    }

    private fun getObserveTargets(
        project: Project,
        callObj: PsiField,
        postEventType: PsiElement
    ): ArrayList<PsiElement> {
        val targets = ArrayList<PsiElement>()
        addJavaFunTargets(project, callObj, postEventType, targets, { isBusObserveFun() }) { getObserveEventType() }
        addKtFunTargets(project, callObj, postEventType, targets, { pro, call ->
            isBusObserveFun(pro, call.getCallPsiMethod())
        }) { getObserveEventType() }
        addKtFunTargets(project, callObj, postEventType, targets, { _, call ->
            call.getCallFunFqName() == Configs.EXT_OBSERVER_FUN_FQ_NAME
        }) { getExtObserveEventType() }
        return targets
    }

    private fun addKtFunTargets(
        project: Project,
        callObj: PsiField,
        observeEventType: PsiElement,
        targets: ArrayList<PsiElement>,
        tarFunFilter: (Project, KtCallElement) -> Boolean,
        eventTypeGetter: KtCallElement.() -> PsiElement?,
    ) {
        FilenameIndex.getAllFilesByExt(project, KotlinFileType.EXTENSION, GlobalSearchScope.projectScope(project))
            .mapNotNull { vf -> PsiManager.getInstance(project).findFile(vf) }
            .forEach { psiFile ->
                val tarObjPostCalls = PsiTreeUtil.findChildrenOfAnyType(psiFile, KtCallElement::class.java)
                    .filter { call -> tarFunFilter(project, call) }
                    .filter { call -> isFieldEqual(call.getCallObj(), callObj)}
                    .filter { call -> isEventTypeEqual(call.eventTypeGetter(), observeEventType) }
                    .map { call -> call.navigationElement }
                    .toList()
                targets.addAll(tarObjPostCalls)
            }
    }

    private fun addJavaFunTargets(
        project: Project,
        callObj: PsiField,
        observeEventType: PsiElement,
        targets: ArrayList<PsiElement>,
        tarFunFilter: PsiCall.() -> Boolean,
        eventTypeGetter: PsiMethodCallExpression.() -> PsiClass?,
    ) {
        FilenameIndex.getAllFilesByExt(project, JavaFileType.DEFAULT_EXTENSION, GlobalSearchScope.projectScope(project))
            .mapNotNull { vf -> PsiManager.getInstance(project).findFile(vf) }
            .forEach { psiFile ->
                val tarObjPostCalls = PsiTreeUtil.findChildrenOfAnyType(psiFile, PsiMethodCallExpression::class.java)
                    .filter { call -> call.tarFunFilter() }
                    .filter { call -> isFieldEqual(call.getCallObj(), callObj)}
                    .filter { call -> isEventTypeEqual(call.eventTypeGetter(), observeEventType) }
                    .map { call -> call.navigationElement }
                    .toList()
                targets.addAll(tarObjPostCalls)
            }
    }

    /**
     * 构建导航样式
     */
    private fun createNavigationGutterIcon(
        navigationPoint: PsiElement,
        targets: List<PsiElement>,
        lineIcon: Icon,
        targetIcon: Icon,
        tipTest: String,
    ): RelatedItemLineMarkerInfo<PsiElement> {
        val builder: NavigationGutterIconBuilder<PsiElement> =
            NavigationGutterIconBuilder.create(lineIcon)
                .setTargets(targets)
                .setTooltipText(tipTest)
                .setAlignment(GutterIconRenderer.Alignment.RIGHT)
                .setCellRenderer { TargetCellRenderer(targetIcon) }
        return builder.createLineMarkerInfo(navigationPoint)
    }

    /**
     * 单元格渲染
     */
    private class TargetCellRenderer(val targetIcon: Icon) : DefaultPsiElementCellRenderer() {
        override fun getIcon(element: PsiElement): Icon {
            return targetIcon
        }
    }
}
