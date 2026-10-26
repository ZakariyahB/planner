package app.planner.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.planner.data.ThemeRepository
import app.planner.theme.ThemeMode
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class AppearanceViewModel @Inject constructor(private val theme: ThemeRepository) : ViewModel() {
    val mode: StateFlow<ThemeMode> = theme.mode.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ThemeMode.SYSTEM)

    fun choose(mode: ThemeMode) {
        viewModelScope.launch { theme.save(mode) }
    }
}
