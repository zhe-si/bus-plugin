package com.zhesi.busplugin.common

import com.intellij.ide.highlighter.JavaFileType
import com.intellij.openapi.application.smartReadAction
import com.intellij.openapi.project.Project
import com.intellij.psi.*
import com.intellij.psi.impl.source.PsiClassReferenceType
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.PsiSuperMethodUtil
import com.intellij.psi.util.PsiTreeUtil
import com.zhesi.busplugin.config.Configs
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.jetbrains.kotlin.idea.KotlinFileType
import org.jetbrains.kotlin.idea.base.utils.fqname.fqName
import org.jetbrains.kotlin.idea.base.utils.fqname.getKotlinFqName
import org.jetbrains.kotlin.idea.caches.resolve.analyze
import org.jetbrains.kotlin.nj2k.postProcessing.resolve
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.psiUtil.findFunctionByName
import org.jetbrains.kotlin.resolve.calls.util.getType
import org.jetbrains.kotlin.resolve.lazy.BodyResolveMode
import org.jetbrains.kotlin.types.isError

/**
 * **EventBusUtils**
 *
 * @author lq
 * @version 1.0
 */

fun getIBusClass(project: Project) = findKtClass(project, Configs.I_EVENT_BUS_FQ_NAME)

fun getIBusFun(project: Project, funName: String) = getIBusClass(project)?.findFunctionByName(funName) as? KtFunction


////////////////// 判断是否为发送或订阅方法 //////////////////////

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


////////////////// 判断指向 psi 相等 //////////////////////

fun isEventTypeEqual(type1: PsiElement?, type2: PsiElement?): Boolean {
    if (type1 == null || type2 == null) return false
    return type1.getKotlinFqName()?.let { it == type2.getKotlinFqName() } == true
}

fun isFieldEqual(f1: PsiField?, f2: PsiField?): Boolean {
    if (f1 == null || f2 == null) return false
    return f1 == f2 || f1.getKotlinFqName()?.let { it == f2.getKotlinFqName() } == true
}


////////////////// 获得 event 类型 //////////////////////

fun KtCallElement.getPostEventType(): KtClassOrObject? {
    return valueArguments.firstOrNull()?.getArgumentExpression()
        ?.let { it.analyze(BodyResolveMode.PARTIAL).getType(it) }?.fqName?.asString()
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

// 返回 KtClassOrObject 或 PsiClass
fun KtCallElement.getExtObserveEventType(): PsiElement? =
    (typeArguments.firstOrNull()?.typeReference?.typeElement as? KtUserType)?.referenceExpression?.resolve()

fun PsiMethodCallExpression.getObserveEventType(): PsiClass? =
    ((argumentList.expressions.firstOrNull()?.type as? PsiClassType)?.typeArguments()
        ?.firstOrNull() as? PsiClassReferenceType)?.resolve()


////////////////// 获得所有 event //////////////////////

/**
 * Psi 对象，通过 "对象 fqName -> Set<T>" 表示一个对象，对象 fqName 作为该对象的唯一标识
 *
 * 单个 psi 对象存在多种 psi 描述方式，如 java 和 kotlin 不同的描述，且不好相互转化与判断相等
 */
data class PsiTarget<T: PsiElement>(val fqName: String) {
    val psiDesSet = mutableSetOf<T>()
}

typealias BusTarget = PsiTarget<PsiField>
typealias EventTarget = PsiTarget<PsiElement>

/**
 * 通过发布事件和订阅事件调用，检索所有事件总线对象和对应的注册为事件的类型
 *
 * @return 总线对象fqName -> 总线对象Psi集合(存在多种psi的表达方式); 总线对象fqName -> 该总线上的事件定义集合
 * 保证了一个文件内注册为事件的事件顺序
 */
suspend fun getAllEventType(
    project: Project,
): Map<BusTarget, Set<EventTarget>> {
    val busEventMap = LinkedHashMap<BusTarget, LinkedHashSet<EventTarget>>()
    val processBar = JBStatusBarProgressBar(project, "search bus events").also { it.start() }

    coroutineScope {
        val ktFiles = getAllFiles(project, KotlinFileType.EXTENSION)
        processBar.addPercent(0.07)
        val javaFiles = getAllFiles(project, JavaFileType.DEFAULT_EXTENSION)
        processBar.addPercent(0.07)
        processBar.allTaskNum = ktFiles.size + javaFiles.size

        ktFiles.forEach { psiFile ->
            launch {
                smartReadAction(project) {
                    for (call in PsiTreeUtil.findChildrenOfAnyType(psiFile, KtCallElement::class.java)) {
                        val event = if (isBusPostFun(project, call.getCallPsiMethod())) {
                            call.getPostEventType()
                        } else if (isBusObserveFun(project, call.getCallPsiMethod())) {
                            call.getObserveEventType()
                        } else if (call.getCallFunFqName() == Configs.EXT_OBSERVER_FUN_FQ_NAME) {
                            call.getExtObserveEventType()
                        } else null

                        addEventTypeMap(call.getCallObj(), event, busEventMap)
                        processBar.addStep()
                    }
                }
            }
        }
        javaFiles.forEach { psiFile ->
            launch {
                smartReadAction(project) {
                    for (call in PsiTreeUtil.findChildrenOfAnyType(psiFile, PsiMethodCallExpression::class.java)) {
                        val event = if (call.isBusPostFun()) {
                            call.getPostEventType()
                        } else if (call.isBusObserveFun()) {
                            call.getObserveEventType()
                        } else null

                        addEventTypeMap(call.getCallObj(), event, busEventMap)
                        processBar.addStep()
                    }
                }
            }
        }
    }
    processBar.finish()

    return busEventMap
}

private suspend fun getAllFiles(project: Project, fileType: String) =
    smartReadAction(project) { FilenameIndex.getAllFilesByExt(project, fileType, GlobalSearchScope.projectScope(project)) }
        .mapNotNull { vf -> smartReadAction(project) { PsiManager.getInstance(project).findFile(vf) } }

@Synchronized
private fun addEventTypeMap(
    busPsi: PsiField?,
    eventPsi: PsiElement?,
    busEventMap: MutableMap<BusTarget, LinkedHashSet<EventTarget>>,
    successfulCallback: () -> Unit = {},
): Boolean {
    val objFqName = busPsi?.getKotlinFqName()?.asString() ?: return false
    val eventFqName = eventPsi?.getKotlinFqName()?.asString() ?: return false

    val busTarget = (busEventMap.keys.find { it.fqName == objFqName } ?: BusTarget(objFqName)).apply {
        psiDesSet.add(busPsi)
    }
    val eventSet = busEventMap.getOrPut(busTarget) { linkedSetOf() }
    val eventTarget = eventSet.find { it.fqName == eventFqName } ?: EventTarget(eventFqName).apply { eventSet.add(this) }
    eventTarget.psiDesSet.add(eventPsi)

    successfulCallback()
    return true
}


// PsiClass, KtClassOrObject
fun groupEventBySuper(eventSet: Set<PsiElement>) {
    for (e in eventSet) {
        if (e is PsiClass) {
            println(e)
        } else if (e is KtClassOrObject) {
            println(e)
        } else continue
    }
}
