package com.sdwvit.s2cfg

import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFileFactory

/** Builds throwaway PSI to splice into real files — the standard way to edit a tree safely. */
object S2CfgElementFactory {

  fun createFile(project: Project, text: String): S2CfgFile =
    PsiFileFactory.getInstance(project)
      .createFileFromText("dummy.cfg", S2CfgFileType, text) as S2CfgFile

  fun createStruct(project: Project, name: String): S2CfgStruct =
    createFile(project, "$name : struct.begin\nstruct.end").structs.single()

  fun createEntry(project: Project, key: String, value: String): S2CfgEntry =
    createFile(project, "Dummy : struct.begin\n   $key = $value\nstruct.end")
      .structs.single().entries.single()

  fun createRef(project: Project, keyword: String, value: String): S2CfgRef =
    createFile(project, "Dummy : struct.begin {$keyword=$value}\nstruct.end")
      .structs.single().head!!.refs!!.refs.single()
}
