package com.zhesi.busplugin

import com.intellij.codeInsight.daemon.RelatedItemLineMarkerInfo
import com.intellij.codeInsight.daemon.RelatedItemLineMarkerProvider
import com.intellij.codeInsight.navigation.NavigationGutterIconBuilder
import com.intellij.ide.highlighter.JavaFileType
import com.intellij.ide.util.DefaultPsiElementCellRenderer
import com.intellij.json.JsonFileType
import com.intellij.json.psi.JsonProperty
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.project.Project
import com.intellij.psi.*
import com.intellij.psi.impl.source.PsiClassReferenceType
import com.intellij.psi.search.FileTypeIndex
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.PsiSuperMethodUtil
import com.intellij.psi.util.PsiTreeUtil
import com.zhesi.busplugin.common.Icons
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
import kotlin.streams.toList

/**
 * **BusLineMarkerProvider**
 *
 * 组件间交互逻辑可读性：生成某个事件的发布订阅依赖关系图
 * 1. 根据订阅查看对应事件发布者
 * 2. 根据发布者查看所有订阅
 * 3. 从observe查看所有事件
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
    }

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

            addKtPostTargets(project, callObj, observeEventType, targets)
            addJavaPostTargets(project, callObj, observeEventType, targets)
            return createNavigationGutterIcon(element, targets)
        }
        return null
    }

    private fun KtCallElement.getExtObserveEventType(): KtClassOrObject? =
        (typeArguments.firstOrNull()?.typeReference?.typeElement as? KtUserType)?.referenceExpression?.resolve() as? KtClassOrObject

    private fun addKtPostTargets(
        project: Project,
        callObj: PsiField,
        observeEventType: PsiElement,
        targets: ArrayList<PsiElement>
    ) {
        FilenameIndex.getAllFilesByExt(project, KotlinFileType.EXTENSION, GlobalSearchScope.projectScope(project))
            .mapNotNull { vf -> PsiManager.getInstance(project).findFile(vf) }
            .forEach { psiFile ->
                val tarObjPostCalls = PsiTreeUtil.findChildrenOfAnyType(psiFile, KtCallElement::class.java)
                    .filter { call -> isBusPostFun(project, call.getCallPsiMethod()) }
                    .filter { call -> call.getCallObj() == callObj }
                    .filter { call -> call.getPostEventType()?.getKotlinFqName()?.let { it == observeEventType.getKotlinFqName() } ?: false }
                    .toList()
                targets.addAll(tarObjPostCalls)
            }
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
            // TODO: 总线变量还未处理
            is KtObjectDeclaration -> rPsi.toLightClass()?.findFieldByName("INSTANCE", false) ?: return null
            else -> return null
        }
        return rField
    }

    private fun collectObserversJava(element: PsiElement): RelatedItemLineMarkerInfo<PsiElement>? {
        if (element is PsiMethodCallExpression) {
            element.containingFile?.virtualFile ?: return null
            val project = element.project

            // 找目标接口下的observe方法，获得来自的对象和订阅的事件，找到对象和发送事件(匹配所有子类)匹配的post调用
            if (element.isBusObserveFun()) {
                val callObj = element.getCallObj() ?: return null
                val observeEventType = element.getObserveEventType() ?: return null
                val targets = ArrayList<PsiElement>()
                addJavaPostTargets(project, callObj, observeEventType, targets)
                addKtPostTargets(project, callObj, observeEventType, targets)
                return createNavigationGutterIcon(element, targets)
            }
        }
        return null
    }

    private fun addJavaPostTargets(
        project: Project,
        callObj: PsiField,
        observeEventType: PsiElement,
        targets: ArrayList<PsiElement>
    ) {
        FilenameIndex.getAllFilesByExt(project, JavaFileType.DEFAULT_EXTENSION, GlobalSearchScope.projectScope(project))
            .mapNotNull { vf -> PsiManager.getInstance(project).findFile(vf) }
            .forEach { psiFile ->
                val tarObjPostCalls = PsiTreeUtil.findChildrenOfAnyType(psiFile, PsiMethodCallExpression::class.java)
                    .filter { call -> call.isBusPostFun() }
                    .filter { call -> call.getCallObj() == callObj }
                    .filter { call -> call.getPostEventType()?.getKotlinFqName()?.let { it == observeEventType.getKotlinFqName() } ?: false }
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

    private fun collectPandas(element: PsiElement): RelatedItemLineMarkerInfo<PsiElement>? {
        val psiClass: PsiClass = element as? PsiClass ?: return null
        psiClass.containingFile?.virtualFile ?: return null

        val targets = ArrayList<PsiElement>()

        // 1. 查找与 class 类名同名的 xml 文件, 并追加到结果集合
        val xmlFile = FilenameIndex.getFilesByName(
            psiClass.project,
            psiClass.name + ".xml",
            GlobalSearchScope.projectScope(psiClass.project)
        ).toList()
        targets.addAll(xmlFile)

        // 2. Json 属性 key 为 panda, 值为类名则添加到标记结果
        FileTypeIndex.getFiles(JsonFileType.INSTANCE, GlobalSearchScope.projectScope(psiClass.project))
            .mapNotNull { vf -> PsiManager.getInstance(psiClass.project).findFile(vf) }
            .forEach { jsonFile ->
                val jsonProperties = PsiTreeUtil.findChildrenOfAnyType(jsonFile, JsonProperty::class.java).stream()
                    .filter { jp ->
                        jp.name == "panda"
                                && jp.value != null
                                && jp.value!!.text.replace("\"", "") == psiClass.name
                    }
                    .toList()
                targets.addAll(jsonProperties)
            }

        return psiClass.identifyingElement?.let { createNavigationGutterIcon(it, targets) }
    }

    /**
     * 构建导航样式
     */
    private fun createNavigationGutterIcon(
        navigationPoint: PsiElement,
        targets: List<PsiElement>,
    ): RelatedItemLineMarkerInfo<PsiElement> {
        val builder: NavigationGutterIconBuilder<PsiElement> =
            NavigationGutterIconBuilder.create(Icons.PANDA)
                .setTargets(targets)
                .setTooltipText("Navigate to Panda resource")
                .setAlignment(GutterIconRenderer.Alignment.RIGHT)
                .setCellRenderer { MyListCellRenderer() }
        return builder.createLineMarkerInfo(navigationPoint)
    }

    /**
     * 单元格渲染
     */
    private class MyListCellRenderer : DefaultPsiElementCellRenderer() {
        override fun getElementText(element: PsiElement): String {
            val prefix = StringBuilder()
            if (element is JsonProperty) {
                prefix.append("Found Panda! ")
            }
            return prefix.append(super.getElementText(element)).toString()
        }

        override fun getIcon(element: PsiElement): Icon {
            return Icons.PANDA
        }
    }
}
