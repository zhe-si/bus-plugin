package com.zhesi.busplugin.common

import com.intellij.ide.highlighter.JavaFileType
import com.intellij.openapi.application.smartReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.WindowManager
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
import org.jetbrains.kotlin.idea.core.resolveType
import org.jetbrains.kotlin.nj2k.postProcessing.resolve
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.psiUtil.findFunctionByName
import org.jetbrains.kotlin.resolve.calls.util.getType
import org.jetbrains.kotlin.types.isError
import java.math.RoundingMode
import java.text.DecimalFormat

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

suspend fun getAllEventType(
    project: Project,
): Pair<HashMap<String, MutableList<PsiField>>, LinkedHashMap<String, MutableList<PsiElement>>> {
    val objMap = HashMap<String, MutableList<PsiField>>()
    val objEventMap = LinkedHashMap<String, MutableList<PsiElement>>()

    val statusBar = WindowManager.getInstance().getStatusBar(project)
    statusBar.startRefreshIndication("Searching all events.")

    coroutineScope {
        val ktFiles = getAllFiles(project, KotlinFileType.EXTENSION)
        statusBar.info = getSearchingMsg(0.05)
        val javaFiles = getAllFiles(project, JavaFileType.DEFAULT_EXTENSION)
        statusBar.info = getSearchingMsg(0.10)

        val filesNum = ((ktFiles.size + javaFiles.size) / 0.90).toInt()
        var nowFinishNum = filesNum / 10

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

                        if (!addEventTypeMap(call.getCallObj(), event, objMap, objEventMap) {
                            nowFinishNum++
                            statusBar.info = getSearchingMsg(nowFinishNum, filesNum)
                        }) continue
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

                        if (!addEventTypeMap(call.getCallObj(), event, objMap, objEventMap) {
                            nowFinishNum++
                            statusBar.info = getSearchingMsg(nowFinishNum, filesNum)
                        }) continue
                    }
                }
            }
        }
    }

    statusBar.stopRefreshIndication()
    statusBar.info = "Finish search events."

    return objMap to objEventMap
}

private fun getSearchingMsg(nowNum: Int, allNum: Int): String = "Searching events: ${getPercentMsg(nowNum, allNum)}%"
private fun getSearchingMsg(value: Double): String = "Searching events: ${getPercentMsg(value)}%"

private val percentFormatter = DecimalFormat("##.##").apply { roundingMode = RoundingMode.FLOOR }
private fun getPercentMsg(value: Double): String =
    percentFormatter.format(100.0 * value)
private fun getPercentMsg(nowNum: Int, allNum: Int): String = getPercentMsg(nowNum.toDouble() / allNum.toDouble())

private suspend fun getAllFiles(project: Project, fileType: String) =
    smartReadAction(project) { FilenameIndex.getAllFilesByExt(project, fileType, GlobalSearchScope.projectScope(project)) }
        .mapNotNull { vf -> smartReadAction(project) { PsiManager.getInstance(project).findFile(vf) } }

@Synchronized
private fun addEventTypeMap(
    obj: PsiField?,
    event: PsiElement?,
    objMap: HashMap<String, MutableList<PsiField>>,
    objEventMap: LinkedHashMap<String, MutableList<PsiElement>>,
    successfulCallback: () -> Unit,
): Boolean {
    obj ?: return false
    event ?: return false
    val objFqName = obj.getKotlinFqName()?.asString() ?: return false

    objMap.getOrPut(objFqName) { mutableListOf() }.let {
        if (!it.contains(obj)) it.add(obj)
    }
    objEventMap.getOrPut(objFqName) { mutableListOf() }.let {
        if (!it.contains(event)) it.add(event)
    }

    successfulCallback()
    return true
}
