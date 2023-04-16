package com.zhesi.busplugin

import com.intellij.codeInsight.daemon.RelatedItemLineMarkerInfo
import com.intellij.codeInsight.daemon.RelatedItemLineMarkerProvider
import com.intellij.codeInsight.navigation.NavigationGutterIconBuilder
import com.intellij.ide.highlighter.JavaFileType
import com.intellij.ide.util.DefaultPsiElementCellRenderer
import com.intellij.json.psi.JsonProperty
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.project.Project
import com.intellij.psi.*
import com.intellij.psi.impl.source.PsiClassReferenceType
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.PsiSuperMethodUtil
import com.intellij.psi.util.PsiTreeUtil
import com.zhesi.busplugin.common.Icons
import com.zhesi.busplugin.config.Configs
import org.jetbrains.kotlin.asJava.getRepresentativeLightMethod
import org.jetbrains.kotlin.asJava.toLightClass
import org.jetbrains.kotlin.idea.KotlinFileType
import org.jetbrains.kotlin.idea.base.utils.fqname.fqName
import org.jetbrains.kotlin.idea.base.utils.fqname.getKotlinFqName
import org.jetbrains.kotlin.idea.caches.resolve.analyze
import org.jetbrains.kotlin.idea.core.resolveType
import org.jetbrains.kotlin.idea.stubindex.KotlinFullClassNameIndex
import org.jetbrains.kotlin.js.resolve.diagnostics.findPsi
import org.jetbrains.kotlin.nj2k.postProcessing.resolve
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.psiUtil.findFunctionByName
import org.jetbrains.kotlin.psi.psiUtil.getReceiverExpression
import org.jetbrains.kotlin.resolve.bindingContextUtil.getReferenceTargets
import org.jetbrains.kotlin.resolve.calls.util.getType
import org.jetbrains.kotlin.types.isError
import javax.swing.Icon

/**
 * **BusLineMarkerProvider**
 *
 * 组件间交互逻辑可读性：生成某个事件的发布订阅依赖关系图
 * 1. 根据订阅查看对应事件发布者
 * 2. 根据发布者查看所有订阅
 * 3. 从窗口查看所有事件
 *
 * @author lq
 * @version 1.0
 */
class BusLineMarkerProvider : RelatedItemLineMarkerProvider() {
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

                val targets = ArrayList<PsiElement>()
                addJavaFunTargets(project, callObj, postEventType, targets, { isBusObserveFun() }) { getObserveEventType() }
                addKtFunTargets(project, callObj, postEventType, targets, { pro, call ->
                    isBusObserveFun(pro, call.getCallPsiMethod())
                }) { getObserveEventType() }
                addKtFunTargets(project, callObj, postEventType, targets, { _, call ->
                    call.getCallFunFqName() == Configs.EXT_OBSERVER_FUN_FQ_NAME
                }) { getExtObserveEventType() }

                return createNavigationGutterIcon(element, targets, Icons.POST, Icons.OBSERVE)
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

            val targets = ArrayList<PsiElement>()
            addJavaFunTargets(project, callObj, postEventType, targets, { isBusObserveFun() }) { getObserveEventType() }
            addKtFunTargets(project, callObj, postEventType, targets, { pro, call ->
                isBusObserveFun(pro, call.getCallPsiMethod())
            }) { getObserveEventType() }
            addKtFunTargets(project, callObj, postEventType, targets, { _, call ->
                call.getCallFunFqName() == Configs.EXT_OBSERVER_FUN_FQ_NAME
            }) { getExtObserveEventType() }

            return createNavigationGutterIcon(element, targets, Icons.POST, Icons.OBSERVE)
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

            val targets = ArrayList<PsiElement>()
            addJavaFunTargets(project, callObj, observeEventType, targets, { isBusPostFun() }) { getPostEventType() }
            addKtFunTargets(project, callObj, observeEventType, targets, { pro, call ->
                isBusPostFun(pro, call.getCallPsiMethod())
            }) { getPostEventType() }

            return createNavigationGutterIcon(element, targets, Icons.OBSERVE, Icons.POST)
        }
        return null
    }

    private fun KtCallElement.getExtObserveEventType(): KtClassOrObject? =
        (typeArguments.firstOrNull()?.typeReference?.typeElement as? KtUserType)?.referenceExpression?.resolve() as? KtClassOrObject

    private fun addKtFunTargets(
        project: Project,
        callObj: PsiField,
        observeEventType: PsiElement,
        targets: ArrayList<PsiElement>,
        tarFunFilter: (Project, KtCallElement) -> Boolean,
        eventTypeGetter: KtCallElement.() -> KtClassOrObject?,
    ) {
        FilenameIndex.getAllFilesByExt(project, KotlinFileType.EXTENSION, GlobalSearchScope.projectScope(project))
            .mapNotNull { vf -> PsiManager.getInstance(project).findFile(vf) }
            .forEach { psiFile ->
                val tarObjPostCalls = PsiTreeUtil.findChildrenOfAnyType(psiFile, KtCallElement::class.java)
                    .filter { call -> tarFunFilter(project, call) }
                    .filter { call -> isFieldEqual(call.getCallObj(), callObj)}
                    .filter { call -> isEventTypeEqual(call.eventTypeGetter(), observeEventType) }
                    .toList()
                targets.addAll(tarObjPostCalls)
            }
    }

    private fun isEventTypeEqual(type1: PsiElement?, type2: PsiElement?): Boolean {
        if (type1 == null || type2 == null) return false
        return type1.getKotlinFqName()?.let { it == type2.getKotlinFqName() } == true
    }

    private fun isFieldEqual(f1: PsiField?, f2: PsiField?): Boolean {
        if (f1 == null || f2 == null) return false
        return f1 == f2 || f1.getKotlinFqName()?.let { it == f2.getKotlinFqName() } == true
    }

    private fun KtCallElement.getPostEventType(): KtClassOrObject? {
        return valueArguments.firstOrNull()?.getArgumentExpression()?.resolveType()?.fqName?.asString()
            ?.let { findKtClass(project, it) }
    }

    /**
     * TODO: kotlin psi 无法完全解析 xxx.javaClass、xxx::class.java 的类型，只能通过字符串直接判断，当存在间接关系则无法处理
     */
    private fun KtCallElement.getObserveEventType(): KtClassOrObject? {
        val rExp = valueArguments.firstOrNull()?.getArgumentExpression() ?: return null
        val rType = rExp.let { it.getType(it.analyze()) }
        if (rType?.isError == false) {
            return rType.fqName?.asString()?.let { findKtClass(project, it) }
        }
        if (rExp.text.endsWith(".javaClass")) {
            return ((rExp as? KtQualifiedExpression)?.receiverExpression as? KtReferenceExpression)?.resolve() as? KtClassOrObject
        } else if (rExp.text.endsWith("::class.java")) {
            return (((rExp as? KtQualifiedExpression)?.receiverExpression as? KtDoubleColonExpression)?.receiverExpression as? KtReferenceExpression)?.resolve() as? KtClassOrObject
        }
        return null
    }

    private fun KtCallElement.getCallFun() = (calleeExpression as? KtReferenceExpression)?.resolve() as? KtNamedFunction

    private fun KtCallElement.getCallFunFqName() = getCallFun()?.fqName?.asString()

    private fun KtCallElement.getCallPsiMethod() = getCallFun()?.asPsiMethod()

    private fun KtCallElement.getCallObj(): PsiField? {
        val rRef = (calleeExpression as KtNameReferenceExpression).getReceiverExpression() as? KtNameReferenceExpression ?: return null
        val rField: PsiField = when (val rPsi = rRef.getReferenceTargets(rRef.analyze()).singleOrNull()?.findPsi()) {
            is KtObjectDeclaration -> rPsi.toLightClass()?.findFieldByName("INSTANCE", false) ?: return null
            is KtProperty -> {
                // TODO: kotlin入口查找总线变量无法转换为 PsiField，java可以
                return null
            }
            else -> return null
        }
        return rField
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

                val targets = ArrayList<PsiElement>()
                addJavaFunTargets(project, callObj, observeEventType, targets, { isBusPostFun() }) { getPostEventType() }
                addKtFunTargets(project, callObj, observeEventType, targets, { pro, call ->
                    isBusPostFun(pro, call.getCallPsiMethod())
                }) { getPostEventType() }

                return createNavigationGutterIcon(element, targets, Icons.OBSERVE, Icons.POST)
            }
        }
        return null
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
                    .toList()
                targets.addAll(tarObjPostCalls)
            }
    }

    private fun PsiMethodCallExpression.getObserveEventType(): PsiClass? =
        ((argumentList.expressions.firstOrNull()?.type as? PsiClassType)?.typeArguments()
            ?.firstOrNull() as? PsiClassReferenceType)?.resolve()

    private fun PsiMethodCallExpression.getPostEventType(): PsiClass? =
        (argumentList.expressions.firstOrNull()?.type as? PsiClassType)?.resolve()

    private fun PsiMethodCallExpression.getCallObj() = (methodExpression.qualifierExpression as? PsiReferenceExpression)?.resolve() as? PsiField

    private fun getIBusClass(project: Project) = findKtClass(project, Configs.I_EVENT_BUS_FQ_NAME)

    private fun getIBusFun(project: Project, funName: String) = getIBusClass(project)?.findFunctionByName(funName) as? KtFunction

    private fun PsiCall.isBusPostFun() = resolveMethod()?.let { isBusPostFun(project, it) } ?: false

    private fun PsiCall.isBusObserveFun() = resolveMethod()?.let { isBusObserveFun(project, it) } ?: false

    private fun isBusPostFun(project: Project, tarMethod: PsiMethod?): Boolean {
        if (tarMethod == null) return false
        val iEventBusPostFun = getIBusFun(project, Configs.EVENT_BUS_POST_FUN_NAME) ?: return false
        return iEventBusPostFun.asPsiMethod()?.let { tarMethod == it || PsiSuperMethodUtil.isSuperMethod(tarMethod, it) } == true
    }

    private fun isBusObserveFun(project: Project, tarMethod: PsiMethod?): Boolean {
        if (tarMethod == null) return false
        val iEventBusObserveFun = getIBusFun(project, Configs.EVENT_BUS_OBSERVE_FUN_NAME) ?: return false
        return iEventBusObserveFun.asPsiMethod()?.let { tarMethod == it || PsiSuperMethodUtil.isSuperMethod(tarMethod, it) } == true
    }

    private fun KtFunction?.asPsiMethod() = this?.getRepresentativeLightMethod()

    private fun findKtClass(project: Project, fqName: String): KtClassOrObject? {
        val searchResult = KotlinFullClassNameIndex.getInstance().get(fqName, project, GlobalSearchScope.allScope(project))
        return searchResult.singleOrNull()
    }

    /**
     * 构建导航样式
     */
    private fun createNavigationGutterIcon(
        navigationPoint: PsiElement,
        targets: List<PsiElement>,
        lineIcon: Icon,
        targetIcon: Icon,
    ): RelatedItemLineMarkerInfo<PsiElement> {
        val builder: NavigationGutterIconBuilder<PsiElement> =
            NavigationGutterIconBuilder.create(lineIcon)
                .setTargets(targets)
                .setTooltipText("Navigate to Panda resource")
                .setAlignment(GutterIconRenderer.Alignment.RIGHT)
                .setCellRenderer { TargetCellRenderer(targetIcon) }
        return builder.createLineMarkerInfo(navigationPoint)
    }

    /**
     * 单元格渲染
     */
    private class TargetCellRenderer(val targetIcon: Icon) : DefaultPsiElementCellRenderer() {
        override fun getElementText(element: PsiElement): String {
            val prefix = StringBuilder()
            if (element is JsonProperty) {
                prefix.append("Found Panda! ")
            }
            return prefix.append(super.getElementText(element)).toString()
        }

        override fun getIcon(element: PsiElement): Icon {
            return targetIcon
        }
    }
}
