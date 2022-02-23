package com.example.cleanarchitecturetest.presentation

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import com.example.cleanarchitecturetest.domain.models.SaveUserNameParam
import com.example.cleanarchitecturetest.domain.usecase.GetUserNameUseCase
import com.example.cleanarchitecturetest.domain.usecase.SaveUserNameUseCase

class MainViewModel(
    private val getUserNameUseCase: GetUserNameUseCase,
    private val saveUserNameUseCase: SaveUserNameUseCase
) : ViewModel() {

    private val _liveData =
        MutableLiveData<MainScreenState>().also { it.value = MainScreenState() }
    val liveData: LiveData<MainScreenState> = _liveData


    fun get() {
        val userName = getUserNameUseCase.execute()
        val fullName = "${userName.firstName} ${userName.lastName}"
        _liveData.postValue(_liveData.value?.copy(fullName = fullName))
    }

    fun save(text: String){
        val nameOnly = SaveUserNameParam(name = text)
        val result = saveUserNameUseCase.execute(nameOnly = nameOnly)
        _liveData.postValue(_liveData.value?.copy(fullName = "Save result = ${result}"))
    }

}