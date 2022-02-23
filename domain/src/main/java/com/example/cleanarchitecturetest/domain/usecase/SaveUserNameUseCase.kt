package com.example.cleanarchitecturetest.domain.usecase

import com.example.cleanarchitecturetest.domain.models.SaveUserNameParam
import com.example.cleanarchitecturetest.domain.repository.UserRepositoryInterface

class SaveUserNameUseCase(private val userRepositoty : UserRepositoryInterface) {
    fun execute(nameOnly: SaveUserNameParam): Boolean{

        val oldName = userRepositoty.get()

        if (oldName.firstName == nameOnly.name){
            return true
        }

        val result = userRepositoty.save(nameOnly)
        return result
    }
}