package com.example.cleanarchitecturetest.data.mappers

import com.example.cleanarchitecturetest.data.storage.models.User
import com.example.cleanarchitecturetest.domain.models.SaveUserNameParam
import com.example.cleanarchitecturetest.domain.models.UserName

fun SaveUserNameParam.mapToStorage() : User {
    return User(firstName = this.name, lastName = "Default")
}

fun User.mapToDomain() : UserName {
    return UserName(firstName = this.firstName, lastName = this.lastName)
}