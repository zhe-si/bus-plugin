package com.zhesi.busplugin

import com.intellij.codeInsight.daemon.RelatedItemLineMarkerInfo
import com.intellij.codeInsight.daemon.RelatedItemLineMarkerProvider
import com.intellij.codeInsight.navigation.NavigationGutterIconBuilder
import com.intellij.ide.util.DefaultPsiElementCellRenderer
import com.intellij.json.JsonFileType
import com.intellij.json.psi.JsonProperty
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.search.FileTypeIndex
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.PsiTreeUtil
import com.zhesi.busplugin.common.Icons
import javax.swing.Icon
import kotlin.streams.toList

/**
 * **BusLineMarkerProvider**
 *
 * @author lq
 * @version 1.0
 */
class BusLineMarkerProvider : RelatedItemLineMarkerProvider() {
    override fun collectNavigationMarkers(
        element: PsiElement, result: MutableCollection<in RelatedItemLineMarkerInfo<*>>
    ) {
        if (element !is PsiClass) {
            return
        }
        val psiClass: PsiClass = element
        if (psiClass.containingFile.virtualFile == null) {
            return
        }
        val targets: ArrayList<PsiElement> = ArrayList()

        // 1. 查找与 class 类名同名的 xml 文件, 并追加到结果集合
        val xmlFile = FilenameIndex.getFilesByName(
            psiClass.project, psiClass.name + ".xml", GlobalSearchScope.projectScope(psiClass.project)
        ).toList()
        targets.addAll(xmlFile)

        // 2. Json 属性 key 为 panda, 值为类名则添加到标记结果
        FileTypeIndex.getFiles(JsonFileType.INSTANCE, GlobalSearchScope.projectScope(psiClass.project))
            .mapNotNull { vf -> PsiManager.getInstance(psiClass.project).findFile(vf) }.forEach { jsonFile ->
                run {
                    val jsonProperties =
                        PsiTreeUtil.findChildrenOfAnyType(jsonFile, JsonProperty::class.java).stream().filter { jp ->
                            jp.name == "panda" && jp.value != null && jp.value!!.text.replace("\"", "") == psiClass.name
                        }.toList()
                    targets.addAll(jsonProperties)
                }
            }

        // 构建导航样式
        val builder: NavigationGutterIconBuilder<PsiElement> =
            NavigationGutterIconBuilder.create(Icons.PANDA)
                .setTargets(targets)
                .setTooltipText("Navigate to Panda resource")
                .setAlignment(GutterIconRenderer.Alignment.RIGHT)
                .setCellRenderer(MyListCellRenderer())

        if (null != psiClass.nameIdentifier) {
            result.add(builder.createLineMarkerInfo(psiClass.nameIdentifier!!))
        }
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
