package com.example.cleanarchitecturetest.domain.usecase

import com.example.cleanarchitecturetest.domain.models.UserName
import com.example.cleanarchitecturetest.domain.repository.UserRepositoryInterface

class GetUserNameUseCase(private val userRepository : UserRepositoryInterface) {
    fun execute() : UserName{
        return userRepository.get()
    }
}