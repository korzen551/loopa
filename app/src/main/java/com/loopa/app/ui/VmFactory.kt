package com.loopa.app.ui

import androidx.compose.runtime.Composable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.compose.viewModel
import com.loopa.app.LoopaApp

/**
 * Bez wstrzykiwania zaleznosci - jedna fabryka, ktora podaje [LoopaApp].
 * Przy tej skali kontener DI bylby wiekszy niz to, co mialby ogarniac.
 */
fun <T : ViewModel> appViewModelFactory(create: (LoopaApp) -> T): ViewModelProvider.Factory =
    object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <VM : ViewModel> create(modelClass: Class<VM>, extras: CreationExtras): VM {
            val app = extras[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as LoopaApp
            return create(app) as VM
        }
    }

@Composable
inline fun <reified T : ViewModel> appViewModel(
    key: String? = null,
    noinline create: (LoopaApp) -> T,
): T = viewModel(key = key, factory = appViewModelFactory(create))
