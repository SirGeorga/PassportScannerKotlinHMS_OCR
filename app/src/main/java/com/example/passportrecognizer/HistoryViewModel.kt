package com.example.passportrecognizer

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request

class HistoryViewModel : ViewModel() {
    private val _data = MutableLiveData<List<ServerHistoryResponse>>()
    val data: LiveData<List<ServerHistoryResponse>> = _data

    fun fetchData() {
        viewModelScope.launch {
            val client = OkHttpClient()
            val request = Request.Builder()
                .url("https://crm.tpu.ru/bitrix/js/LocalApps/olimpHandler/wh.php")
                .build()

            try {
                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    val jsonResponse = response.body!!.string()
                    val gson = Gson()
                    val dataModels = gson.fromJson<List<ServerHistoryResponse>>(jsonResponse, object : TypeToken<List<ServerHistoryResponse>>() {}.type)
                    _data.postValue(dataModels)
                } else {
                    // Обработка ошибки
                }
            } catch (e: Exception) {
                // Обработка исключения
            }
        }
    }
}