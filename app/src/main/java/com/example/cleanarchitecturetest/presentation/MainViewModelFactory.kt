package com.example.cleanarchitecturetest.presentation

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.example.cleanarchitecturetest.data.repository.UserRepositoryImpl
import com.example.cleanarchitecturetest.data.storage.sharedprefs.SharedPrefsUserStorage
import com.example.cleanarchitecturetest.domain.usecase.GetUserNameUseCase
import com.example.cleanarchitecturetest.domain.usecase.SaveUserNameUseCase

class MainViewModelFactory(context: Context) : ViewModelProvider.Factory {

    private val repository by lazy(LazyThreadSafetyMode.NONE) {UserRepositoryImpl(SharedPrefsUserStorage(context))}
    private val getUserNameUseCase by lazy(LazyThreadSafetyMode.NONE) { GetUserNameUseCase(repository) }
    private val saveUserNameUseCase by lazy(LazyThreadSafetyMode.NONE) { SaveUserNameUseCase(repository) }

    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return MainViewModel(getUserNameUseCase, saveUserNameUseCase) as T
    }
}