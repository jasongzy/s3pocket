package com.jasongzy.s3pocket.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jasongzy.s3pocket.data.ProfileRepository
import com.jasongzy.s3pocket.data.ProfileStoreState
import com.jasongzy.s3pocket.data.model.StorageProfile
import com.jasongzy.s3pocket.data.model.FavoriteItem
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ProfilesViewModel(private val repository: ProfileRepository) : ViewModel() {
  val state: StateFlow<ProfileStoreState?> =
    repository.state.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

  val favorites: StateFlow<List<FavoriteItem>?> =
    repository.favorites.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

  fun delete(id: String) {
    viewModelScope.launch { repository.delete(id) }
  }

  fun deleteAll(ids: Set<String>) {
    viewModelScope.launch { repository.deleteAll(ids) }
  }

  fun reorder(profiles: List<StorageProfile>) {
    viewModelScope.launch { repository.reorder(profiles.map { it.id }) }
  }

  fun removeFavorites(favorites: List<FavoriteItem>, onResult: (Result<Unit>) -> Unit) {
    viewModelScope.launch {
      onResult(runSuspendCatching { repository.setFavorites(favorites, false) })
    }
  }

  fun reorderFavorites(favorites: List<FavoriteItem>) {
    viewModelScope.launch { repository.reorderFavorites(favorites.map { it.id }) }
  }
}
