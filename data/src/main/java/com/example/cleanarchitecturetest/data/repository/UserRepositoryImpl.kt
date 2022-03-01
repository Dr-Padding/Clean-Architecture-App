package com.example.cleanarchitecturetest.data.repository

import com.example.cleanarchitecturetest.data.storage.models.User
import com.example.cleanarchitecturetest.data.storage.UserStorage
import com.example.cleanarchitecturetest.domain.models.SaveUserNameParam
import com.example.cleanarchitecturetest.domain.models.UserName
import com.example.cleanarchitecturetest.domain.repository.UserRepositoryInterface



class UserRepository(private val userStorage: UserStorage) : UserRepositoryInterface {

    override fun save(nameOnly: SaveUserNameParam): Boolean {
        val user = mapToStorage(nameOnly)
        return userStorage.save(user)
    }

    override fun get(): UserName {
        val user = userStorage.get()
        return mapToDomain(user)
    }

    private fun mapToStorage(nameOnly: SaveUserNameParam) : User{
        return User(firstName = nameOnly.name, lastName = "Default")
    }

    private fun mapToDomain(user: User) : UserName{
        return UserName(firstName = user.firstName, lastName = user.lastName)
    }

}