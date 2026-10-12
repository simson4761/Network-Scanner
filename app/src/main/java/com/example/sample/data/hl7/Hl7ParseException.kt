package com.example.sample.data.hl7

import com.example.sample.data.AckStatus

class Hl7ParseException(val status: AckStatus, message: String) : Exception(message)