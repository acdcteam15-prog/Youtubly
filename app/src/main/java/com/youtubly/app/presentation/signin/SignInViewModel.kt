package com.youtubly.app.presentation.signin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.youtubly.app.domain.repository.AuthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SignInViewModel @Inject constructor(
    private val authRepository: AuthRepository
) : ViewModel() {
    fun save(cookies: String, onDone: () -> Unit) {
        viewModelScope.launch {
            authRepository.saveSession(cookies)
            onDone()
        }
    }
}
