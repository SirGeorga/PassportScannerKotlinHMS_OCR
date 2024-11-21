package com.example.passportrecognizer

data class ServerResponse (
    val success: Boolean,
    val message: String,
    val data: Data?
)

data class Data(
    val surname: String,
    val patronymic: String,
    val birth_date: String,
    val sex: String,
    val grade: String,
    val doc_type: String,
    val doc_series: String,
    val doc_numb: String,
    val doc_date: String,
    val issued_by: String,
    val olimp_id: String
)