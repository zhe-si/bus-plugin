package com.zhesi.busplugin.common

import com.intellij.openapi.project.Project
import com.intellij.psi.*
import com.intellij.psi.impl.source.tree.CompositeElement
import com.intellij.psi.search.GlobalSearchScope
import org.jetbrains.kotlin.asJava.getRepresentativeLightMethod
import org.jetbrains.kotlin.asJava.toLightClass
import org.jetbrains.kotlin.idea.caches.resolve.analyze
import org.jetbrains.kotlin.idea.stubindex.KotlinFullClassNameIndex
import org.jetbrains.kotlin.js.resolve.diagnostics.findPsi
import org.jetbrains.kotlin.nj2k.postProcessing.resolve
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.psiUtil.getReceiverExpression
import org.jetbrains.kotlin.resolve.bindingContextUtil.getReferenceTargets

/**
 * **PsiUtils**
 *
 * @author lq
 * @version 1.0
 */

fun KtFunction?.asPsiMethod() = this?.getRepresentativeLightMethod()

fun findKtClass(project: Project, fqName: String): KtClassOrObject? {
    val searchResult = KotlinFullClassNameIndex.getInstance().get(fqName, project, GlobalSearchScope.allScope(project))
    return searchResult.singleOrNull()
}

fun KtCallElement.getCallFun() = (calleeExpression as? KtReferenceExpression)?.resolve() as? KtNamedFunction

fun KtCallElement.getCallFunFqName() = getCallFun()?.fqName?.asString()

fun KtCallElement.getCallPsiMethod() = getCallFun()?.asPsiMethod()

fun PsiMethodCallExpression.getCallObj() = (methodExpression.qualifierExpression as? PsiReferenceExpression)?.resolve() as? PsiField

fun KtCallElement.getCallObj(): PsiField? {
    val rRef = (calleeExpression as? KtSimpleNameExpression)?.getReceiverExpression() as? KtSimpleNameExpression ?: return null
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


fun KtClassOrObject.getSuperClassList() =
    superTypeListEntries
        .mapNotNull { it.typeAsUserType?.referenceExpression?.resolve() }
        .filter { it is PsiClass || it is KtClassOrObject }

fun PsiElement.getSuperClassList() =
    when (this) {
        is KtClassOrObject -> getSuperClassList()
        is PsiClass -> supers.filterNotNull()
        else -> null
    }


/**
 * 获得调用对象的标识符，如果找不到则返回自身
 */
fun PsiElement.getCallIdentifier(): PsiElement =
    when (this) {
        is PsiMethodCallExpression -> (methodExpression as? CompositeElement)?.findPsiChildByType(JavaTokenType.IDENTIFIER) ?: this
        is KtCallElement -> (calleeExpression as? KtSimpleNameExpression)?.getIdentifier() ?: this
        else -> this
    }
