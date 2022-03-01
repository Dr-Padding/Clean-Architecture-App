package com.example.cleanarchitecturetest.domain.usecase

import com.example.cleanarchitecturetest.domain.models.UserName
import com.example.cleanarchitecturetest.domain.repository.UserRepository

class GetUserNameUseCase(private val userRepository : UserRepository) {
    operator fun invoke() : UserName{
        return userRepository.get()
    }
}
