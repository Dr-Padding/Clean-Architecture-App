package com.example.cleanarchitecturetest.presentation

import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import com.example.cleanarchitecturetest.data.repository.UserRepository
import com.example.cleanarchitecturetest.data.storage.sharedprefs.SharedPrefsUserStorage
import com.example.cleanarchitecturetest.databinding.ActivityMainBinding
import com.example.cleanarchitecturetest.domain.models.SaveUserNameParam
import com.example.cleanarchitecturetest.domain.usecase.GetUserNameUseCase
import com.example.cleanarchitecturetest.domain.usecase.SaveUserNameUseCase

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var viewModel: MainViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        //val viewModel: MainViewModel by viewModels()
        viewModel = ViewModelProvider(this, MainViewModelFactory(this))[MainViewModel::class.java]
        viewModel.liveData.observe(this) { liveData ->
            binding.tvUserData.text = liveData.fullName
        }

        binding.btnGetData.setOnClickListener {
            viewModel.get()
        }

        binding.btnSaveData.setOnClickListener {
            val text = binding.etPutData.text.toString()
            viewModel.save(text)
        }

    }
}