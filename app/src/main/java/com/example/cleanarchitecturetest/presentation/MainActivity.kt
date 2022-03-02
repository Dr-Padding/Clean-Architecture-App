package com.example.cleanarchitecturetest.presentation

import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import com.example.cleanarchitecturetest.databinding.ActivityMainBinding
import org.koin.androidx.viewmodel.ext.android.viewModel

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel by viewModel<MainViewModel>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

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