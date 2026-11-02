package com.example.history

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.PrettyNightsApp
import com.example.logging.*
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SessionMetricsState(
    val imuSummary: ImuSessionSummary = ImuSessionSummary(),
    val boschSamples: List<BoschSampleEntity> = emptyList(),
    val stateIntervals: List<StateIntervalEntity> = emptyList(),
    val rideMarkers: List<RideMarkerEntity> = emptyList(),
    val boschReport: BoschSessionReliabilityReport? = null,
    val isLoading: Boolean = false
)

class HistoryViewModel(
    private val logRepository: LogRepository,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : ViewModel() {

    val sessions: StateFlow<List<SessionEntity>> = logRepository.allSessions
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _expandedSessionId = MutableStateFlow<Long?>(null)
    val expandedSessionId: StateFlow<Long?> = _expandedSessionId.asStateFlow()

    private val _metricsBySessionId = MutableStateFlow<Map<Long, SessionMetricsState>>(emptyMap())
    val metricsBySessionId: StateFlow<Map<Long, SessionMetricsState>> = _metricsBySessionId.asStateFlow()

    fun toggleExpand(sessionId: Long) {
        val next = if (_expandedSessionId.value == sessionId) null else sessionId
        _expandedSessionId.value = next
        if (next != null) {
            loadSessionMetrics(next)
        }
    }

    fun loadSessionMetrics(sessionId: Long) {
        _metricsBySessionId.update { current ->
            current + (sessionId to (current[sessionId]?.copy(isLoading = true) ?: SessionMetricsState(isLoading = true)))
        }
        viewModelScope.launch {
            val loaded = withContext(ioDispatcher) {
                val summary = logRepository.getImuSessionSummary(sessionId)
                val bosch = logRepository.getBoschSamples(sessionId).first()
                val intervals = logRepository.getStateIntervals(sessionId).first()
                val markers = logRepository.getRideMarkers(sessionId).first()
                val report = SessionExportHelper.computeBoschReportFromSummary(summary, bosch)
                SessionMetricsState(
                    imuSummary = summary,
                    boschSamples = bosch,
                    stateIntervals = intervals,
                    rideMarkers = markers,
                    boschReport = report,
                    isLoading = false
                )
            }
            _metricsBySessionId.update { it + (sessionId to loaded) }
        }
    }

    fun deleteSession(sessionId: Long) {
        viewModelScope.launch(ioDispatcher) {
            logRepository.deleteSession(sessionId)
            if (_expandedSessionId.value == sessionId) {
                _expandedSessionId.value = null
            }
            _metricsBySessionId.update { it - sessionId }
        }
    }

    fun updateSessionMetadata(sessionId: Long, title: String, notes: String) {
        viewModelScope.launch(ioDispatcher) {
            logRepository.updateSessionMetadata(sessionId, title, notes)
        }
    }

    fun exportSessionCsv(context: Context, session: SessionEntity) {
        viewModelScope.launch(ioDispatcher) {
            val fullImuSamples = logRepository.getImuSamples(session.id).first()
            val boschSamples = logRepository.getBoschSamples(session.id).first()
            val rideMarkers = logRepository.getRideMarkers(session.id).first()
            val report = SessionExportHelper.computeBoschReport(fullImuSamples, boschSamples)
            SessionExportHelper.exportAndShareSessionCsv(
                context = context,
                session = session,
                imuSamples = fullImuSamples,
                boschSamples = boschSamples,
                markers = rideMarkers,
                report = report
            )
        }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as PrettyNightsApp
                HistoryViewModel(app.container.logRepository)
            }
        }
    }
}
