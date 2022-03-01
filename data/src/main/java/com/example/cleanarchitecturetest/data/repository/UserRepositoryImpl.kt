package com.example.cleanarchitecturetest.data.repository

import com.example.cleanarchitecturetest.data.storage.models.User
import com.example.cleanarchitecturetest.data.storage.UserStorage
import com.example.cleanarchitecturetest.domain.models.SaveUserNameParam
import com.example.cleanarchitecturetest.domain.models.UserName
import com.example.cleanarchitecturetest.domain.repository.UserRepository



class UserRepositoryImpl(private val userStorage: UserStorage) :
    UserRepository {

    //    override fun save(nameOnly: SaveUserNameParam): Boolean {
//        val user = mapToStorage(nameOnly)
//        return userStorage.save(user)
//    }
//
//    override fun get(): UserName {
//        val user = userStorage.get()
//        return mapToDomain(user)
//    }
//
//    private fun mapToStorage(nameOnly: SaveUserNameParam) : User{
//        return User(firstName = nameOnly.name, lastName = "Default")
//    }
//
//    private fun mapToDomain(user: User) : UserName{
//        return UserName(firstName = user.firstName, lastName = user.lastName)
//    }

    override fun save(nameOnly: SaveUserNameParam): Boolean {
        val user = nameOnly.mapToStorage()
        return userStorage.save(user)
    }

    override fun get(): UserName {
        val user = userStorage.get()
        return user.mapToDomain()
    }

    fun SaveUserNameParam.mapToStorage() : User {
        return User(firstName = this.name, lastName = "Default")
    }

    fun User.mapToDomain() : UserName {
        return UserName(firstName = this.firstName, lastName = this.lastName)
    }

}