package com.code5150.api

class ContractException(
    val statusCode: Int,
    val code: String,
    message: String = code,
) : RuntimeException(message)
