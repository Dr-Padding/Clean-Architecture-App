package com.example.cleanarchitecturetest.data.repository

import com.example.cleanarchitecturetest.data.mappers.mapToDomain
import com.example.cleanarchitecturetest.data.mappers.mapToStorage
import com.example.cleanarchitecturetest.data.storage.models.User
import com.example.cleanarchitecturetest.data.storage.UserStorage
import com.example.cleanarchitecturetest.domain.models.SaveUserNameParam
import com.example.cleanarchitecturetest.domain.models.UserName
import com.example.cleanarchitecturetest.domain.repository.UserRepository



class UserRepositoryImpl(private val userStorage: UserStorage) :
    UserRepository {
    override fun save(nameOnly: SaveUserNameParam): Boolean {
        val user = nameOnly.mapToStorage()
        return userStorage.save(user)
    }

    override fun get(): UserName {
        val user = userStorage.get()
        return user.mapToDomain()
    }
}