package com.zhesi.busplugin.common

import com.intellij.ide.highlighter.JavaFileType
import com.intellij.openapi.project.Project
import com.intellij.psi.*
import com.intellij.psi.impl.source.PsiClassReferenceType
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.PsiSuperMethodUtil
import com.intellij.psi.util.PsiTreeUtil
import com.zhesi.busplugin.config.Configs
import org.jetbrains.kotlin.idea.KotlinFileType
import org.jetbrains.kotlin.idea.base.utils.fqname.fqName
import org.jetbrains.kotlin.idea.base.utils.fqname.getKotlinFqName
import org.jetbrains.kotlin.idea.caches.resolve.analyze
import org.jetbrains.kotlin.idea.core.resolveType
import org.jetbrains.kotlin.nj2k.postProcessing.resolve
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.psiUtil.findFunctionByName
import org.jetbrains.kotlin.resolve.calls.util.getType
import org.jetbrains.kotlin.types.isError

/**
 * **EventBusUtils**
 *
 * @author lq
 * @version 1.0
 */

fun getIBusClass(project: Project) = findKtClass(project, Configs.I_EVENT_BUS_FQ_NAME)

fun getIBusFun(project: Project, funName: String) = getIBusClass(project)?.findFunctionByName(funName) as? KtFunction


fun isBusPostFun(project: Project, tarMethod: PsiMethod?): Boolean {
    if (tarMethod == null) return false
    val iEventBusPostFun = getIBusFun(project, Configs.EVENT_BUS_POST_FUN_NAME) ?: return false
    return iEventBusPostFun.asPsiMethod()?.let { tarMethod == it || PsiSuperMethodUtil.isSuperMethod(tarMethod, it) } == true
}

fun isBusObserveFun(project: Project, tarMethod: PsiMethod?): Boolean {
    if (tarMethod == null) return false
    val iEventBusObserveFun = getIBusFun(project, Configs.EVENT_BUS_OBSERVE_FUN_NAME) ?: return false
    return iEventBusObserveFun.asPsiMethod()?.let { tarMethod == it || PsiSuperMethodUtil.isSuperMethod(tarMethod, it) } == true
}

fun PsiCall.isBusPostFun() = resolveMethod()?.let { isBusPostFun(project, it) } ?: false

fun PsiCall.isBusObserveFun() = resolveMethod()?.let { isBusObserveFun(project, it) } ?: false


fun isEventTypeEqual(type1: PsiElement?, type2: PsiElement?): Boolean {
    if (type1 == null || type2 == null) return false
    return type1.getKotlinFqName()?.let { it == type2.getKotlinFqName() } == true
}

fun isFieldEqual(f1: PsiField?, f2: PsiField?): Boolean {
    if (f1 == null || f2 == null) return false
    return f1 == f2 || f1.getKotlinFqName()?.let { it == f2.getKotlinFqName() } == true
}


fun KtCallElement.getPostEventType(): KtClassOrObject? {
    return valueArguments.firstOrNull()?.getArgumentExpression()?.resolveType()?.fqName?.asString()
        ?.let { findKtClass(project, it) }
}

fun PsiMethodCallExpression.getPostEventType(): PsiClass? =
    (argumentList.expressions.firstOrNull()?.type as? PsiClassType)?.resolve()


/**
 * TODO: kotlin psi 无法完全解析 xxx.javaClass、xxx::class.java 的类型，只能通过字符串直接判断，当存在间接关系则无法处理
 */
fun KtCallElement.getObserveEventType(): KtClassOrObject? {
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

fun KtCallElement.getExtObserveEventType(): KtClassOrObject? =
    (typeArguments.firstOrNull()?.typeReference?.typeElement as? KtUserType)?.referenceExpression?.resolve() as? KtClassOrObject

fun PsiMethodCallExpression.getObserveEventType(): PsiClass? =
    ((argumentList.expressions.firstOrNull()?.type as? PsiClassType)?.typeArguments()
        ?.firstOrNull() as? PsiClassReferenceType)?.resolve()

fun getAllEventType(project: Project): LinkedHashMap<String, MutableList<PsiElement>> {
    val objEventMap = LinkedHashMap<String, MutableList<PsiElement>>()
    val objMap = HashMap<String, MutableList<PsiField>>()
    FilenameIndex.getAllFilesByExt(project, KotlinFileType.EXTENSION, GlobalSearchScope.projectScope(project))
        .mapNotNull { vf -> PsiManager.getInstance(project).findFile(vf) }
        .forEach { psiFile ->
            for (call in PsiTreeUtil.findChildrenOfAnyType(psiFile, KtCallElement::class.java)) {
                val obj = call.getCallObj()
                val objFqName = obj?.getKotlinFqName()?.asString() ?: continue
                objMap.getOrPut(objFqName) { mutableListOf() }.let {
                    if (!it.contains(obj)) it.add(obj)
                }

                val event = if (isBusPostFun(project, call.getCallPsiMethod())) {
                    call.getPostEventType()
                } else if (isBusObserveFun(project, call.getCallPsiMethod())) {
                    call.getObserveEventType()
                } else if (call.getCallFunFqName() == Configs.EXT_OBSERVER_FUN_FQ_NAME) {
                    call.getExtObserveEventType()
                } else null
                event?.let { e ->
                    objEventMap.getOrPut(objFqName) { mutableListOf() }.let {
                        if (!it.contains(e)) it.add(e)
                    }
                } ?: continue
            }
        }
    FilenameIndex.getAllFilesByExt(project, JavaFileType.DEFAULT_EXTENSION, GlobalSearchScope.projectScope(project))
        .mapNotNull { vf -> PsiManager.getInstance(project).findFile(vf) }
        .forEach { psiFile ->
            for (call in PsiTreeUtil.findChildrenOfAnyType(psiFile, PsiMethodCallExpression::class.java)) {
                val obj = call.getCallObj()
                val objFqName = obj?.getKotlinFqName()?.asString() ?: continue
                objMap.getOrPut(objFqName) { mutableListOf() }.let {
                    if (!it.contains(obj)) it.add(obj)
                }

                val event = if (call.isBusPostFun()) {
                    call.getPostEventType()
                } else if (call.isBusObserveFun()) {
                    call.getObserveEventType()
                } else null
                event?.let { e ->
                    objEventMap.getOrPut(objFqName) { mutableListOf() }.let {
                        if (!it.contains(e)) it.add(e)
                    }
                } ?: continue
            }
        }
    return objEventMap
}
