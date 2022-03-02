package com.example.cleanarchitecturetest.di

import com.example.cleanarchitecturetest.data.repository.UserRepositoryImpl
import com.example.cleanarchitecturetest.data.storage.UserStorage
import com.example.cleanarchitecturetest.data.storage.sharedprefs.SharedPrefsUserStorage
import com.example.cleanarchitecturetest.domain.repository.UserRepository
import org.koin.dsl.module


val dataModule = module{

    single<UserStorage>{
        SharedPrefsUserStorage(context = get())
    }

    single<UserRepository>{
        UserRepositoryImpl(userStorage = get())
    }
}