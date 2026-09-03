package com.sdwvit.s2cfg

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileDocumentManagerListener
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.LightVirtualFile
import com.intellij.ui.EditorNotificationPanel
import com.intellij.ui.components.panels.Wrapper
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.beans.PropertyChangeListener
import java.beans.PropertyChangeSupport
import java.util.Collections
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Opens a Mod SDK `*-localization.uasset` package as the JSON document its `LocalizedTexts`
 * export describes, and writes the edited JSON back into the binary package on save.
 *
 * The package is recognised by its header rather than by its name, so an asset the SDK named
 * something else still opens, and a `.uasset` that is not a localization package is left to
 * whatever else can show it.
 */
class S2LocalizationEditorProvider : FileEditorProvider, DumbAware {

  override fun getEditorTypeId() = "s2cfg-localization-json"

  /** Ours is the only sensible view of these packages, so the binary viewer stays out of the way. */
  override fun getPolicy(): FileEditorPolicy = FileEditorPolicy.HIDE_DEFAULT_EDITOR

  override fun acceptRequiresReadAction() = false

  override fun accept(project: Project, file: VirtualFile): Boolean {
    if (file.isDirectory || !file.isValid) return false
    if (!file.name.endsWith(".uasset", ignoreCase = true)) return false
    if (file.length <= 0 || file.length > MAX_PACKAGE_BYTES) return false
    // accept() is called for every open and every tab-layout decision, so the verdict is cached
    // against the file's modification stamp — the header is only read when the file changes.
    val cached = file.getUserData(ACCEPTED)
    if (cached != null && cached.first == file.modificationStamp) return cached.second
    val verdict = S2CfgLog.timed(what = { "localization header check of ${file.name}" }) {
      runCatching { S2UassetFormat.isLocalizationPackage(file.contentsToByteArray()) }
        .getOrDefault(false)
    }
    file.putUserData(ACCEPTED, file.modificationStamp to verdict)
    return verdict
  }

  override fun createEditor(project: Project, file: VirtualFile): FileEditor =
    S2LocalizationEditor(project, file)

  private companion object {
    /** These assets hold dialogue lines; anything this big is not one, and reading it would stall. */
    const val MAX_PACKAGE_BYTES = 64L * 1024 * 1024
    val ACCEPTED = Key.create<Pair<Long, Boolean>>("s2cfg.localization.accepted")
  }
}

/**
 * The editor itself: a normal JSON editor over an in-memory document, plus the save path.
 *
 * Because the document is not the binary file's own, the platform cannot save it for us. Instead
 * [S2LocalizationSaveListener] flushes every open editor whenever the IDE saves — Ctrl+S, frame
 * deactivation, before a build — and a save is refused outright while the JSON does not parse or
 * does not describe a localization document, with the reason shown above the editor.
 */
class S2LocalizationEditor(private val project: Project, private val file: VirtualFile) :
  UserDataHolderBase(), FileEditor {

  private val changes = PropertyChangeSupport(this)
  private val validation = Wrapper()
  private val panel = JPanel(BorderLayout())
  /**
   * Coalesces validation while the user types. A Swing timer rather than the platform's `Alarm`:
   * this only ever has to fire on the EDT, and `Alarm` wants a coroutine scope we do not have.
   */
  private val revalidate = javax.swing.Timer(VALIDATION_DELAY_MS) { validate() }
    .apply { isRepeats = false }

  private val lightFile: LightVirtualFile
  /** Internal rather than private so the tests can drive the editor the way a user would. */
  internal val document: Document
  private val editor: EditorEx

  /**
   * The package's name table, as it was when the editor opened. The writer cannot add to it, so
   * validation needs it — and re-reading the package on every keystroke to get it would not do.
   */
  private val names: List<String>

  /** The text last known to match the package on disk; what [isModified] compares against. */
  private var savedText: String

  /** Why the current text cannot be saved, or `null` when it can. */
  private var error: String? = null

  init {
    val asset = S2UassetFormat.parse(file.contentsToByteArray())
    names = asset.names
    savedText = S2Localization.toJson(asset)
    // A `.json` name is all it takes for the platform's JSON support — highlighting, folding,
    // structure view, its own syntax errors — to apply to the in-memory document.
    lightFile = LightVirtualFile(
      "${file.nameWithoutExtension}.json",
      FileTypeManager.getInstance().getFileTypeByFileName("a.json"),
      savedText,
    )
    document = FileDocumentManager.getInstance().getDocument(lightFile)
      ?: throw IllegalStateException("no document for ${lightFile.name}")
    editor = EditorFactory.getInstance().createEditor(document, project, lightFile, false) as EditorEx
    editor.settings.isLineNumbersShown = true

    document.addDocumentListener(object : DocumentListener {
      override fun documentChanged(event: DocumentEvent) {
        changes.firePropertyChange(FileEditor.getPropModified(), null, isModified)
        // Validating on every keystroke is wasted work on a document with thousands of entries;
        // a short delay keeps the banner responsive without parsing mid-word.
        revalidate.restart()
      }
    }, this)

    panel.add(toolbar(), BorderLayout.NORTH)
    panel.add(editor.component, BorderLayout.CENTER)
    panel.add(validation, BorderLayout.SOUTH)
    OPEN.add(this)
    validate()
  }

  private fun toolbar(): JComponent {
    val group = DefaultActionGroup(
      action("Save to .uasset", "Write the JSON back into the binary package", AllIcons.Actions.MenuSaveall) {
        save(interactive = true)
      },
      action("Copy JSON", "Copy the whole document to the clipboard", AllIcons.Actions.Copy) {
        java.awt.Toolkit.getDefaultToolkit().systemClipboard
          .setContents(StringSelection(document.text), null)
      },
      action("Paste JSON", "Replace the whole document with the clipboard's JSON", AllIcons.Actions.MenuPaste) {
        pasteJson()
      },
      action("Reload", "Discard the edits and re-read the package", AllIcons.Actions.Refresh) {
        reload()
      },
    )
    val toolbar = com.intellij.openapi.actionSystem.ActionManager.getInstance()
      .createActionToolbar("S2CfgLocalization", group, true)
    toolbar.targetComponent = editor.component
    return toolbar.component.also { it.border = JBUI.Borders.emptyLeft(4) }
  }

  private fun action(text: String, description: String, icon: javax.swing.Icon, body: () -> Unit) =
    object : AnAction(text, description, icon), DumbAware {
      override fun getActionUpdateThread() = ActionUpdateThread.EDT
      override fun actionPerformed(e: AnActionEvent) = body()
    }

  /**
   * Replaces the document with the clipboard's contents, but only if they are a valid
   * localization document — a bad paste would otherwise silently block every later save.
   */
  private fun pasteJson() {
    val text = runCatching {
      java.awt.Toolkit.getDefaultToolkit().systemClipboard
        .getData(DataFlavor.stringFlavor) as? String
    }.getOrNull()
    if (text.isNullOrBlank()) {
      Messages.showErrorDialog(project, "The clipboard holds no text.", "Paste JSON")
      return
    }
    val entries = try {
      S2Localization.parse(text, names)
    } catch (e: Exception) {
      Messages.showErrorDialog(
        project,
        "The clipboard is not a localization document:\n${e.message}",
        "Paste JSON",
      )
      return
    }
    WriteCommandAction.writeCommandAction(project)
      .withName("Paste Localization JSON")
      .run<RuntimeException> { document.setText(S2Localization.toJson(entries)) }
  }

  private fun reload() {
    if (isModified && Messages.showYesNoDialog(
        project,
        "Discard the edits made here and re-read ${file.name}?",
        "Reload Localization Asset",
        Messages.getQuestionIcon(),
      ) != Messages.YES
    ) return
    val text = S2Localization.toJson(S2UassetFormat.parse(file.contentsToByteArray()))
    WriteCommandAction.writeCommandAction(project)
      .withName("Reload Localization Asset")
      .run<RuntimeException> { document.setText(text) }
    savedText = text
    validate()
  }

  /** Re-checks the text and shows or hides the banner that explains why a save is blocked. */
  private fun validate() {
    error = describeProblem(document.text)
    validation.removeAll()
    error?.let { message ->
      validation.setContent(
        EditorNotificationPanel(EditorNotificationPanel.Status.Error).text("Not saved — $message")
      )
    }
    validation.revalidate()
    validation.repaint()
  }

  private fun describeProblem(text: String): String? = try {
    S2Localization.parse(text, names)
    null
  } catch (e: S2JsonException) {
    val at = if (e.offset >= 0) " at line ${lineOf(text, e.offset)}" else ""
    "invalid JSON$at: ${e.message}"
  } catch (e: S2LocalizationException) {
    e.message
  } catch (e: S2UassetException) {
    e.message
  }

  private fun lineOf(text: String, offset: Int) =
    1 + text.take(offset.coerceAtMost(text.length)).count { it == '\n' }

  /**
   * Writes the document back into the package. Returns false without touching the file when the
   * text does not validate; [interactive] saves say so in a dialog, an IDE-wide save just leaves
   * the banner up, because a modal dialog on autosave would be worse than the unsaved change.
   */
  fun save(interactive: Boolean): Boolean {
    if (!isModified) return true
    val text = document.text
    val problem = describeProblem(text)
    if (problem != null) {
      validate()
      S2CfgLog.LOG.info("not saving ${file.name}: $problem")
      if (interactive) {
        Messages.showErrorDialog(project, "This document is not saved:\n$problem", "Save Localization Asset")
      }
      return false
    }
    return try {
      val bytes = S2Localization.apply(file.contentsToByteArray(), text)
      ApplicationManager.getApplication().runWriteAction { file.setBinaryContent(bytes) }
      savedText = text
      changes.firePropertyChange(FileEditor.getPropModified(), null, false)
      true
    } catch (e: Exception) {
      S2CfgLog.LOG.warn("could not write ${file.name}", e)
      if (interactive) {
        Messages.showErrorDialog(
          project,
          "Could not write ${file.name}:\n${e.message}",
          "Save Localization Asset",
        )
      }
      false
    }
  }

  override fun getComponent(): JComponent = panel
  override fun getPreferredFocusedComponent(): JComponent = editor.contentComponent
  override fun getName() = "Localization"
  override fun getFile() = file
  override fun setState(state: FileEditorState) {}
  override fun isModified() = document.text != savedText
  override fun isValid() = file.isValid && !editor.isDisposed
  override fun addPropertyChangeListener(listener: PropertyChangeListener) =
    changes.addPropertyChangeListener(listener)

  override fun removePropertyChangeListener(listener: PropertyChangeListener) =
    changes.removePropertyChangeListener(listener)

  override fun dispose() {
    revalidate.stop()
    OPEN.remove(this)
    // Closing a tab is the IDE's usual save point, and it saves before disposing editors, so an
    // unsaved change here means the text did not validate. Say so rather than lose it silently.
    if (isModified) S2CfgLog.LOG.warn("closing ${file.name} with unsaved localization changes")
    EditorFactory.getInstance().releaseEditor(editor)
  }

  companion object {
    private const val VALIDATION_DELAY_MS = 300

    /** Every editor currently open, so an IDE-wide save can flush them. */
    private val OPEN: MutableSet<S2LocalizationEditor> =
      Collections.synchronizedSet(LinkedHashSet())

    fun saveAll() {
      for (editor in synchronized(OPEN) { OPEN.toList() }) {
        runCatching { editor.save(interactive = false) }
          .onFailure { S2CfgLog.LOG.warn("localization save failed", it) }
      }
    }
  }
}

/**
 * The document shown in a localization editor is an in-memory one, so the platform's save never
 * reaches the `.uasset` behind it. This hooks every IDE-wide save — Ctrl+S, frame deactivation,
 * before a run or a VCS operation — and writes the open editors through.
 */
class S2LocalizationSaveListener : FileDocumentManagerListener {
  override fun beforeAllDocumentsSaving() = S2LocalizationEditor.saveAll()
}
