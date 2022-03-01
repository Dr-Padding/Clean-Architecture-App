package com.example.cleanarchitecturetest.domain.repository

import com.example.cleanarchitecturetest.domain.models.SaveUserNameParam
import com.example.cleanarchitecturetest.domain.models.UserName

interface UserRepository {

    fun save(nameOnly: SaveUserNameParam) : Boolean

    fun get() : UserName
}