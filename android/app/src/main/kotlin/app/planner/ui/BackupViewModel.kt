package app.planner.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.planner.backup.Backup
import app.planner.backup.BackupFile
import app.planner.backup.BackupParse
import app.planner.backup.BackupRepository
import app.planner.backup.RestoreResult
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Clock
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Where the Backup page is: nothing going on, busy with a file, or waiting for the user to confirm a restore. */
sealed interface BackupStep {
    data object Idle : BackupStep

    data class Working(val what: String) : BackupStep

    data class Confirm(val file: BackupFile) : BackupStep
}

/** The outcome of the last action, in plain words. */
data class BackupMessage(val text: String, val good: Boolean)

@HiltViewModel
class BackupViewModel @Inject constructor(
    private val backup: BackupRepository,
    private val clock: Clock,
) : ViewModel() {
    private val _step = MutableStateFlow<BackupStep>(BackupStep.Idle)
    val step: StateFlow<BackupStep> = _step

    private val _message = MutableStateFlow<BackupMessage?>(null)
    val message: StateFlow<BackupMessage?> = _message

    /** Off unless asked for: the conversation with the assistant is the most personal thing the app holds. */
    private val _includeChat = MutableStateFlow(false)
    val includeChat: StateFlow<Boolean> = _includeChat

    /** The name offered in the save dialog, for example planner-backup-2026-10-26.json. */
    fun suggestedName(): String = Backup.fileName(LocalDate.now(clock))

    fun setIncludeChat(on: Boolean) {
        _includeChat.value = on
    }

    /** [uri] is where the user chose to save, or null if they backed out of the picker. */
    fun save(uri: Uri?) {
        if (uri == null) return
        viewModelScope.launch {
            _message.value = null
            _step.value = BackupStep.Working("Saving your backup…")
            try {
                val exported = backup.export(_includeChat.value)
                backup.write(uri, exported.json)
                _message.value = BackupMessage("Saved: ${exported.summary}.", good = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _message.value = BackupMessage("The backup couldn't be saved. Choose another place, such as Downloads, and try again.", good = false)
            } finally {
                _step.value = BackupStep.Idle
            }
        }
    }

    /** [uri] is the file the user picked, or null if they backed out. Nothing changes until they confirm. */
    fun choose(uri: Uri?) {
        if (uri == null) return
        viewModelScope.launch {
            _message.value = null
            _step.value = BackupStep.Working("Reading the file…")
            try {
                when (val parsed = backup.inspect(backup.read(uri))) {
                    is BackupParse.Ok -> _step.value = BackupStep.Confirm(parsed.file)
                    is BackupParse.Rejected -> {
                        _message.value = BackupMessage(parsed.reason, good = false)
                        _step.value = BackupStep.Idle
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _message.value = BackupMessage("That file couldn't be read. Choose it again, or pick a different copy.", good = false)
                _step.value = BackupStep.Idle
            }
        }
    }

    fun restore() {
        val file = (_step.value as? BackupStep.Confirm)?.file ?: return
        viewModelScope.launch {
            _step.value = BackupStep.Working("Restoring…")
            _message.value = when (val result = backup.restore(file)) {
                is RestoreResult.Restored -> BackupMessage(
                    if (result.replanned) "Restored: ${file.describe()}." else "Restored: ${file.describe()}. The plan couldn't be rebuilt just now; tap Replan on Today.",
                    good = true,
                )
                is RestoreResult.Failed -> BackupMessage(result.reason, good = false)
            }
            _step.value = BackupStep.Idle
        }
    }

    fun cancel() {
        _step.value = BackupStep.Idle
    }
}
