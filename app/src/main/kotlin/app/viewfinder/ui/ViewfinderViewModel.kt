package app.viewfinder.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.viewfinder.domain.CameraBody
import app.viewfinder.domain.Framelines
import app.viewfinder.domain.LensSet
import app.viewfinder.domain.PlaceholderCatalog
import app.viewfinder.domain.ProjectRepository
import app.viewfinder.domain.Rig
import app.viewfinder.domain.SensorMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ViewfinderViewModel(private val repo: ProjectRepository) : ViewModel() {
    private val _rig = MutableStateFlow(repo.load())
    val rig: StateFlow<Rig> = _rig.asStateFlow()

    /** Lets a debug launch open a panel straight away. Null in normal use. */
    val pendingPicker = MutableStateFlow<Picker?>(null)

    @OptIn(FlowPreview::class)
    private val autosave = viewModelScope.launch {
        _rig.drop(1).debounce(400).collect { r -> withContext(Dispatchers.IO) { repo.save(r) } }
    }

    /** Saves right now. Called when the app leaves the screen so nothing is lost to the debounce. */
    fun flush() = repo.save(_rig.value)

    class Factory(private val repo: ProjectRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = ViewfinderViewModel(repo) as T
    }

    // ---- camera, lens ----

    fun selectCamera(body: CameraBody, mode: SensorMode) = _rig.update { it.copy(cameraId = body.id, modeId = mode.id) }

    /** Choosing a lens hands zoom back to automatic lens matching. */
    fun selectLens(set: LensSet) = _rig.update {
        it.copy(lensId = set.id, focalMm = set.clampFocal(it.focalMm), squeezeOverride = null, zoomManual = null)
    }

    fun setFocal(mm: Double) = _rig.update {
        it.copy(focalMm = PlaceholderCatalog.lens(it.lensId).clampFocal(mm), zoomManual = null)
    }

    fun setT(t: Double) = _rig.update { it.copy(tStop = t) }
    fun setFocus(m: Double) = _rig.update { it.copy(focusM = m.coerceIn(0.5, 60.0)) }

    fun cycleSqueeze() = _rig.update {
        val choices = PlaceholderCatalog.squeezeChoices
        val next = choices[(choices.indexOf(it.squeezeOverride) + 1) % choices.size]
        it.copy(squeezeOverride = next)
    }

    // ---- framelines ----

    /** Switches one frameline on or off. Switching on makes it the active one. */
    fun toggleFrameline(id: String) = _rig.update {
        if (id in it.framelines) it.copy(framelines = it.framelines - id) else it.copy(framelines = it.framelines + id)
    }

    fun clearFramelines() = _rig.update { it.copy(framelines = emptyList(), safeAreas = emptyList()) }

    /** Adds a custom ratio to the project and switches it on. */
    fun addCustomRatio(aspect: Double) = _rig.update {
        val c = Framelines.custom(aspect)
        val customs = if (it.customRatios.any { r -> Framelines.nearlyEqual(r, c.aspect) }) it.customRatios
        else (it.customRatios + c.aspect).takeLast(Framelines.MAX_CUSTOMS)
        val id = Framelines.custom(customs.first { r -> Framelines.nearlyEqual(r, c.aspect) }).id
        it.copy(customRatios = customs, framelines = (it.framelines - id) + id)
    }

    fun removeCustomRatio(aspect: Double) = _rig.update {
        val id = Framelines.custom(aspect).id
        it.copy(
            customRatios = it.customRatios.filterNot { r -> Framelines.nearlyEqual(r, aspect) },
            framelines = it.framelines - id,
        )
    }

    fun toggleSafeArea(percent: Int) = _rig.update {
        val next = if (percent in it.safeAreas) it.safeAreas - percent else (it.safeAreas + percent).sorted()
        it.copy(safeAreas = next)
    }

    fun toggleThirds() = _rig.update { it.copy(showThirds = !it.showThirds) }

    // ---- zoom ----

    /** Sets a manual zoom ratio (already clamped by the caller to the camera's range). Switches off lens matching. */
    fun setZoom(total: Double) = _rig.update { it.copy(zoomManual = total) }
    fun zoomAuto() = _rig.update { it.copy(zoomManual = null) }
    fun toggleDigital() = _rig.update { it.copy(allowDigital = !it.allowDigital) }

    // ---- panels ----

    fun toggleDof() = _rig.update { it.copy(showDof = !it.showDof) }
    fun toggleInfo() = _rig.update { it.copy(showInfo = !it.showInfo) }

    /**
     * Debug-only: `frame=2.39,1.85;safe=90;zoom=2|auto;digital=1;thirds=1;lens=ana2-primes;focal=50;picker=frames`.
     * Lets a test run drive the UI without touch injection. Unknown keys are ignored.
     */
    fun applyDebugScript(script: String) {
        for (raw in script.split(';')) {
            val part = raw.trim()
            if (part == "reset") { _rig.value = Rig(); continue }
            val eq = part.indexOf('=')
            if (eq < 0) continue
            val k = part.substring(0, eq).trim()
            val v = part.substring(eq + 1).trim()
            when (k) {
                "frame" -> _rig.update { it.copy(framelines = v.split(',').filter { s -> s.isNotBlank() }) }
                "custom" -> v.split(',').mapNotNull { it.toDoubleOrNull() }.forEach(::addCustomRatio)
                "safe" -> _rig.update { it.copy(safeAreas = v.split(',').mapNotNull { s -> s.toIntOrNull() }.filter { s -> s in Framelines.safeAreas }.sorted()) }
                "zoom" -> if (v == "auto") zoomAuto() else v.toDoubleOrNull()?.let(::setZoom)
                "digital" -> _rig.update { it.copy(allowDigital = v == "1") }
                "thirds" -> _rig.update { it.copy(showThirds = v == "1") }
                "info" -> _rig.update { it.copy(showInfo = v == "1") }
                "camera" -> { // camera=<bodyId>/<modeId>
                    val (b, m) = v.split('/').let { it.getOrElse(0) { "" } to it.getOrElse(1) { "" } }
                    PlaceholderCatalog.cameras.firstOrNull { it.id == b }?.let { body ->
                        selectCamera(body, body.modes.firstOrNull { it.id == m } ?: body.modes.first())
                    }
                }
                "lens" -> PlaceholderCatalog.lenses.firstOrNull { it.id == v }?.let(::selectLens)
                "focal" -> v.toDoubleOrNull()?.let(::setFocal)
                "picker" -> pendingPicker.value = when (v) {
                    "frames" -> Picker.Guides
                    "camera" -> Picker.Camera
                    "lens" -> Picker.Lens
                    else -> null
                }
            }
        }
    }
}
