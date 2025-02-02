package com.example.passportrecognizer

import android.content.Intent
import android.os.Bundle
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.example.passportrecognizer.databinding.ActivityHistoryBinding

class HistoryActivity : AppCompatActivity() {

    private val viewModel: HistoryViewModel by viewModels()


    private lateinit var binding: ActivityHistoryBinding
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_history)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }


        binding = ActivityHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val recyclerView = binding.rvHistory
        val adapter = HistoryAdapter()
        recyclerView.adapter = adapter

        val bottomNavigationView = binding.bottomNavigationView

        bottomNavigationView.selectedItemId = R.id.historyFragment

        bottomNavigationView.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.addClent -> {
                    startActivity(Intent(this, MainActivity::class.java))
                    finish()
                    true
                }

                R.id.historyFragment -> true // Текущая Activity, ничего не делаем
                else -> false
            }
        }

        viewModel.data.observe(this)
        { dataModels ->
            // Обновляем RecyclerView
            //adapter.setData(dataModels)
            adapter.notifyDataSetChanged()
        }

        viewModel.fetchData()
    }
}