package com.example.cleanarchitecturetest.domain.usecase

import com.example.cleanarchitecturetest.domain.models.SaveUserNameParam
import com.example.cleanarchitecturetest.domain.repository.UserRepository

class SaveUserNameUseCase(private val userRepositoty : UserRepository) {
    operator fun invoke (nameOnly: SaveUserNameParam): Boolean{

        val oldName = userRepositoty.get()
        if (oldName.firstName == nameOnly.name){
            return false
        }

        if (nameOnly.name.isEmpty()){
            return false
        }

        val result = userRepositoty.save(nameOnly)
        return result
    }
}