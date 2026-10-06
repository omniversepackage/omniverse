package com.yodesla.omniverse.feature.home.profiles

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yodesla.omniverse.core.data.BedtimeWindow
import com.yodesla.omniverse.core.data.KidsLimits
import com.yodesla.omniverse.core.data.Profile
import com.yodesla.omniverse.core.data.ProfileRepository
import com.yodesla.omniverse.core.data.UserDataRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@Immutable
data class ProfilesState(
    val items: List<Profile> = emptyList(),
    val currentId: String = ProfileRepository.DEFAULT_ID,
    val pickerAtStart: Boolean = true,
    /** Non-null while the editor is open (a placeholder row for a new profile). */
    val editing: Profile? = null,
    val isNew: Boolean = false,
    val nameDraft: String = "",
    val avatarDraft: Int = 0,
    val kidsDraft: Boolean = false,
    /** Task 106: Kids daily allowance in minutes (0 = Off) and the bedtime window (null = Off). */
    val limitDraft: Int = 0,
    val bedtimeStartDraft: Int? = null,
    val bedtimeEndDraft: Int? = null,
    val confirmDelete: Boolean = false,
    /** Set when a new profile was saved: the shell switches to it (PIN + restart). */
    val createdId: String? = null,
)

class ProfilesViewModel(
    private val profiles: ProfileRepository,
    private val userData: UserDataRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(ProfilesState())
    val state: StateFlow<ProfilesState> = _state.asStateFlow()

    init {
        combine(
            profiles.profiles(),
            userData.setting(ProfileRepository.CURRENT_KEY),
            userData.setting(ProfileRepository.PICKER_KEY),
        ) { items, storedId, picker ->
            _state.update {
                it.copy(
                    items = items,
                    currentId = items.firstOrNull { p -> p.id == storedId }?.id ?: items.firstOrNull()?.id ?: ProfileRepository.DEFAULT_ID,
                    pickerAtStart = picker != "false",
                )
            }
        }.launchIn(viewModelScope)
    }

    fun openNew() = _state.update {
        it.copy(
            editing = Profile("", "", 0, false), isNew = true,
            nameDraft = "", avatarDraft = nextFreeAvatar(it.items), kidsDraft = false, confirmDelete = false,
            limitDraft = 0, bedtimeStartDraft = null, bedtimeEndDraft = null,
        )
    }

    fun openEdit(id: String) {
        _state.update { s ->
            val p = s.items.firstOrNull { it.id == id } ?: return@update s
            s.copy(
                editing = p, isNew = false,
                nameDraft = p.name, avatarDraft = p.avatar, kidsDraft = p.isKids, confirmDelete = false,
                limitDraft = 0, bedtimeStartDraft = null, bedtimeEndDraft = null,
            )
        }
        // Limits live outside the profile row, so they arrive one step after the row itself.
        viewModelScope.launch {
            val limits = profiles.limits(id)
            _state.update { s ->
                if (s.editing?.id != id) return@update s
                s.copy(
                    limitDraft = limits.dailyLimitMinutes,
                    bedtimeStartDraft = limits.bedtime?.startMinute,
                    bedtimeEndDraft = limits.bedtime?.endMinute,
                )
            }
        }
    }

    fun closeEditor() = _state.update { it.copy(editing = null, confirmDelete = false) }

    fun setName(value: String) = _state.update { it.copy(nameDraft = value.take(40)) }
    fun setAvatar(value: Int) = _state.update { it.copy(avatarDraft = value.coerceIn(0, ProfileRepository.AVATAR_COUNT - 1)) }
    fun setKids(value: Boolean) = _state.update { it.copy(kidsDraft = value) }

    fun setDailyLimit(minutes: Int) = _state.update { it.copy(limitDraft = minutes.takeIf { m -> m in KidsLimits.DAILY_CHOICES } ?: 0) }

    fun setBedtimeEnabled(enabled: Boolean) = _state.update { s ->
        when {
            !enabled -> s.copy(bedtimeStartDraft = null, bedtimeEndDraft = null)
            s.bedtimeStartDraft != null && s.bedtimeEndDraft != null -> s
            else -> s.copy(bedtimeStartDraft = DEFAULT_BEDTIME_START, bedtimeEndDraft = DEFAULT_BEDTIME_END)
        }
    }

    fun shiftBedtimeStart(deltaMinutes: Int) = _state.update { s ->
        val start = s.bedtimeStartDraft ?: return@update s
        s.copy(bedtimeStartDraft = shiftMinute(start, deltaMinutes))
    }

    fun shiftBedtimeEnd(deltaMinutes: Int) = _state.update { s ->
        val end = s.bedtimeEndDraft ?: return@update s
        s.copy(bedtimeEndDraft = shiftMinute(end, deltaMinutes))
    }

    fun save() = viewModelScope.launch {
        val s = _state.value
        val editing = s.editing ?: return@launch
        val limits = KidsLimits(
            s.limitDraft,
            s.bedtimeStartDraft?.let { start -> s.bedtimeEndDraft?.let { end -> BedtimeWindow(start, end) } },
        )
        if (s.isNew) {
            val created = profiles.create(s.nameDraft, s.avatarDraft, s.kidsDraft)
            profiles.setLimits(created.id, limits)
            _state.update { it.copy(editing = null, createdId = created.id) }
        } else {
            profiles.rename(editing.id, s.nameDraft)
            profiles.setAvatar(editing.id, s.avatarDraft)
            profiles.setKids(editing.id, s.kidsDraft)
            profiles.setLimits(editing.id, limits)
            _state.update { it.copy(editing = null) }
        }
    }

    fun consumeCreated() = _state.update { it.copy(createdId = null) }

    fun askDelete() = _state.update { it.copy(confirmDelete = true) }
    fun cancelDelete() = _state.update { it.copy(confirmDelete = false) }

    fun deleteNow() = viewModelScope.launch {
        val id = _state.value.editing?.id ?: return@launch
        if (profiles.delete(id)) _state.update { it.copy(editing = null, confirmDelete = false) }
        else _state.update { it.copy(confirmDelete = false) }
    }

    fun setPickerAtStart(enabled: Boolean) = viewModelScope.launch {
        userData.putSetting(ProfileRepository.PICKER_KEY, enabled.toString())
    }

    private fun nextFreeAvatar(items: List<Profile>): Int {
        val used = items.map { it.avatar % ProfileRepository.AVATAR_COUNT }.toSet()
        return (0 until ProfileRepository.AVATAR_COUNT).firstOrNull { it !in used } ?: (items.size % ProfileRepository.AVATAR_COUNT)
    }

    /** Bedtime steps wrap the clock: 23:30 + 30 min is 00:00, not 24:00. */
    private fun shiftMinute(minuteOfDay: Int, deltaMinutes: Int): Int {
        val perDay = BedtimeWindow.MINUTES_PER_DAY
        return ((minuteOfDay + deltaMinutes) % perDay + perDay) % perDay
    }

    private companion object {
        /** A sensible first bedtime when a parent turns the rule on: 20:00-07:00. */
        const val DEFAULT_BEDTIME_START = 20 * 60
        const val DEFAULT_BEDTIME_END = 7 * 60
    }
}
